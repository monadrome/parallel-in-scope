package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ClosingFuture;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Adopted group members: a caller-supplied {@link ListenableFuture} owned by the group, cancelled
 * through the group's token tree, and released through its {@link ResourceCloser} when no owner
 * receives its value. See {@code design/task-group-adopted-futures.md}.
 */
class TaskGroupAdoptedFutureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final TaskKey<Resource> FIRST = TaskKey.named("first");
    private static final TaskKey<Resource> SECOND = TaskKey.named("second");
    private static final TaskKey<String> FAILING = TaskKey.named("failing");
    private static final TaskKey<String> TERMINAL = TaskKey.named("terminal");

    private final List<ParRuntime> runtimes = new ArrayList<>();
    private final List<ExecutorService> pools = new ArrayList<>();

    @AfterEach
    void shutDown() {
        for (ParRuntime runtime : runtimes) {
            runtime.close();
        }
        for (ExecutorService pool : pools) {
            pool.shutdownNow();
        }
    }

    private ExecutorService pool(int threads) {
        ExecutorService pool =
                new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(64));
        pools.add(pool);
        return pool;
    }

    private ParRuntime runtime(ExecutorService io) {
        ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), io).build();
        runtimes.add(runtime);
        return runtime;
    }

    private static Par io(ParRuntime runtime) {
        return runtime.par(ParId.of("io"));
    }

    /** A closeable value that counts how often it was released. */
    static final class Resource implements AutoCloseable {
        final String name;
        final AtomicInteger closes = new AtomicInteger();

        Resource(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** A source that records whether the cancellation it received interrupted it. */
    static final class RecordingFuture<T> extends AbstractFuture<T> {
        volatile boolean interrupted;

        @Override
        protected void interruptTask() {
            interrupted = true;
        }

        boolean complete(T value) {
            return set(value);
        }
    }

    /** A source that refuses cancellation, like a client future that keeps its request running. */
    static final class StubbornFuture<T> extends AbstractFuture<T> {
        final AtomicInteger cancelRequests = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelRequests.incrementAndGet();
            return false;
        }

        boolean complete(T value) {
            return set(value);
        }
    }

    @Test
    void adoptedValuesAreHandedToTheCallerOfASuccessfulGroup() {
        ParRuntime runtime = runtime(pool(2));
        Resource first = new Resource("first");
        Resource second = new Resource("second");
        SettableFuture<Resource> pending = SettableFuture.create();
        pools.get(0).execute(() -> pending.set(second));

        TaskGroupResult<Tuple2<Resource, Resource>, Void> result = runtime.group("load", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(first), Resource::close)
                .adopt(SECOND, pending, Resource::close)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        Tuple2<Resource, Resource> values =
                Objects.requireNonNull(result.valuesOrThrow().typedValues());
        assertThat(values.first()).isSameAs(first);
        assertThat(values.second()).isSameAs(second);
        assertThat(first.closes).hasValue(0);
        assertThat(second.closes).hasValue(0);
        assertThat(result.bodyCompletionConfirmed()).isTrue();
        TaskCompletion<?> observed = Objects.requireNonNull(result.members().get("second"));
        assertThat(observed.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(observed.result()).isSameAs(second);
    }

    @Test
    void adoptedAndRunMembersShareOneScopeAndFeedTheCombine() {
        ParRuntime runtime = runtime(pool(2));
        TaskKey<Integer> count = TaskKey.named("count");
        TaskKey<String> adopted = TaskKey.named("adopted");

        TaskGroupResult<Tuple2<String, Integer>, String> result = runtime.group("mixed", TIMEOUT)
                .adopt(adopted, Futures.immediateFuture("adopted"))
                .par(count, io(runtime), () -> 2)
                .combine(
                        TERMINAL,
                        io(runtime),
                        values -> Objects.requireNonNull(values).first() + ":"
                                + Objects.requireNonNull(values).second())
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.terminalValueOrThrow()).isEqualTo("adopted:2");
        assertThat(result.resultOf(adopted).outcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void aFailingMemberCancelsPendingSourcesWithInterruptAndClosesProducedValues() {
        ParRuntime runtime = runtime(pool(2));
        Resource produced = new Resource("produced");
        RecordingFuture<Resource> pending = new RecordingFuture<>();

        TaskGroupResult<?, Void> result = runtime.group("fail", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(produced), Resource::close)
                .adopt(SECOND, pending, Resource::close)
                .par(FAILING, io(runtime), () -> {
                    throw new IllegalStateException("boom");
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.failedTaskName()).isEqualTo("failing");
        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
        assertThat(result.resultOf(SECOND).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        assertThat(produced.closes).hasValue(1);
        assertThat(result.bodyCompletionConfirmed()).isTrue();
    }

    @Test
    void aFailedSourceIsAUserFailureThatCancelsItsSiblings() {
        ParRuntime runtime = runtime(pool(1));
        IOException failure = new IOException("connection reset");
        SettableFuture<Resource> sibling = SettableFuture.create();

        TaskGroupResult<?, Void> result = runtime.group("fail", TIMEOUT)
                .adopt(FIRST, Futures.immediateFailedFuture(failure))
                .adopt(SECOND, sibling)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.failedTaskName()).isEqualTo("first");
        assertThat(result.resultOf(FIRST).failure()).isSameAs(failure);
        assertThat(sibling.isCancelled()).isTrue();
    }

    @Test
    void theGroupDeadlineCancelsAPendingSource() {
        ParRuntime runtime = runtime(pool(1));
        SettableFuture<Resource> pending = SettableFuture.create();

        TaskGroupResult<?, Void> result = runtime.group("slow", Duration.ofMillis(50))
                .closeGrace(TIMEOUT)
                .adopt(FIRST, pending, Resource::close)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(pending.isCancelled()).isTrue();
        assertThat(result.bodyCompletionConfirmed()).isTrue();
    }

    @Test
    void cancellingAnAdoptedSourceIsADirectMemberCancellation() {
        ParRuntime runtime = runtime(pool(2));
        SettableFuture<Resource> cancelledByCaller = SettableFuture.create();
        SettableFuture<Resource> sibling = SettableFuture.create();
        pools.get(0).execute(() -> cancelledByCaller.cancel(false));

        TaskGroupResult<?, Void> result = runtime.group("cancel", TIMEOUT)
                .adopt(FIRST, cancelledByCaller)
                .adopt(SECOND, sibling)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(result.resultOf(FIRST).outcome()).isEqualTo(TaskOutcome.MEMBER_CANCELLED);
        assertThat(result.resultOf(SECOND).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(sibling.isCancelled()).isTrue();
    }

    @Test
    void aSourceCancelledAtAdmissionIsCancelledEvenWhenTheMirrorWasCancelledFirst() {
        ParRuntime runtime = runtime(pool(1));
        RecordingFuture<Resource> pending = new RecordingFuture<>();

        TaskGroupResult<?, Void> result = runtime.group("cancelled", TIMEOUT)
                .cancelOn(Futures.immediateFuture("request done"))
                .adopt(FIRST, pending)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
    }

    @Test
    void aValueArrivingAfterCancellationIsClosedAndTheSourceIsReportedUnfinishedUntilThen() {
        ParRuntime runtime = runtime(pool(1));
        StubbornFuture<Resource> stubborn = new StubbornFuture<>();

        TaskGroupResult<?, Void> result = runtime.group("late", Duration.ofMillis(50))
                .closeGrace(Duration.ofMillis(20))
                .adopt(FIRST, stubborn, Resource::close)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(stubborn.cancelRequests).hasValue(1);
        assertThat(result.bodyCompletionConfirmed()).isFalse();
        assertThat(result.unfinishedBodies()).containsEntry("first", 1);

        Resource late = new Resource("late");
        assertThat(stubborn.complete(late)).isTrue();
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(late.closes).hasValue(1));
    }

    @Test
    void theCombineOwnsTheAdoptedValuesOnceItsBodyIsEntered() {
        ParRuntime runtime = runtime(pool(1));
        Resource first = new Resource("first");
        AtomicReference<Resource> seen = new AtomicReference<>();

        TaskGroupResult<Resource, String> result = runtime.group("combine", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(first), Resource::close)
                .combine(TERMINAL, io(runtime), value -> {
                    seen.set(value);
                    throw new IllegalStateException("render failed");
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.failedTaskName()).isEqualTo("terminal");
        assertThat(seen.get()).isSameAs(first);
        assertThat(first.closes).hasValue(0);
    }

    @Test
    void aCombineThatNeverEntersLeavesTheValuesToTheCloser() {
        ParRuntime runtime = runtime(pool(1));
        Resource first = new Resource("first");
        AtomicBoolean combineRan = new AtomicBoolean();

        TaskGroupResult<?, String> result = runtime.group("combine", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(first), Resource::close)
                .adopt(SECOND, Futures.immediateFailedFuture(new IOException("down")), Resource::close)
                .combine(TERMINAL, io(runtime), values -> {
                    combineRan.set(true);
                    return "never";
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(combineRan).isFalse();
        assertThat(first.closes).hasValue(1);
    }

    @Test
    void exactlyOneOwnerReleasesEachValueWhenTheDeadlineRacesTheCombine() {
        ParRuntime runtime = runtime(pool(2));
        for (int run = 0; run < 200; run++) {
            Resource value = new Resource("raced-" + run);
            SettableFuture<Resource> source = SettableFuture.create();
            pools.get(0).execute(() -> source.set(value));

            TaskGroupResult<Resource, String> result = runtime.group("race", Duration.ofNanos(1 + run * 5_000L))
                    .closeGrace(TIMEOUT)
                    .adopt(FIRST, source, Resource::close)
                    .combine(TERMINAL, io(runtime), adopted -> {
                        try (Resource owned = Objects.requireNonNull(adopted)) {
                            return owned.name;
                        }
                    })
                    .runAll();

            assertThat(result.bodyCompletionConfirmed()).isTrue();
            int expected = source.isCancelled() ? 0 : 1;
            assertThat(value.closes)
                    .as("run %s ended %s", run, result.outcome())
                    .hasValue(expected);
        }
    }

    @Test
    void aFailingCloserNeverChangesTheOutcomeOrTheExitConfirmation() {
        ParRuntime runtime = runtime(pool(1));
        AtomicInteger attempts = new AtomicInteger();

        TaskGroupResult<?, Void> result = runtime.group("closer", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(new Resource("first")), resource -> {
                    attempts.incrementAndGet();
                    throw new IOException("close failed");
                })
                .par(FAILING, io(runtime), () -> {
                    throw new IllegalStateException("boom");
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.failedTaskName()).isEqualTo("failing");
        assertThat(attempts).hasValue(1);
        assertThat(result.bodyCompletionConfirmed()).isTrue();
    }

    @Test
    void aBlockingCloserCannotHoldRunAllPastItsCleanupBudget() throws Exception {
        ParRuntime runtime = runtime(pool(2));
        CountDownLatch closerEntered = new CountDownLatch(1);
        CountDownLatch releaseCloser = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        pools.add(caller);

        Future<TaskGroupResult<Tuple2<Resource, String>, Void>> running =
                caller.submit(() -> runtime.group("blocking-closer", TIMEOUT)
                        .closeGrace(Duration.ofMillis(50))
                        .adopt(FIRST, Futures.immediateFuture(new Resource("first")), resource -> {
                            closerEntered.countDown();
                            releaseCloser.await();
                        })
                        .par(FAILING, io(runtime), () -> {
                            throw new IllegalStateException("boom");
                        })
                        .runAll());
        try {
            TaskGroupResult<Tuple2<Resource, String>, Void> result = running.get(5, TimeUnit.SECONDS);

            assertThat(closerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.bodyCompletionConfirmed()).isFalse();
            assertThat(result.unfinishedBodies()).containsEntry("first", 1);
        } finally {
            releaseCloser.countDown();
        }
    }

    @Test
    void theConvergedReportCarriesTheSettleTimeOfAnAdoptedMember() throws Exception {
        ParRuntime runtime = runtime(pool(1));

        TaskGroup<Resource, Void> group = runtime.groupDraft("timed", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(new Resource("first")))
                .submitAll();

        TaskCompletion<?> snapshot = Objects.requireNonNull(
                group.completionFuture().get(5, TimeUnit.SECONDS).members().get("first"));
        assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(snapshot.startTimeNanos()).isNotZero();
        assertThat(snapshot.endTimeNanos()).isNotZero();
    }

    @Test
    void aRunAdmittedBeforeCloseStillDispatchesItsDiscardedValues() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        ConnectedFuture<Resource> failing = new ConnectedFuture<>();
        AtomicReference<Thread> closerThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        pools.add(caller);

        Future<TaskGroupResult<Tuple2<Resource, Resource>, Void>> running =
                caller.submit(() -> runtime.group("closing", TIMEOUT)
                        .closeGrace(TIMEOUT)
                        .adopt(
                                FIRST,
                                Futures.immediateFuture(new Resource("first")),
                                resource -> closerThread.set(Thread.currentThread()))
                        .adopt(SECOND, failing)
                        .runAll());
        assertThat(failing.connected.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.close();
        failing.fail(new IOException("down"));

        TaskGroupResult<Tuple2<Resource, Resource>, Void> result = running.get(5, TimeUnit.SECONDS);
        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.bodyCompletionConfirmed()).isTrue();
        assertThat(closerThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(Objects.requireNonNull(closerThread.get()).getName()).startsWith("ParRuntime-services-");
    }

    /** A source that reports when the group connects to it. */
    static final class ConnectedFuture<T> extends AbstractFuture<T> {
        final CountDownLatch connected = new CountDownLatch(1);

        @Override
        public void addListener(Runnable listener, Executor executor) {
            super.addListener(listener, executor);
            connected.countDown();
        }

        boolean fail(Throwable failure) {
            return setException(failure);
        }
    }

    @Test
    void aSourceListenerThatThrowsWhileTheGroupCancelsItDoesNotStopTheCascade() throws Exception {
        ParRuntime runtime = runtime(pool(2));
        SettableFuture<Resource> throwing = SettableFuture.create();
        throwing.addListener(
                () -> {
                    throw new AssertionError("caller listener");
                },
                directExecutor());
        SettableFuture<Resource> sibling = SettableFuture.create();
        Resource produced = new Resource("produced");
        TaskKey<Resource> third = TaskKey.named("third");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        pools.add(caller);

        Future<TaskGroupResult<?, Void>> running = caller.submit(() -> runtime.group("throwing-listener", TIMEOUT)
                .adopt(FIRST, throwing)
                .adopt(SECOND, sibling)
                .adopt(third, Futures.immediateFuture(produced), Resource::close)
                .par(FAILING, io(runtime), () -> {
                    throw new IllegalStateException("boom");
                })
                .runAll());
        TaskGroupResult<?, Void> result = running.get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(throwing.isCancelled()).isTrue();
        assertThat(sibling.isCancelled()).isTrue();
        assertThat(produced.closes).hasValue(1);
        assertThat(result.bodyCompletionConfirmed()).isTrue();
    }

    @Test
    void anAdmissionFailureFinishesItsCleanupPastAThrowingSourceListener() {
        ParRuntime runtime = runtime(pool(1));
        SettableFuture<Resource> throwing = SettableFuture.create();
        throwing.addListener(
                () -> {
                    throw new AssertionError("caller listener");
                },
                directExecutor());
        Resource produced = new Resource("produced");
        RecordingFuture<Resource> pending = new RecordingFuture<>();
        GroupStep<Tuple2<Tuple2<Resource, Resource>, Resource>> draft = runtime.group("closed", TIMEOUT)
                .adopt(FIRST, throwing)
                .adopt(SECOND, Futures.immediateFuture(produced), Resource::close)
                .adopt(TaskKey.<Resource>named("third"), pending);
        runtime.close();

        assertThatThrownBy(draft::runAll).isInstanceOf(IllegalStateException.class);

        assertThat(throwing.isCancelled()).isTrue();
        assertThat(produced.closes).hasValue(1);
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void aSourceThatCompletesWhileItsEarlierListenersRunStillReportsItsEndTime() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        ExecutorService completer = Executors.newSingleThreadExecutor();
        pools.add(completer);
        CompletingOnRegistration<Resource> source = new CompletingOnRegistration<>(new Resource("first"), completer);

        TaskGroup<Resource, Void> group =
                runtime.groupDraft("window", TIMEOUT).adopt(FIRST, source).submitAll();
        source.releaseBlocker.countDown();

        TaskCompletion<?> snapshot = Objects.requireNonNull(
                group.completionFuture().get(5, TimeUnit.SECONDS).members().get("first"));
        assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(snapshot.endTimeNanos()).isNotZero();
    }

    /**
     * A source that completes as soon as the group registers on it, while an earlier listener of
     * the source still runs, so the source is done before the group's own listener has run.
     */
    static final class CompletingOnRegistration<T> extends ForwardingListenableFuture<T> {
        final SettableFuture<T> delegate = SettableFuture.create();
        final CountDownLatch blockerEntered = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        private final T value;
        private final Executor completer;

        CompletingOnRegistration(T value, Executor completer) {
            this.value = value;
            this.completer = completer;
            delegate.addListener(
                    () -> {
                        blockerEntered.countDown();
                        Uninterruptibles.awaitUninterruptibly(releaseBlocker);
                    },
                    directExecutor());
        }

        @Override
        protected ListenableFuture<T> delegate() {
            return delegate;
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            delegate.addListener(listener, executor);
            completer.execute(() -> delegate.set(value));
            Uninterruptibles.awaitUninterruptibly(blockerEntered);
        }
    }

    @Test
    void anAdmissionFailureCancelsEverySourceAndClosesProducedValues() {
        ParRuntime runtime = runtime(pool(1));
        Resource produced = new Resource("produced");
        RecordingFuture<Resource> pending = new RecordingFuture<>();
        GroupStep<Tuple2<Resource, Resource>> draft = runtime.group("closed", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(produced), Resource::close)
                .adopt(SECOND, pending, Resource::close);
        runtime.close();

        assertThatThrownBy(draft::runAll).isInstanceOf(IllegalStateException.class);

        assertThat(produced.closes).hasValue(1);
        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
    }

    @Test
    void aMissingInheritedDeadlineAbandonsTheSourcesOffTheCallerThread() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        AtomicReference<Thread> closerThread = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        GroupStep<Resource> draft = runtime.groupInheriting("orphan")
                .adopt(FIRST, Futures.immediateFuture(new Resource("produced")), resource -> {
                    closerThread.set(Thread.currentThread());
                    closed.countDown();
                });

        assertThatThrownBy(draft::runAll).isInstanceOf(IllegalArgumentException.class);

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(closerThread.get()).isNotSameAs(Thread.currentThread());
    }

    @Test
    void aBlockingCloserOfAGroupThatConvergesInsideRunAllDoesNotHoldTheCaller() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        CountDownLatch releaseCloser = new CountDownLatch(1);
        AtomicReference<Thread> closerThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        pools.add(caller);
        AtomicReference<Thread> callerThread = new AtomicReference<>();

        Future<TaskGroupResult<Tuple2<Resource, Resource>, Void>> running = caller.submit(() -> {
            callerThread.set(Thread.currentThread());
            return runtime.group("immediate", TIMEOUT)
                    .closeGrace(Duration.ofMillis(50))
                    .adopt(FIRST, Futures.immediateFuture(new Resource("first")), resource -> {
                        closerThread.set(Thread.currentThread());
                        releaseCloser.await();
                    })
                    .adopt(SECOND, Futures.immediateFailedFuture(new IOException("down")))
                    .runAll();
        });
        try {
            TaskGroupResult<Tuple2<Resource, Resource>, Void> result = running.get(5, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.bodyCompletionConfirmed()).isFalse();
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> closerThread.get() != null);
            assertThat(closerThread.get()).isNotSameAs(callerThread.get());
        } finally {
            releaseCloser.countDown();
        }
    }

    @Test
    void aDeadlineDrivenDiscardRunsTheCloserOffTheThreadThatDeliversTheDeadline() throws Exception {
        ExecutorService io = pool(1);
        ScheduledExecutorService deliverer = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                .setNameFormat("deadline-deliverer-%d")
                .build());
        pools.add(deliverer);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .timeoutScheduler(deliverer)
                .build();
        runtimes.add(runtime);
        AtomicReference<String> closerThread = new AtomicReference<>();
        AtomicReference<String> convergingThread = new AtomicReference<>();
        CountDownLatch hold = new CountDownLatch(1);
        try {
            TaskGroup<Tuple2<Resource, String>, Void> group = runtime.groupDraft("deadline", Duration.ofMillis(50))
                    .closeGrace(TIMEOUT)
                    .adopt(
                            FIRST,
                            Futures.immediateFuture(new Resource("first")),
                            resource -> closerThread.set(Thread.currentThread().getName()))
                    .par(FAILING, io(runtime), () -> {
                        hold.await();
                        return "late";
                    })
                    .submitAll();
            group.completionFuture()
                    .addListener(
                            () -> convergingThread.set(Thread.currentThread().getName()), directExecutor());
            TaskGroupResult<Tuple2<Resource, String>, Void> result = group.finish();

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(convergingThread.get()).startsWith("deadline-deliverer-");
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> closerThread.get() != null);
            assertThat(closerThread.get()).startsWith("ParRuntime-services-");
        } finally {
            hold.countDown();
        }
    }

    @Test
    void declarationsRejectReusedSourcesForeignProducersAndNulls() {
        ParRuntime runtime = runtime(pool(1));
        ParRuntime other = runtime(pool(1));
        SettableFuture<Resource> source = SettableFuture.create();
        GroupStep<Resource> step = runtime.group("checks", TIMEOUT).adopt(FIRST, source);

        assertThatThrownBy(() -> step.adopt(SECOND, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second");
        GroupStart foreign = runtime.group("foreign", TIMEOUT);
        assertThatThrownBy(() -> foreign.adopt(FIRST, io(other), SettableFuture.<Resource>create()))
                .isInstanceOf(IllegalArgumentException.class);
        GroupStart nulls = runtime.group("nulls", TIMEOUT);
        assertThatThrownBy(() -> nulls.adopt(FIRST, nullFuture())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> nulls.adopt(FIRST, source, nullCloser())).isInstanceOf(NullPointerException.class);
        assertThat(source.isCancelled()).isFalse();
    }

    @SuppressWarnings("NullAway")
    private static ListenableFuture<Resource> nullFuture() {
        return null;
    }

    @Test
    void adoptDeclarationsShareNameUniquenessStageAndThreadConfinementWithPar() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        GroupStep<Resource> step = runtime.group("names", TIMEOUT).adopt(FIRST, SettableFuture.create());
        assertThatThrownBy(() -> step.adopt(FIRST, SettableFuture.<Resource>create()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate name 'first'");
        GroupStep<Resource> ran = runtime.group("par-then-adopt", TIMEOUT).par(FIRST, io(runtime), () -> null);
        assertThatThrownBy(() -> ran.adopt(FIRST, SettableFuture.<Resource>create()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate name 'first'");

        GroupStart start = runtime.group("stale", TIMEOUT);
        GroupStep<Resource> advanced = start.adopt(FIRST, SettableFuture.create());
        assertThatThrownBy(() -> start.adopt(SECOND, SettableFuture.<Resource>create()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer current");
        GroupStep<Tuple2<Resource, Resource>> next = advanced.adopt(SECOND, SettableFuture.create());
        assertThat(next).isNotNull();
        assertThatThrownBy(() -> advanced.adopt(TaskKey.<Resource>named("third"), SettableFuture.<Resource>create()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer current");

        GroupStart confinedStart = runtime.group("confined-start", TIMEOUT);
        GroupStep<Resource> confinedStep =
                runtime.group("confined-step", TIMEOUT).adopt(FIRST, SettableFuture.create());
        AtomicReference<Throwable> fromStart = new AtomicReference<>();
        AtomicReference<Throwable> fromStep = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                GroupStep<?> unused = confinedStart.adopt(FIRST, SettableFuture.<Resource>create());
            } catch (Throwable failure) {
                fromStart.set(failure);
            }
            try {
                GroupStep<?> unused = confinedStep.adopt(SECOND, SettableFuture.<Resource>create());
            } catch (Throwable failure) {
                fromStep.set(failure);
            }
        });
        other.start();
        other.join(TIMEOUT.toMillis());
        assertThat(fromStart.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread");
        assertThat(fromStep.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread");
    }

    @Test
    void theAttributedOverloadWithACloserReleasesValuesOfAFailedGroupOnEitherStage() {
        ParRuntime runtime = runtime(pool(2));
        Resource first = new Resource("first");
        Resource second = new Resource("second");

        TaskGroupResult<Tuple2<Tuple2<Resource, Resource>, String>, Void> result = runtime.group("attributed", TIMEOUT)
                .adopt(FIRST, io(runtime), Futures.immediateFuture(first), Resource::close)
                .adopt(SECOND, io(runtime), Futures.immediateFuture(second), Resource::close)
                .par(FAILING, io(runtime), () -> {
                    throw new IOException("boom");
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.resultOf(FIRST).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.resultOf(SECOND).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(first.closes).hasValue(1);
            assertThat(second.closes).hasValue(1);
        });
    }

    @Test
    void anAdoptingGroupStopsHoldingTheRuntimeOnceOwnershipIsSettled() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        Resource first = new Resource("first");

        TaskGroupResult<Resource, Void> result = runtime.group("settled", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture(first), Resource::close)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        runtime.close();
        assertThat(runtime.awaitQuiescence(TIMEOUT)).isTrue();
        assertThat(first.closes).hasValue(0);
    }

    @SuppressWarnings("NullAway")
    private static ResourceCloser<Resource> nullCloser() {
        return null;
    }

    @Test
    void guavaFutureShapesAreAdoptedAndCancelledThroughTheGroup() throws Exception {
        ExecutorService io = pool(2);
        ParRuntime runtime = runtime(io);
        ListeningExecutorService listening = MoreExecutors.listeningDecorator(io);
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch pipelineStarted = new CountDownLatch(1);
        AtomicBoolean pipelineResourceClosed = new AtomicBoolean();
        ListenableFuture<String> task = listening.submit(() -> {
            blocking.await();
            return "task";
        });
        FluentFuture<String> chained =
                FluentFuture.from(Futures.immediateFuture("chain")).transform(String::toUpperCase, directExecutor());
        ClosingFuture<String> closing = ClosingFuture.submit(
                closer -> {
                    closer.eventuallyClose(() -> pipelineResourceClosed.set(true), directExecutor());
                    pipelineStarted.countDown();
                    new CountDownLatch(1).await();
                    return "pipeline";
                },
                io);
        assertThat(pipelineStarted.await(5, TimeUnit.SECONDS)).isTrue();
        ListenableFuture<String> pipeline = closing.finishToFuture();

        TaskKey<String> chainedKey = TaskKey.named("chained");
        TaskGroupResult<?, Void> result = runtime.group("shapes", Duration.ofMillis(200))
                .adopt(chainedKey, chained)
                .adopt(TaskKey.<String>named("task"), io(runtime), task)
                .adopt(TaskKey.<String>named("pipeline"), io(runtime), pipeline)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(result.resultOf(chainedKey).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(task.isCancelled()).isTrue();
        assertThat(pipeline.isCancelled()).isTrue();
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilTrue(pipelineResourceClosed);
        blocking.countDown();
    }

    @Test
    void anAttributedSourceAwaitedOnItsOwnPoolIsAnExecutorSelfLoop() {
        ExecutorService io = pool(4);
        ParRuntime runtime = runtime(io);
        ListeningExecutorService listening = MoreExecutors.listeningDecorator(io);
        TaskKey<String> loaded = TaskKey.named("loaded");

        List<String> values = io(runtime)
                .map(
                        Collections.singletonList("outer"),
                        value -> runtime.groupInheriting("inner")
                                .adopt(loaded, io(runtime), listening.submit(() -> value))
                                .runAll()
                                .valuesOrThrow()
                                .valueOf(loaded),
                        BatchOptions.timeout("outer", TIMEOUT))
                .valuesOrThrow();

        assertThat(values).containsExactly("outer");
        List<ExecutorWaitReport.Component> cycles = runtime.executorWaitReport().cyclicComponents();
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).cycle()).containsExactly(Collections.singleton(ParId.of("io")));
        assertThat(cycles.get(0).toString()).contains("outer[").contains("loaded[");
    }

    @Test
    void anUnattributedSourceRecordsNoExecutorWait() {
        ExecutorService io = pool(4);
        ParRuntime runtime = runtime(io);
        ListeningExecutorService listening = MoreExecutors.listeningDecorator(io);
        TaskKey<String> loaded = TaskKey.named("loaded");

        io(runtime)
                .map(
                        Collections.singletonList("outer"),
                        value -> runtime.groupInheriting("inner")
                                .adopt(loaded, listening.submit(() -> value))
                                .runAll()
                                .valuesOrThrow()
                                .typedValues(),
                        BatchOptions.timeout("outer", TIMEOUT))
                .valuesOrThrow();

        assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
    }

    @Test
    void aSourceQueuedBehindItsOwnWaiterIsSeenByBothStarvationSignalsAndEndsAtTheDeadline() throws Exception {
        ExecutorService io = pool(1);
        ParRuntime runtime = runtime(io);
        ListeningExecutorService listening = MoreExecutors.listeningDecorator(io);
        AtomicReference<ListenableFuture<String>> queued = new AtomicReference<>();
        TaskKey<String> loaded = TaskKey.named("loaded");
        Duration interval = Duration.ofMillis(20);
        ThreadPoolStarvationReport report;
        TaskBatchResult<TaskOutcome> outer;

        try (ThreadPoolStarvationObservationScope observation = runtime.openThreadPoolStarvationObservation(interval)) {
            ListenableFuture<ThreadPoolStarvationReport> reportFuture = observation.reportFuture();
            outer = io(runtime)
                    .map(
                            Collections.singletonList("outer"),
                            value -> {
                                ListenableFuture<String> source = listening.submit(() -> value);
                                queued.set(source);
                                return runtime.group("inner", Duration.ofMillis(400))
                                        .adopt(loaded, io(runtime), source)
                                        .runAll()
                                        .outcome();
                            },
                            BatchOptions.timeout("outer", TIMEOUT));
            observation.close();
            report = Objects.requireNonNull(Futures.getDone(reportFuture));
        }

        assertThat(outer.valuesOrThrow()).containsExactly(TaskOutcome.TIMEOUT);
        assertThat(Objects.requireNonNull(queued.get()).isCancelled()).isTrue();
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION);
        assertThat(runtime.executorWaitReport().cyclicComponents()).hasSize(1);
    }
}

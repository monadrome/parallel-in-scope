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
 * Adopted group members: caller-supplied futures owned by the group from {@code runAll()}, cancelled
 * through its token tree, and mirrored into frozen member results. Resource lifetime remains with
 * the source's own pipeline or the value's recipient. See {@code design/task-group-adopted-futures.md}.
 */
class TaskGroupAdoptedFutureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final TaskKey<String> FIRST = TaskKey.named("first");
    private static final TaskKey<String> SECOND = TaskKey.named("second");
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

    /** A closeable value whose lifecycle is not owned by the group. */
    static final class CloseableValue implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

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
        SettableFuture<String> pending = SettableFuture.create();
        pools.get(0).execute(() -> pending.set("second"));

        TaskGroupResult<Tuple2<String, String>, Void> result = runtime.group("load", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture("first"))
                .adopt(SECOND, pending)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        Tuple2<String, String> values =
                Objects.requireNonNull(result.valuesOrThrow().typedValues());
        assertThat(values.first()).isEqualTo("first");
        assertThat(values.second()).isEqualTo("second");
        assertThat(result.executionExitConfirmed()).isTrue();
        TaskCompletion<?> observed = Objects.requireNonNull(result.members().get("second"));
        assertThat(observed.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(observed.result()).isEqualTo("second");
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
    void aFailingMemberCancelsPendingSourcesWithoutClosingTheirValues() {
        ParRuntime runtime = runtime(pool(2));
        CloseableValue produced = new CloseableValue();
        TaskKey<CloseableValue> producedKey = TaskKey.named("produced");
        RecordingFuture<String> pending = new RecordingFuture<>();

        TaskGroupResult<?, Void> result = runtime.group("fail", TIMEOUT)
                .adopt(producedKey, Futures.immediateFuture(produced))
                .adopt(SECOND, pending)
                .par(FAILING, io(runtime), () -> {
                    throw new IllegalStateException("boom");
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.failedTaskName()).isEqualTo("failing");
        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
        assertThat(result.resultOf(SECOND).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        assertThat(produced.closes).hasValue(0);
        assertThat(result.executionExitConfirmed()).isTrue();
    }

    @Test
    void aFailedSourceIsAUserFailureThatCancelsItsSiblings() {
        ParRuntime runtime = runtime(pool(1));
        IOException failure = new IOException("connection reset");
        SettableFuture<String> sibling = SettableFuture.create();

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
        SettableFuture<String> pending = SettableFuture.create();

        TaskGroupResult<?, Void> result = runtime.group("slow", Duration.ofMillis(50))
                .closeGrace(TIMEOUT)
                .adopt(FIRST, pending)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(pending.isCancelled()).isTrue();
        assertThat(result.executionExitConfirmed()).isTrue();
    }

    @Test
    void cancellingAnAdoptedSourceIsADirectMemberCancellation() {
        ParRuntime runtime = runtime(pool(2));
        SettableFuture<String> cancelledByCaller = SettableFuture.create();
        SettableFuture<String> sibling = SettableFuture.create();
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
        RecordingFuture<String> pending = new RecordingFuture<>();

        TaskGroupResult<?, Void> result = runtime.group("cancelled", TIMEOUT)
                .cancelOn(Futures.immediateFuture("request done"))
                .adopt(FIRST, pending)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
    }

    @Test
    void aLateValueIsNotClosedAndTheSourceIsReportedUnfinishedUntilItSettles() {
        ParRuntime runtime = runtime(pool(1));
        StubbornFuture<CloseableValue> stubborn = new StubbornFuture<>();
        TaskKey<CloseableValue> key = TaskKey.named("first");

        TaskGroupResult<?, Void> result = runtime.group("late", Duration.ofMillis(50))
                .closeGrace(Duration.ofMillis(20))
                .adopt(key, stubborn)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(stubborn.cancelRequests).hasValue(1);
        assertThat(result.executionExitConfirmed()).isFalse();
        assertThat(result.unexitedExecutions()).containsEntry("first", 1);

        CloseableValue late = new CloseableValue();
        assertThat(stubborn.complete(late)).isTrue();
        assertThat(late.closes).hasValue(0);
    }

    @Test
    void aFailedMemberSkipsTheCombine() {
        ParRuntime runtime = runtime(pool(1));
        AtomicBoolean combineRan = new AtomicBoolean();

        TaskGroupResult<?, String> result = runtime.group("combine", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture("first"))
                .adopt(SECOND, Futures.immediateFailedFuture(new IOException("down")))
                .combine(TERMINAL, io(runtime), values -> {
                    combineRan.set(true);
                    return "never";
                })
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(combineRan).isFalse();
    }

    @Test
    void theConvergedReportCarriesTheSettleTimeOfAnAdoptedMember() throws Exception {
        ParRuntime runtime = runtime(pool(1));

        TaskGroup<String, Void> group = runtime.groupDraft("timed", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture("first"))
                .submitAll();

        TaskCompletion<?> snapshot = Objects.requireNonNull(
                group.completionFuture().get(5, TimeUnit.SECONDS).members().get("first"));
        assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(snapshot.startTimeNanos()).isNotZero();
        assertThat(snapshot.endTimeNanos()).isNotZero();
    }

    @Test
    void aRunAdmittedBeforeCloseStillConvergesWhenItsSourceFails() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        ConnectedFuture<String> failing = new ConnectedFuture<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        pools.add(caller);

        Future<TaskGroupResult<Tuple2<String, String>, Void>> running =
                caller.submit(() -> runtime.group("closing", TIMEOUT)
                        .closeGrace(TIMEOUT)
                        .adopt(FIRST, Futures.immediateFuture("first"))
                        .adopt(SECOND, failing)
                        .runAll());
        assertThat(failing.connected.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.close();
        failing.fail(new IOException("down"));

        TaskGroupResult<Tuple2<String, String>, Void> result = running.get(5, TimeUnit.SECONDS);
        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.executionExitConfirmed()).isTrue();
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
        SettableFuture<String> throwing = SettableFuture.create();
        throwing.addListener(
                () -> {
                    throw new AssertionError("caller listener");
                },
                directExecutor());
        SettableFuture<String> sibling = SettableFuture.create();
        TaskKey<String> third = TaskKey.named("third");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        pools.add(caller);

        Future<TaskGroupResult<?, Void>> running = caller.submit(() -> runtime.group("throwing-listener", TIMEOUT)
                .adopt(FIRST, throwing)
                .adopt(SECOND, sibling)
                .adopt(third, Futures.immediateFuture("produced"))
                .par(FAILING, io(runtime), () -> {
                    throw new IllegalStateException("boom");
                })
                .runAll());
        TaskGroupResult<?, Void> result = running.get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(throwing.isCancelled()).isTrue();
        assertThat(sibling.isCancelled()).isTrue();
        assertThat(result.executionExitConfirmed()).isTrue();
    }

    @Test
    void anAdmissionFailureFinishesItsCleanupPastAThrowingSourceListener() {
        ParRuntime runtime = runtime(pool(1));
        SettableFuture<String> throwing = SettableFuture.create();
        throwing.addListener(
                () -> {
                    throw new AssertionError("caller listener");
                },
                directExecutor());
        RecordingFuture<String> pending = new RecordingFuture<>();
        GroupStep<Tuple2<Tuple2<String, String>, String>> draft = runtime.group("closed", TIMEOUT)
                .adopt(FIRST, throwing)
                .adopt(SECOND, Futures.immediateFuture("produced"))
                .adopt(TaskKey.<String>named("third"), pending);
        runtime.close();

        assertThatThrownBy(draft::runAll).isInstanceOf(IllegalStateException.class);

        assertThat(throwing.isCancelled()).isTrue();
        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
    }

    @Test
    void aSourceThatCompletesWhileItsEarlierListenersRunStillReportsItsEndTime() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        ExecutorService completer = Executors.newSingleThreadExecutor();
        pools.add(completer);
        CompletingOnRegistration<String> source = new CompletingOnRegistration<>("first", completer);

        TaskGroup<String, Void> group =
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
    void anAdmissionFailureCancelsEverySource() {
        ParRuntime runtime = runtime(pool(1));
        RecordingFuture<String> pending = new RecordingFuture<>();
        GroupStep<Tuple2<String, String>> draft = runtime.group("closed", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture("produced"))
                .adopt(SECOND, pending);
        runtime.close();

        assertThatThrownBy(draft::runAll).isInstanceOf(IllegalStateException.class);

        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
    }

    @Test
    void aMissingInheritedDeadlineAbandonsTheSources() {
        ParRuntime runtime = runtime(pool(1));
        RecordingFuture<String> pending = new RecordingFuture<>();
        GroupStep<String> draft = runtime.groupInheriting("orphan").adopt(FIRST, pending);

        assertThatThrownBy(draft::runAll).isInstanceOf(IllegalArgumentException.class);

        assertThat(pending.isCancelled()).isTrue();
        assertThat(pending.interrupted).isTrue();
    }

    @Test
    void aDeadlineDrivenConvergenceRunsOnTheThreadThatDeliversTheDeadline() throws Exception {
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
        AtomicReference<String> convergingThread = new AtomicReference<>();
        CountDownLatch hold = new CountDownLatch(1);
        try {
            TaskGroup<Tuple2<String, String>, Void> group = runtime.groupDraft("deadline", Duration.ofMillis(50))
                    .closeGrace(TIMEOUT)
                    .adopt(FIRST, Futures.immediateFuture("first"))
                    .par(FAILING, io(runtime), () -> {
                        hold.await();
                        return "late";
                    })
                    .submitAll();
            group.completionFuture()
                    .addListener(
                            () -> convergingThread.set(Thread.currentThread().getName()), directExecutor());
            TaskGroupResult<Tuple2<String, String>, Void> result = group.finish();

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(convergingThread.get()).startsWith("deadline-deliverer-");
        } finally {
            hold.countDown();
        }
    }

    @Test
    void declarationsRejectReusedSourcesForeignProducersAndNulls() {
        ParRuntime runtime = runtime(pool(1));
        ParRuntime other = runtime(pool(1));
        SettableFuture<String> source = SettableFuture.create();
        GroupStep<String> step = runtime.group("checks", TIMEOUT).adopt(FIRST, source);

        assertThatThrownBy(() -> step.adopt(SECOND, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second");
        GroupStart foreign = runtime.group("foreign", TIMEOUT);
        assertThatThrownBy(() -> foreign.adopt(FIRST, io(other), SettableFuture.<String>create()))
                .isInstanceOf(IllegalArgumentException.class);
        GroupStart nulls = runtime.group("nulls", TIMEOUT);
        assertThatThrownBy(() -> nulls.adopt(FIRST, nullFuture())).isInstanceOf(NullPointerException.class);
        assertThat(source.isCancelled()).isFalse();
    }

    @SuppressWarnings("NullAway")
    private static ListenableFuture<String> nullFuture() {
        return null;
    }

    @Test
    void adoptDeclarationsShareNameUniquenessStageAndThreadConfinementWithPar() throws Exception {
        ParRuntime runtime = runtime(pool(1));
        GroupStep<String> step = runtime.group("names", TIMEOUT).adopt(FIRST, SettableFuture.create());
        assertThatThrownBy(() -> step.adopt(FIRST, SettableFuture.<String>create()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate name 'first'");
        GroupStep<String> ran = runtime.group("par-then-adopt", TIMEOUT).par(FIRST, io(runtime), () -> null);
        assertThatThrownBy(() -> ran.adopt(FIRST, SettableFuture.<String>create()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate name 'first'");

        GroupStart start = runtime.group("stale", TIMEOUT);
        GroupStep<String> advanced = start.adopt(FIRST, SettableFuture.create());
        assertThatThrownBy(() -> start.adopt(SECOND, SettableFuture.<String>create()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer current");
        GroupStep<Tuple2<String, String>> next = advanced.adopt(SECOND, SettableFuture.create());
        assertThat(next).isNotNull();
        assertThatThrownBy(() -> advanced.adopt(TaskKey.<String>named("third"), SettableFuture.<String>create()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer current");

        GroupStart confinedStart = runtime.group("confined-start", TIMEOUT);
        GroupStep<String> confinedStep = runtime.group("confined-step", TIMEOUT).adopt(FIRST, SettableFuture.create());
        AtomicReference<Throwable> fromStart = new AtomicReference<>();
        AtomicReference<Throwable> fromStep = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                GroupStep<?> unused = confinedStart.adopt(FIRST, SettableFuture.<String>create());
            } catch (Throwable failure) {
                fromStart.set(failure);
            }
            try {
                GroupStep<?> unused = confinedStep.adopt(SECOND, SettableFuture.<String>create());
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
    void anAdoptingGroupStopsHoldingTheRuntimeOnceItsSourcesSettle() throws Exception {
        ParRuntime runtime = runtime(pool(1));

        TaskGroupResult<String, Void> result = runtime.group("settled", TIMEOUT)
                .adopt(FIRST, Futures.immediateFuture("first"))
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        runtime.close();
        assertThat(runtime.awaitQuiescence(TIMEOUT)).isTrue();
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

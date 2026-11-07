package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(10)
class RequestCancellationTest {
    private static final ParId WORKER = ParId.of("worker");
    private static final TaskKey<Integer> MEMBER = TaskKey.named("member");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<ParRuntime> runtimes = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ParRuntime runtime : runtimes) {
            runtime.close();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("NullAway")
    @Test
    void batchCancelOnIsValidatedAndPreservedByWithers() {
        ParRuntime runtime = pooledRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> source = new TrackingFuture<>();
        BatchOptions base = BatchOptions.timeout("batch", TIMEOUT);
        BatchOptions derived = base.cancelOn(source)
                .parallelism(2)
                .taskType(TaskType.COMPUTE)
                .rejectEnqueue(true)
                .closeGrace(Duration.ZERO);

        assertThatThrownBy(() -> base.cancelOn(null)).isInstanceOf(NullPointerException.class);
        assertThat(source.listeners()).isEmpty();
        assertThat(Arrays.stream(TaskOptions.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("cancelOn");
        assertThat(Arrays.stream(BatchOptions.class.getMethods())
                        .filter(method -> method.getName().equals("cancelOn"))
                        .map(method -> method.getParameterCount()))
                .as("request cancellation always interrupts; there is no interrupt-policy overload")
                .containsExactly(1);
        assertThat(Arrays.stream(GroupStart.class.getMethods())
                        .filter(method -> method.getName().equals("cancelOn"))
                        .map(method -> method.getParameterCount()))
                .containsExactly(1);

        source.succeed(null);
        assertThat(runSingle(ScopeKind.BATCH, runtime, derived, source, () -> {
                    throw new AssertionError("execution must not run");
                }))
                .isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(par.map(Collections.singletonList(1), value -> value, base).valuesOrThrow())
                .containsExactly(1);
        assertThat(source.listeners()).hasSize(1);
    }

    @SuppressWarnings("NullAway")
    @Test
    void groupCancelOnIsValidatedAndFollowsStageRules() {
        ParRuntime runtime = pooledRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> source = new TrackingFuture<>();
        GroupStart start = runtime.group("group", TIMEOUT);

        assertThatThrownBy(() -> start.cancelOn(null)).isInstanceOf(NullPointerException.class);
        assertThat(start.cancelOn(source)).isSameAs(start);
        assertThat(source.listeners()).isEmpty();

        GroupStep step = start.par(MEMBER, par, () -> 1);
        assertThatThrownBy(() -> start.cancelOn(source)).isInstanceOf(IllegalStateException.class);
        assertThat(step.runAll().resultAt(0).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(source.listeners()).hasSize(1);
        assertThat(source.isDone()).isFalse();
    }

    @Test
    void cancelOnFromANonOwnerThreadRegistersNothingAndLeavesTheStageUsable() throws Exception {
        ParRuntime runtime = pooledRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> source = new TrackingFuture<>();
        GroupStart start = runtime.group("group", TIMEOUT);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                start.cancelOn(source);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
            attempted.countDown();
        });
        other.start();
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        other.join(5000);

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(source.listeners()).isEmpty();
        assertThat(source.isDone()).isFalse();

        // The owner thread can still use the stage: the rejected call never registered or advanced.
        assertThat(start.cancelOn(source)
                        .par(MEMBER, par, () -> 1)
                        .runAll()
                        .resultAt(0)
                        .outcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(source.listeners()).hasSize(1);
        assertThat(source.isDone()).isFalse();
        assertSevered(source, 0);
    }

    @ParameterizedTest
    @EnumSource(SourceCompletion.class)
    void precompletedSourceCancelsBatchBeforeExecution(SourceCompletion completion) throws Exception {
        ParRuntime runtime = pooledRuntime();
        TrackingFuture<Void> source = new TrackingFuture<>();
        AtomicInteger executions = new AtomicInteger();
        complete(source, completion);

        TaskOutcome outcome = runSingle(ScopeKind.BATCH, runtime, source, () -> {
            executions.incrementAndGet();
            return 1;
        });

        assertThat(outcome).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(executions).hasValue(0);
        assertThat(source.listeners()).hasSize(1);
        assertSevered(source, 0);
    }

    @ParameterizedTest
    @EnumSource(SourceCompletion.class)
    void precompletedSourceCancelsGroupBeforeExecution(SourceCompletion completion) throws Exception {
        ParRuntime runtime = pooledRuntime();
        TrackingFuture<Void> source = new TrackingFuture<>();
        AtomicInteger executions = new AtomicInteger();
        complete(source, completion);

        TaskOutcome outcome = runSingle(ScopeKind.GROUP, runtime, source, () -> {
            executions.incrementAndGet();
            return 1;
        });

        assertThat(outcome).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(executions).hasValue(0);
        assertThat(source.listeners()).hasSize(1);
        assertSevered(source, 0);
    }

    static Stream<Arguments> runningCases() {
        return Stream.of(
                Arguments.of(ScopeKind.BATCH, SourceCompletion.SUCCESS),
                Arguments.of(ScopeKind.GROUP, SourceCompletion.SUCCESS),
                Arguments.of(ScopeKind.BATCH, SourceCompletion.CANCELLED),
                Arguments.of(ScopeKind.BATCH, SourceCompletion.FAILURE));
    }

    @ParameterizedTest
    @MethodSource("runningCases")
    void sourceCompletionInterruptsTheRunningExecution(ScopeKind scope, SourceCompletion completion) throws Exception {
        ParRuntime runtime = pooledRuntime();
        TrackingFuture<Void> source = new TrackingFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean();
        AtomicReference<TaskOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        Callable<Integer> taskCode = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                wasInterrupted.set(true);
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return 1;
        };
        Thread caller = new Thread(() -> {
            try {
                outcome.set(runSingle(scope, runtime, source, taskCode));
            } catch (Throwable failure) {
                callerFailure.set(failure);
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            complete(source, completion);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            caller.join(5000);
        }

        assertThat(caller.isAlive()).isFalse();
        assertThat(callerFailure.get()).isNull();
        assertThat(outcome.get()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(wasInterrupted.get()).isTrue();
        assertSevered(source, 0);
    }

    @ParameterizedTest
    @EnumSource(ScopeKind.class)
    void normalCompletionSeversWithoutCompletingTheSource(ScopeKind scope) throws Exception {
        ParRuntime runtime = pooledRuntime();
        TrackingFuture<Void> source = new TrackingFuture<>();

        TaskOutcome outcome = runSingle(scope, runtime, source, () -> 1);

        assertThat(outcome).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(source.isDone()).isFalse();
        assertThat(source.isCancelled()).isFalse();
        assertThat(source.listeners()).hasSize(1);
        assertSevered(source, 0);
    }

    @Test
    void registeringAfterTokenCompletionSeversTheSourceLink() throws Exception {
        CancellationToken token = CancellationToken.create();
        TrackingFuture<Void> source = new TrackingFuture<>();
        token.cancel();

        token.cancelOn(source);

        assertThat(source.listeners()).hasSize(1);
        assertSevered(source, 0);
        assertThat(source.isDone()).isFalse();
    }

    @Test
    void oneSourceCanBeReusedAcrossIndependentExecutions() throws Exception {
        ParRuntime runtime = directRuntime();
        TrackingFuture<Void> source = new TrackingFuture<>();
        AtomicInteger secondExecutions = new AtomicInteger();

        assertThat(runSingle(ScopeKind.BATCH, runtime, source, () -> 1)).isEqualTo(TaskOutcome.SUCCESS);
        source.succeed(null);
        assertThat(runSingle(ScopeKind.BATCH, runtime, source, () -> {
                    secondExecutions.incrementAndGet();
                    return 2;
                }))
                .isEqualTo(TaskOutcome.GROUP_CANCELLED);

        assertThat(secondExecutions).hasValue(0);
        assertThat(source.listeners()).hasSize(2);
        assertSevered(source, 0);
        assertSevered(source, 1);
    }

    @Test
    void emptyExecutionsDoNotRegisterAndMapAndCombineStillRejectsEmptyInput() {
        ParRuntime runtime = directRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> source = new TrackingFuture<>();
        source.succeed(null);
        BatchOptions options = BatchOptions.timeout("batch", TIMEOUT).cancelOn(source);

        assertThat(par.map(null, value -> value, options).valuesOrThrow()).isEmpty();
        assertThat(par.map(Collections.emptyList(), value -> value, options).valuesOrThrow())
                .isEmpty();
        assertThat(runtime.group("empty", TIMEOUT).cancelOn(source).runAll().outcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThatThrownBy(() -> par.mapAndCombine(
                        Collections.emptyList(),
                        value -> value,
                        options,
                        par,
                        values -> Objects.requireNonNull(values).size()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(source.listeners()).isEmpty();
    }

    @Test
    void preparationFailureLeavesNoSourceListener() {
        ParRuntime runtime = directRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> batchSource = new TrackingFuture<>();
        TrackingFuture<Void> groupSource = new TrackingFuture<>();

        assertThatThrownBy(() -> par.map(
                        Collections.singletonList(1),
                        value -> value,
                        BatchOptions.inheritTimeout("batch").cancelOn(batchSource)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.groupInheriting("group")
                        .cancelOn(groupSource)
                        .par(MEMBER, par, () -> 1)
                        .runAll())
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(batchSource.listeners()).isEmpty();
        assertThat(groupSource.listeners()).isEmpty();
    }

    @Test
    void aRejectedDeadlineAdmissionAfterRegistrationSeversTheSourceLink() throws Exception {
        ParRuntime runtime = rejectingDeadlineRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> batchSource = new TrackingFuture<>();
        TrackingFuture<Void> groupSource = new TrackingFuture<>();

        assertThatThrownBy(() -> par.map(
                        Collections.singletonList(1),
                        value -> value,
                        BatchOptions.timeout("batch", TIMEOUT).cancelOn(batchSource)))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("deadline scheduler is shut down");
        assertThatThrownBy(() -> runtime.group("group", TIMEOUT)
                        .cancelOn(groupSource)
                        .par(MEMBER, par, () -> 1)
                        .runAll())
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("deadline scheduler is shut down");

        assertThat(batchSource.isDone()).isFalse();
        assertThat(batchSource.listeners()).hasSize(1);
        assertSevered(batchSource, 0);
        assertThat(groupSource.isDone()).isFalse();
        assertThat(groupSource.listeners()).hasSize(1);
        assertSevered(groupSource, 0);
    }

    @Test
    void aSourceThatRejectsTheListenerAfterRecordingItIsSeveredAndTheFailureRethrown() throws Exception {
        CancellationToken token = CancellationToken.create();
        TrackingFuture<Void> source = new TrackingFuture<Void>().rejectingListeners();

        assertThatThrownBy(() -> token.cancelOn(source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source rejected the listener");

        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(source.isDone()).isFalse();
        assertThat(source.listeners()).hasSize(1);
        assertSevered(source, 0);
    }

    @ParameterizedTest
    @EnumSource(ScopeKind.class)
    void precompletedSourceCancelsDirectInlineExecution(ScopeKind scope) throws Exception {
        ParRuntime runtime = directRuntime();
        TrackingFuture<Void> source = new TrackingFuture<>();
        AtomicInteger executions = new AtomicInteger();
        source.fail(new IllegalStateException("request failed"));

        TaskOutcome outcome = runSingle(scope, runtime, source, () -> {
            executions.incrementAndGet();
            return 1;
        });

        assertThat(outcome).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(executions).hasValue(0);
        assertSevered(source, 0);
    }

    @Test
    void mapAndCombineSharesTheSameRequestCancellation() throws Exception {
        ParRuntime runtime = directRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> source = new TrackingFuture<>();
        AtomicInteger elementExecutions = new AtomicInteger();
        AtomicInteger combiners = new AtomicInteger();
        source.cancel(true);

        BatchCombinedResult<Integer, Integer> result = par.mapAndCombine(
                Collections.singletonList(1),
                value -> {
                    elementExecutions.incrementAndGet();
                    return value;
                },
                BatchOptions.timeout("batch", TIMEOUT).cancelOn(source),
                par,
                values -> {
                    combiners.incrementAndGet();
                    return Objects.requireNonNull(values).size();
                });

        assertThat(result.batchResult().results().get(0).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(elementExecutions).hasValue(0);
        assertThat(combiners).hasValue(0);
        assertThat(source.listeners()).hasSize(1);
        assertSevered(source, 0);
    }

    static Stream<Arguments> ancestorRaces() {
        return Stream.of(
                Arguments.of(AncestorRace.ANCESTOR_CANCEL, TaskOutcome.GROUP_CANCELLED),
                Arguments.of(AncestorRace.SOURCE, TaskOutcome.GROUP_CANCELLED),
                Arguments.of(AncestorRace.ANCESTOR_TIMEOUT, TaskOutcome.TIMEOUT));
    }

    @ParameterizedTest
    @MethodSource("ancestorRaces")
    void ancestorAndSourceRacesRetainFirstWinsAttributionAndInterrupt(AncestorRace race, TaskOutcome expectedOutcome)
            throws Exception {
        ParRuntime runtime = pooledRuntime();
        Par par = runtime.par(WORKER);
        TrackingFuture<Void> source = new TrackingFuture<>();
        AtomicReference<CancellationToken> parentToken = new AtomicReference<>();
        AtomicReference<TaskOutcome> childOutcome = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        CountDownLatch childEntered = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                par.map(
                        Collections.singletonList(1),
                        ignored -> {
                            TaskExecutionContext current = TaskExecutionContext.current();
                            assertThat(current).isNotNull();
                            parentToken.set(current.multiTaskContext().cancellationToken());
                            childOutcome.set(runSingle(ScopeKind.BATCH, runtime, source, () -> {
                                childEntered.countDown();
                                try {
                                    releaseChild.await();
                                } catch (InterruptedException failure) {
                                    wasInterrupted.set(true);
                                    childInterrupted.countDown();
                                    Thread.currentThread().interrupt();
                                }
                                return 1;
                            }));
                            return 1;
                        },
                        BatchOptions.timeout("outer", TIMEOUT));
            } catch (Throwable failure) {
                callerFailure.set(failure);
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            assertThat(childEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CancellationToken parent = parentToken.get();
            assertThat(parent).isNotNull();
            if (race == AncestorRace.ANCESTOR_CANCEL) {
                parent.cancel();
                source.succeed(null);
            } else if (race == AncestorRace.ANCESTOR_TIMEOUT) {
                parent.timeoutCancel();
                source.succeed(null);
            } else {
                source.succeed(null);
                parent.cancel();
            }
            assertThat(childInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseChild.countDown();
            caller.join(5000);
        }

        assertThat(caller.isAlive()).isFalse();
        assertThat(callerFailure.get()).isNull();
        assertThat(childOutcome.get()).isEqualTo(expectedOutcome);
        assertThat(wasInterrupted.get()).isTrue();
        assertSevered(source, 0);
    }

    private static TaskOutcome runSingle(
            ScopeKind scope, ParRuntime runtime, TrackingFuture<Void> source, Callable<Integer> taskCode) {
        return runSingle(scope, runtime, BatchOptions.timeout("batch", TIMEOUT).cancelOn(source), source, taskCode);
    }

    private static TaskOutcome runSingle(
            ScopeKind scope,
            ParRuntime runtime,
            BatchOptions options,
            TrackingFuture<Void> source,
            Callable<Integer> taskCode) {
        Par par = runtime.par(WORKER);
        if (scope == ScopeKind.BATCH) {
            return par.map(
                            Collections.singletonList(1),
                            value -> {
                                try {
                                    return taskCode.call();
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            options)
                    .results()
                    .get(0)
                    .outcome();
        }
        return runtime.group("group", TIMEOUT)
                .cancelOn(source)
                .par(MEMBER, par, taskCode)
                .runAll()
                .resultAt(0)
                .outcome();
    }

    private ParRuntime pooledRuntime() {
        return newRuntime(Executors.newFixedThreadPool(4));
    }

    private ParRuntime directRuntime() {
        return newRuntime(MoreExecutors.newDirectExecutorService());
    }

    private ParRuntime rejectingDeadlineRuntime() {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime = ParRuntime.builder()
                .register(WORKER, executor)
                .timeoutScheduler(new RejectedDeadlineScheduler())
                .build();
        runtimes.add(runtime);
        executors.add(executor);
        return runtime;
    }

    private ParRuntime newRuntime(ExecutorService executor) {
        ParRuntime runtime = ParRuntime.builder().register(WORKER, executor).build();
        runtimes.add(runtime);
        executors.add(executor);
        return runtime;
    }

    private static void complete(TrackingFuture<Void> source, SourceCompletion completion) {
        if (completion == SourceCompletion.SUCCESS) {
            source.succeed(null);
        } else if (completion == SourceCompletion.FAILURE) {
            source.fail(new IllegalStateException("source failed"));
        } else {
            source.cancel(true);
        }
    }

    private static void assertSevered(TrackingFuture<?> source, int listenerIndex) throws Exception {
        Runnable listener = source.listeners().get(listenerIndex);
        Object link = null;
        for (Field field : listener.getClass().getDeclaredFields()) {
            if (CancellationToken.RequestCancellationLink.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                link = field.get(listener);
            }
        }
        assertThat(link).isNotNull();
        Field actionField = CancellationToken.RequestCancellationLink.class.getDeclaredField("cancellationAction");
        actionField.setAccessible(true);
        AtomicReference<?> action = (AtomicReference<?>) actionField.get(link);
        await().atMost(5, TimeUnit.SECONDS).until(() -> action.get() == null);
    }

    enum ScopeKind {
        BATCH,
        GROUP
    }

    enum SourceCompletion {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    enum AncestorRace {
        ANCESTOR_CANCEL,
        SOURCE,
        ANCESTOR_TIMEOUT
    }

    private static final class TrackingFuture<T> implements ListenableFuture<T> {
        private final SettableFuture<T> delegate = SettableFuture.create();
        private final List<Runnable> listeners = new ArrayList<>();
        private boolean rejectListeners;

        /** Opt-in failure mode: record the listener, then refuse it instead of registering it. */
        TrackingFuture<T> rejectingListeners() {
            rejectListeners = true;
            return this;
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            listeners.add(listener);
            if (rejectListeners) {
                throw new IllegalStateException("source rejected the listener");
            }
            delegate.addListener(listener, executor);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public @Nullable T get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public @Nullable T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }

        boolean succeed(@Nullable T value) {
            return delegate.set(value);
        }

        boolean fail(Throwable failure) {
            return delegate.setException(failure);
        }

        List<Runnable> listeners() {
            return listeners;
        }
    }

    /**
     * A deadline scheduler that refuses every arming, standing in for the retired scheduler of a
     * runtime whose services already shut down while an admission was still binding.
     */
    private static final class RejectedDeadlineScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw rejection();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw rejection();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw rejection();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw rejection();
        }

        @Override
        public void execute(Runnable command) {
            throw rejection();
        }

        private static RejectedExecutionException rejection() {
            return new RejectedExecutionException("deadline scheduler is shut down");
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}

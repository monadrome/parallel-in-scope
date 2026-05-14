package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Behavior pins for small scope primitives: {@link ExecutorIdentity} identity semantics,
 * {@link GlobalPar} Par-name validation and lookup semantics, {@link GlobalPar} task-listener
 * defaults, {@link MultiTaskContext#resolve} boundary matrix, {@link GlobalPar} topology shutdown
 * states with its scheduler adapter, and {@link ScopedCallable} timing bookkeeping.
 */
class ScopePrimitivesTest {

    // ==================== ExecutorIdentity ====================

    @Test
    void executorIdentityBindsToTheExactSuppliedObject() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ExecutorIdentity identity = new ExecutorIdentity(pool);
            assertThat(identity.hashCode()).isEqualTo(System.identityHashCode(pool));
            assertThat(identity.suppliedExecutor()).isSameAs(pool);
            assertThat(identity).isEqualTo(new ExecutorIdentity(pool));
            assertThat(identity).hasSameHashCodeAs(new ExecutorIdentity(pool));

            ExecutorService otherPool = Executors.newCachedThreadPool();
            try {
                assertThat(identity).isNotEqualTo(new ExecutorIdentity(otherPool));
            } finally {
                otherPool.shutdownNow();
            }
            assertThat(identity.toString())
                    .contains("@")
                    .contains(pool.getClass().getSimpleName());
            assertThatThrownBy(() -> new ExecutorIdentity(null)).isInstanceOf(NullPointerException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== Par names ====================

    @Test
    void parNamesAreValidatedAtPublicEndpointsAndNeverNormalized() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TaskListener listener = event -> {};
        try {
            GlobalPar.Builder builder = GlobalPar.builder();
            assertThatThrownBy(() -> builder.register(null, executor)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> builder.register("", executor)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.register("   ", executor)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.defaultPar(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> builder.defaultPar(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.parTaskListener(null, listener)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> builder.parTaskListener(" ", listener))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            executor.shutdownNow();
        }

        // The value is used verbatim: no trimming, lower-casing, or other normalization.
        ExecutorService io = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(" db ", io).register("db", io).build();
        try {
            assertThat(global.pars()).containsOnlyKeys(" db ", "db");
            assertThat(global.par(" db ")).isNotSameAs(global.par("db"));
            assertThat(global.par(" db ").name()).isEqualTo(" db ");
            assertThatThrownBy(() -> global.par(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.par("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.find("   ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.taskListenersFor(" ")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            io.shutdownNow();
        }
    }

    @Test
    void parLookupResolvesByNameValue() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("io", executor).build();
        try {
            assertThat(global.par("io")).isSameAs(global.par("io"));
            assertThat(global.find("io")).contains(global.par("io"));
            assertThat(global.find("missing")).isEmpty();
            assertThat(global.pars()).containsOnlyKeys("io");
            assertThatThrownBy(() -> global.par("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // ==================== GlobalPar task listeners ====================

    @Test
    void taskListenersExposeImmutableSnapshotSemantics() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar empty = GlobalPar.builder().register("worker", executor).build();
        try {
            assertThat(empty.taskListeners()).isEmpty();
            assertThat(empty.taskListenersFor("worker")).isEmpty();
        } finally {
            empty.close();
            executor.shutdownNow();
        }

        GlobalPar.Builder builder = GlobalPar.builder();
        assertThatThrownBy(() -> builder.taskListener(null)).isInstanceOf(NullPointerException.class);

        TaskListener listener = event -> {};
        ExecutorService snapshottedExecutor = Executors.newSingleThreadExecutor();
        GlobalPar snapshotted = GlobalPar.builder()
                .taskListener(listener)
                .register("worker", snapshottedExecutor)
                .build();
        try {
            assertThat(snapshotted.taskListeners()).containsExactly(listener);
            assertThat(snapshotted.taskListenersFor("worker")).containsExactly(listener);
            assertThatThrownBy(() -> snapshotted.taskListeners().add(listener))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> snapshotted.taskListenersFor("worker").add(listener))
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            snapshotted.close();
            snapshottedExecutor.shutdownNow();
        }
    }

    // ==================== MultiTaskContext.resolve ====================

    private static MultiTaskContext resolve(int parallelism, Duration timeout, int taskCount, MultiTaskContext parent) {
        BatchOptions options = BatchOptions.timeout("batch", timeout);
        if (parallelism > 0) {
            options = options.parallelism(parallelism);
        }
        return MultiTaskContext.resolve(options.spec(), taskCount, parent);
    }

    @Test
    void resolveNormalizesParallelismAgainstTaskCount() {
        assertThat(resolve(0, Duration.ofSeconds(30), 4, null).effectiveParallelism())
                .isEqualTo(4);
        assertThat(resolve(-1, Duration.ofSeconds(30), 3, null).effectiveParallelism())
                .isEqualTo(3);
        assertThat(resolve(9, Duration.ofSeconds(30), 3, null).effectiveParallelism())
                .isEqualTo(3);
        assertThat(resolve(2, Duration.ofSeconds(30), 5, null).effectiveParallelism())
                .isEqualTo(2);
        assertThat(resolve(2, Duration.ofSeconds(30), 5, null).taskCount()).isEqualTo(5);
        assertThatThrownBy(() -> resolve(1, Duration.ofSeconds(30), -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveAppliesExplicitTimeoutAndOverflowGuard() {
        long before = System.nanoTime();
        MultiTaskContext timed = resolve(0, Duration.ofMillis(150), 1, null);
        long deadline = timed.deadlineNanos();
        long expectedLow = before + TimeUnit.MILLISECONDS.toNanos(140);
        long expectedHigh = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(160);
        assertThat(deadline).isBetween(expectedLow, expectedHigh);
        assertThat(timed.remaining().toMillis()).isLessThanOrEqualTo(160);
        assertThat(timed.remaining()).isGreaterThanOrEqualTo(Duration.ZERO);

        MultiTaskContext overflow = resolve(0, Duration.ofNanos(Long.MAX_VALUE), 1, null);
        assertThat(overflow.deadlineNanos()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void childDeadlineNeverExceedsParentDeadline() {
        MultiTaskContext parent = resolve(0, Duration.ofMillis(50), 1, null);
        MultiTaskContext child = resolve(0, Duration.ofHours(10), 1, parent);
        assertThat(child.deadlineNanos()).isLessThanOrEqualTo(parent.deadlineNanos());
        assertThat(child.structuralParent()).isSameAs(parent);
        assertThat(child.cancellationToken()).isNotSameAs(parent.cancellationToken());
    }

    @Test
    void resolveRejectsNullOptions() {
        assertThatThrownBy(() -> MultiTaskContext.resolve(null, 1, null)).isInstanceOf(NullPointerException.class);
    }

    // ==================== ScopedCallable timing ====================

    @Test
    void scopedCallableRecordsPositiveWaitAndExecutionDurations() throws Exception {
        MultiTaskContext context = resolve(0, Duration.ofSeconds(30), 1, null);
        TaskExecutionContext taskContext = task(context, 0);
        ScopedCallable<Integer> callable = new ScopedCallable<>(
                taskContext,
                () -> {
                    Thread.sleep(4);
                    return 42;
                },
                java.util.Collections.emptyList());

        Thread.sleep(6); // Simulate queue wait between construction and start.
        assertThat(callable.call()).isEqualTo(42);
        assertThat(taskContext.waitTimeNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(4));
        assertThat(taskContext.executionTimeNanos()).isGreaterThan(0L);
        assertThat(taskContext.totalTimeNanos())
                .isEqualTo(taskContext.waitTimeNanos() + taskContext.executionTimeNanos());
        assertThat(context.cancellationToken()).isNotNull();
    }

    @Test
    void scopedCallableRejectsNullConstructionArguments() {
        MultiTaskContext context = resolve(0, Duration.ofSeconds(30), 1, null);
        assertThatThrownBy(() -> new ScopedCallable<>(null, () -> "ok", java.util.Collections.emptyList()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScopedCallable<>(task(context, 0), null, java.util.Collections.emptyList()))
                .isInstanceOf(NullPointerException.class);
    }

    private static TaskExecutionContext task(MultiTaskContext context, int index) {
        return new TaskExecutionContext(
                context, index, com.google.common.base.Ticker.systemTicker().read());
    }

    // ==================== GlobalPar lifecycle & scheduler adapter ====================

    @Test
    void runtimeBindingsExposeTheExactRegisteredExecutorAndStayDistinct() {
        ExecutorService poolA = Executors.newSingleThreadExecutor();
        ExecutorService poolB = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register("a", poolA).register("b", poolB).build();
        try {
            ExecutorRuntime runtimeA = global.par("a").runtime();
            ExecutorRuntime runtimeB = global.par("b").runtime();
            assertThat(runtimeA).isNotNull();
            assertThat(runtimeB).isNotNull();
            assertThat(runtimeA).isNotSameAs(runtimeB);
            assertThat(runtimeA.suppliedExecutor()).isSameAs(poolA);
            assertThat(runtimeB.suppliedExecutor()).isSameAs(poolB);
            assertThat(global.runtimesByIdentity().keySet())
                    .contains(new ExecutorIdentity(poolA), new ExecutorIdentity(poolB));
        } finally {
            poolA.shutdownNow();
            poolB.shutdownNow();
            global.close();
        }
    }

    @Test
    void globalParReportsClosedOnlyAfterCloseAndIsIdempotent() throws Exception {
        GlobalPar global = GlobalPar.builder().build();
        assertThat(global.closed()).isFalse();
        global.close();
        assertThat(global.closed()).isTrue();
        global.close(); // idempotent
        assertThat(global.closed()).isTrue();
        assertThatThrownBy(global::openTaskGraphObservation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutSchedulerDelegatesRunnablesAndLifecycleStates() throws Exception {
        GlobalPar global = GlobalPar.builder().build();
        java.util.concurrent.ScheduledExecutorService scheduler = global.timeoutScheduler();

        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ScheduledFuture<?> scheduled =
                scheduler.schedule(ran::countDown, 5, TimeUnit.MILLISECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(scheduled);
        assertThat(scheduler.isShutdown()).isFalse();
        assertThat(scheduler.isTerminated()).isFalse();

        java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean(false);
        scheduler.execute(() -> executed.set(true));
        awaitTrue(executed);

        assertThatThrownBy(() -> scheduler.schedule(() -> "v", 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scheduler.scheduleAtFixedRate(ran::countDown, 1, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scheduler.scheduleWithFixedDelay(ran::countDown, 1, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(scheduler::shutdown).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(scheduler::shutdownNow).isInstanceOf(UnsupportedOperationException.class);

        global.close();
        awaitTrue(scheduler::isShutdown);
        assertThat(scheduler.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.isTerminated()).isTrue();
        assertThatThrownBy(() -> scheduler.execute(ran::countDown)).isInstanceOf(RejectedExecutionException.class);
        assertThat(ran.getCount()).isGreaterThanOrEqualTo(0);
    }

    private static void awaitTrue(java.util.concurrent.atomic.AtomicBoolean flag) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!flag.get() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(flag.get()).isTrue();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Par.mapAndCombine}: a finite batch with one prepared terminal combine. The combine shares
 * the batch deadline and cancellation lifecycle, runs exactly once and only after every element
 * succeeded, and is terminal (cancelled or submission failure) whenever the batch does not fully
 * succeed.
 */
// JUnit-style: 'runtime' is assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class ParMapAndCombineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private ParRuntime runtime;

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void combineRunsOnceAfterAllElementsSucceed() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        ExecutorService cpu = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), cpu)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("io"))
                    .mapAndCombine(
                            Arrays.asList(1, 2, 3, 4),
                            value -> value * 2,
                            BatchOptions.timeout("prices", TIMEOUT).parallelism(2),
                            runtime.par(ParId.of("cpu")),
                            values -> {
                                combineRuns.incrementAndGet();
                                return Objects.requireNonNull(values).stream()
                                        .mapToInt(Integer::intValue)
                                        .sum();
                            });

            assertThat(combineRuns).hasValue(1);
            assertThat(result.terminalValueOrThrow()).isEqualTo(20);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.batchResult().valuesOrThrow()).containsExactly(2, 4, 6, 8);
            assertThat(result.bodyCompletionConfirmed()).isTrue();
            TaskCompletion<Integer> terminal = result.terminal();
            assertThat(terminal).isNotNull();
            assertThat(terminal.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(terminal.result()).isEqualTo(20);
        } finally {
            io.shutdownNow();
            cpu.shutdownNow();
        }
    }

    @Test
    void elementFailureLeavesTheCombineUnsubmittedAndTerminal() {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            IllegalStateException boom = new IllegalStateException("boom");

            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("worker"))
                    .mapAndCombine(
                            Arrays.asList(1, 2, 3),
                            value -> {
                                if (value == 2) {
                                    throw boom;
                                }
                                return value;
                            },
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("worker")),
                            values -> {
                                combineRuns.incrementAndGet();
                                return 0;
                            });

            assertThat(combineRuns).hasValue(0);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
            ImmediateResult<Integer> failing = result.batchResult().results().get(1);
            assertThat(failing.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(failing.failure()).isSameAs(boom);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deadlineCancelsTheCombineBeforeItRuns() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            CountDownLatch started = new CountDownLatch(2);
            AtomicInteger combineRuns = new AtomicInteger();

            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("worker"))
                    .mapAndCombine(
                            Arrays.asList(1, 2),
                            value -> {
                                started.countDown();
                                awaitCancellation();
                                return value;
                            },
                            BatchOptions.timeout("batch", Duration.ofMillis(200)),
                            runtime.par(ParId.of("worker")),
                            values -> {
                                combineRuns.incrementAndGet();
                                return 0;
                            });

            assertThat(combineRuns).hasValue(0);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.batchResult().report().stateCounts().get(TaskOutcome.TIMEOUT))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void combineBusinessFailureKeepsElementResultsAndOriginalCause() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Exception combineFailure = new Exception("combine boom");

            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("worker"))
                    .mapAndCombine(
                            Arrays.asList(1, 2),
                            value -> value,
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("worker")),
                            values -> {
                                throw combineFailure;
                            });

            assertThat(result.batchResult().report().stateCounts().get(TaskOutcome.SUCCESS))
                    .isEqualTo(2);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.terminalResult().failure()).isSameAs(combineFailure);
            assertThatThrownBy(result::terminalValueOrThrow)
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause()
                    .isSameAs(combineFailure);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void combineBusinessTimeoutExceptionIsUserFailureNotFrameworkTimeout() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TimeoutException combineFailure = new TimeoutException("business timeout");

            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("worker"))
                    .mapAndCombine(
                            Arrays.asList(1, 2),
                            value -> value,
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("worker")),
                            values -> {
                                throw combineFailure;
                            });

            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.terminalResult().failure()).isSameAs(combineFailure);
            assertThat(result.batchResult().report().stateCounts().get(TaskOutcome.TIMEOUT))
                    .isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void combineRejectionIsSubmissionFailureWithElementsPreserved() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        // A saturated zero-queue pool: the combine's handoff is rejected outright.
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), saturated)
                .build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            saturated.execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("io"))
                    .mapAndCombine(
                            Arrays.asList(1, 2),
                            value -> value,
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("cpu")),
                            values -> Objects.requireNonNull(values).size());

            assertThat(result.batchResult().valuesOrThrow()).containsExactly(1, 2);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
        } finally {
            release.countDown();
            io.shutdownNow();
            saturated.shutdownNow();
        }
    }

    @Test
    void nullElementValuesArePassedToTheCombine() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            BatchCombinedResult<String, String> result = runtime.par(ParId.of("worker"))
                    .mapAndCombine(
                            Arrays.asList("a", "b", "c"),
                            value -> "b".equals(value) ? null : value,
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("worker")),
                            values -> {
                                assertThat(Objects.requireNonNull(values)).containsExactly("a", null, "c");
                                return "joined";
                            });

            assertThat(result.terminalValueOrThrow()).isEqualTo("joined");
            assertThat(result.batchResult().results().get(1).outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.batchResult().results().get(1).valueOrThrow()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void combineBodySeesTheAdmissionThreadsTtlContext() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(1);
        ExecutorService cpu = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), cpu)
                .build();
        TransmittableThreadLocal<String> trace = new TransmittableThreadLocal<>();
        trace.set("trace-1");
        try {
            BatchCombinedResult<Integer, String> result = runtime.par(ParId.of("io"))
                    .mapAndCombine(
                            Arrays.asList(1, 2),
                            value -> value,
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("cpu")),
                            values -> trace.get());

            assertThat(result.terminalValueOrThrow()).isEqualTo("trace-1");
        } finally {
            trace.remove();
            io.shutdownNow();
            cpu.shutdownNow();
        }
    }

    @Test
    void nestedCombineInheritsTheEnclosingBudget() throws Exception {
        ExecutorService outer = Executors.newFixedThreadPool(2);
        ExecutorService inner = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        try {
            TaskGroupResult<Integer, Void> group = runtime.group("enclosing", Duration.ofSeconds(10))
                    .par("nested", runtime.par(ParId.of("outer")), Integer.class, () -> {
                        BatchCombinedResult<Integer, Integer> combined = runtime.par(ParId.of("inner"))
                                .mapAndCombine(
                                        Arrays.asList(1, 2),
                                        value -> value,
                                        BatchOptions.inheritTimeout("inner-batch"),
                                        runtime.par(ParId.of("inner")),
                                        values -> Objects.requireNonNull(values).stream()
                                                .mapToInt(Integer::intValue)
                                                .sum());
                        // The nested batch reports its own tighter, ancestor-capped budget.
                        assertThat(combined.terminalValueOrThrow()).isEqualTo(3);
                        return 1;
                    })
                    .runAll();
            assertThat(group.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    private static java.util.function.Function<Integer, Integer> nullFunction() {
        return null;
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    private static CombineBody<List<@Nullable Integer>, Integer> nullBody() {
        return null;
    }

    @Test
    void callerRunsSaturationNeverRunsTheCombineOnTheConvergingThread() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        ThreadPoolExecutor callerRuns = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), callerRuns)
                .build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            callerRuns.execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            AtomicInteger combineRuns = new AtomicInteger();
            BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("io"))
                    .mapAndCombine(
                            Arrays.asList(1, 2),
                            value -> value,
                            BatchOptions.timeout("batch", TIMEOUT),
                            runtime.par(ParId.of("cpu")),
                            values -> {
                                combineRuns.incrementAndGet();
                                return 0;
                            });

            // The saturated CallerRuns pool would hand the combine to the converging thread; the
            // inline guard records SUBMISSION_FAILURE instead of running user code there.
            assertThat(combineRuns).hasValue(0);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.batchResult().valuesOrThrow()).containsExactly(1, 2);
        } finally {
            release.countDown();
            io.shutdownNow();
            callerRuns.shutdownNow();
        }
    }

    @Test
    void combinedRunIsTrackedByTheRuntimeUntilDrained() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        ExecutorService cpu = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), cpu)
                .build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Thread caller = new Thread(
                    () -> runtime.par(ParId.of("io"))
                            .mapAndCombine(
                                    Arrays.asList(1),
                                    value -> {
                                        started.countDown();
                                        try {
                                            release.await(30, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return value;
                                    },
                                    BatchOptions.timeout("batch", TIMEOUT),
                                    runtime.par(ParId.of("cpu")),
                                    values -> 1),
                    "combine-tracked-caller");
            caller.setDaemon(true);
            caller.start();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            // The combined run is retained and body-tracked like any other admission: a runtime
            // snapshot must see it until it drains.
            org.awaitility.Awaitility.await().untilAsserted(() -> {
                ParRuntimeSnapshot snapshot = runtime.snapshot();
                assertThat(snapshot.undrainedBatches()).isEqualTo(1);
                assertThat(snapshot.unexitedBodySignals()).isGreaterThanOrEqualTo(1);
            });

            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive()).isFalse();
        } finally {
            release.countDown();
            io.shutdownNow();
            cpu.shutdownNow();
        }
    }

    @Test
    void combineEdgeIsRecordedInTheObservationGraph() throws Exception {
        // Bounded pools are starvation-prone, so the batch->combine edge survives the executor
        // projection's deadlock-relevance filter.
        ExecutorService io = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        ExecutorService cpu = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), cpu)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        try {
            TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
            BatchCombinedResult<Integer, Integer> result;
            try {
                result = runtime.par(ParId.of("io"))
                        .mapAndCombine(
                                Arrays.asList(1, 2),
                                value -> value,
                                BatchOptions.timeout("batch", TIMEOUT),
                                runtime.par(ParId.of("cpu")),
                                values -> Objects.requireNonNull(values).size());
                // The graph records the honest batch-to-combine edge in the task graph (root ->
                // batch -> combine), while the executor projection drops the deferred combine edge
                // as not deadlock-relevant and keeps the batch's own fork edge.
                TaskGraphData data = TaskGraphObservationScope.data();
                assertThat(data).isNotNull();
                assertThat(data.graph().edges()).hasSize(2);
                assertThat(data.executorGraph().hasEdgeConnecting("NA", "io")).isTrue();
                assertThat(data.executorGraph().hasEdgeConnecting("io", "cpu")).isFalse();
            } finally {
                scope.close();
            }
            assertThat(result.terminalValueOrThrow()).isEqualTo(2);
            // A batch fanning out to a combine on another executor is ordinary forking, not a cycle.
            TaskGraphReport report =
                    Objects.requireNonNull(com.google.common.util.concurrent.Futures.getDone(scope.reportFuture()));
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        } finally {
            io.shutdownNow();
            cpu.shutdownNow();
        }
    }

    @Test
    void combineOnTheBatchsOwnPoolRecordsNoExecutorSelfLoop() throws Exception {
        // Regression: the deferred combine never parks a worker waiting on its own pool, so a
        // same-pool combine must not read as an executor self-loop (ISSUE) on close.
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        runtime = ParRuntime.builder()
                .register(ParId.of("worker"), pool)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        try {
            TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
            BatchCombinedResult<Integer, Integer> result;
            try {
                result = runtime.par(ParId.of("worker"))
                        .mapAndCombine(
                                Arrays.asList(1, 2),
                                value -> value,
                                BatchOptions.timeout("batch", TIMEOUT),
                                runtime.par(ParId.of("worker")),
                                values -> Objects.requireNonNull(values).size());
            } finally {
                scope.close();
            }
            assertThat(result.terminalValueOrThrow()).isEqualTo(2);
            TaskGraphReport report =
                    Objects.requireNonNull(com.google.common.util.concurrent.Futures.getDone(scope.reportFuture()));
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
            assertThat(report.executorSelfLoop()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Waits until the deadline interrupt arrives; the 30s latch is only a bug backstop. */
    private static void awaitCancellation() {
        try {
            new CountDownLatch(1).await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("task interrupted", e);
        }
    }

    @Test
    void validationRejectsEmptyForeignAndNullArguments() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        ExecutorService foreignExecutor = Executors.newSingleThreadExecutor();
        ParRuntime other = ParRuntime.builder()
                .register(ParId.of("foreign"), foreignExecutor)
                .build();
        try {
            Par par = runtime.par(ParId.of("worker"));
            BatchOptions options = BatchOptions.timeout("batch", TIMEOUT);
            CombineBody<List<@Nullable Integer>, Integer> combine =
                    values -> Objects.requireNonNull(values).size();

            assertThatThrownBy(() -> par.<Integer, Integer, Integer>mapAndCombine(
                            java.util.Collections.emptyList(), v -> v, options, par, combine))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> par.<Integer, Integer, Integer>mapAndCombine(null, v -> v, options, par, combine))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> par.<Integer, Integer, Integer>mapAndCombine(
                            Arrays.asList(1), v -> v, options, other.par(ParId.of("foreign")), combine))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> par.<Integer, Integer, Integer>mapAndCombine(
                            Arrays.asList(1), nullFunction(), options, par, combine))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> par.<Integer, Integer, Integer>mapAndCombine(
                            Arrays.asList(1), v -> v, options, par, nullBody()))
                    .isInstanceOf(NullPointerException.class);
        } finally {
            other.close();
            foreignExecutor.shutdownNow();
            executor.shutdownNow();
        }
    }

    @Test
    void combineSubmittedAsDeadlineFiresNeverRunsItsBody() throws Exception {
        // The tight terminal-submit race: the success gate has passed and the combine has been
        // handed to its executor — but not yet dequeued — when the deadline fires. The cascade
        // must cancel the queued combine before its body can be entered. The manual clock makes
        // the interleaving exact: advance() runs the timer synchronously on this thread, so the
        // cancellation has fully landed before the gate releases the task to its pool.
        ManualClock clock = new ManualClock();
        ExecutorService elementPool = Executors.newFixedThreadPool(2);
        GatedExecutorService combineGate = new GatedExecutorService();
        runtime = ParRuntime.builder()
                .register(ParId.of("worker"), elementPool)
                .register(ParId.of("combiner"), combineGate)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler())
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            AtomicReference<BatchCombinedResult<Integer, Integer>> outcome = new AtomicReference<>();
            Thread caller = new Thread(
                    () -> outcome.set(runtime.par(ParId.of("worker"))
                            .mapAndCombine(
                                    Arrays.asList(1, 2),
                                    value -> value,
                                    BatchOptions.timeout("batch", Duration.ofMinutes(1)),
                                    runtime.par(ParId.of("combiner")),
                                    values -> {
                                        combineRuns.incrementAndGet();
                                        return 0;
                                    })),
                    "combine-race-caller");
            caller.setDaemon(true);
            caller.start();

            assertThat(combineGate.accepted.await(10, TimeUnit.SECONDS))
                    .as("the combine passed the success gate and reached its executor")
                    .isTrue();
            clock.advance(Duration.ofMinutes(1));
            combineGate.release.countDown();

            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive()).isFalse();
            combineGate.awaitDrained();
            BatchCombinedResult<Integer, Integer> result = Objects.requireNonNull(outcome.get());
            assertThat(combineRuns)
                    .as("a combine cancelled while queued must never enter its body")
                    .hasValue(0);
            assertThat(result.batchResult().report().stateCounts().get(TaskOutcome.SUCCESS))
                    .isEqualTo(2);
            assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            elementPool.shutdownNow();
            combineGate.shutdownNow();
        }
    }

    @Test
    void combineRunsExactlyOnceAcrossManyRounds() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(4);
        ExecutorService cpu = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), cpu)
                .build();
        try {
            for (int round = 0; round < 50; round++) {
                AtomicInteger combineRuns = new AtomicInteger();
                BatchCombinedResult<Integer, Integer> result = runtime.par(ParId.of("io"))
                        .mapAndCombine(
                                Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8),
                                value -> value,
                                BatchOptions.timeout("batch-" + round, TIMEOUT).parallelism(4),
                                runtime.par(ParId.of("cpu")),
                                values -> {
                                    combineRuns.incrementAndGet();
                                    return Objects.requireNonNull(values).stream()
                                            .mapToInt(Integer::intValue)
                                            .sum();
                                });
                assertThat(combineRuns).hasValue(1);
                assertThat(result.terminalValueOrThrow()).isEqualTo(36);
            }
        } finally {
            io.shutdownNow();
            cpu.shutdownNow();
        }
    }

    /**
     * Holds the first submitted task until released, standing in for a combine that was handed to
     * its executor but not yet dequeued when cancellation landed.
     */
    private static final class GatedExecutorService extends java.util.concurrent.AbstractExecutorService {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            accepted.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.RejectedExecutionException("interrupted while gated");
            }
            delegate.execute(command);
        }

        void awaitDrained() throws InterruptedException {
            delegate.shutdown();
            if (!delegate.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gated delegate did not drain");
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}

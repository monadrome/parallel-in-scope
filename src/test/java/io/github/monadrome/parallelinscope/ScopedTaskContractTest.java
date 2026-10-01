package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-entry contract tests: {@code Par.map} and {@code TaskGroup} prepare and submit
 * every task through the same {@code TaskSubmissions} pipeline, so the single-task semantics —
 * instrumentation, TTL capture, phase hints, and rejection handling — must agree across both
 * entry points.
 */
class ScopedTaskContractTest {

    /** Submission entry point under test. */
    enum Entry {
        BATCH,
        GROUP
    }

    private static Stream<Entry> entries() {
        return Stream.of(Entry.values());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void successRunsOnceWithObservationAndRunningPhase(Entry entry) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ConcurrentLinkedQueue<ExecutionPhase> phases = new ConcurrentLinkedQueue<>();
        ParRuntime global = global(executor);
        try {
            observePhases(global, phases);
            AtomicInteger executions = new AtomicInteger();

            ListenableFuture<Object> future = submitSingle(global, entry, "task", () -> {
                executions.incrementAndGet();
                return "done";
            });

            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(executions).hasValue(1);
            // set(result) precedes the TERMINAL emission inside the worker, so wait for it.
            Awaitility.await()
                    .atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> assertThat(phases).containsExactly(ExecutionPhase.RUNNING, ExecutionPhase.TERMINAL));
            TaskCompletion<Object> event = observation(future);
            assertThat(event.successful()).isTrue();
            assertThat(event.result()).isEqualTo("done");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void userExceptionIsReportedAsUserFailure(Entry entry) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = global(executor);
        try {
            IllegalStateException boom = new IllegalStateException("boom");
            ListenableFuture<Object> future = submitSingle(global, entry, "task", () -> {
                throw boom;
            });

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(boom);
            if (entry == Entry.GROUP) {
                TaskGroupReport result = lastGroupResult(global);
                // A recorded member failure takes precedence over the group token state: even
                // though convergence runs while the group token is still RUNNING, the group
                // adopts the failed member's own outcome.
                assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
                assertThat(result.failedTaskName()).isEqualTo("task");
                assertThat(Objects.requireNonNull(result.members().get("task")).outcome())
                        .isEqualTo(TaskOutcome.USER_FAILURE);
                assertThat(result.members().get("task").failure()).isSameAs(boom);
            }
            TaskCompletion<Object> event = observation(future);
            assertThat(event.successful()).isFalse();
            assertThat(event.failure()).isSameAs(boom);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void ttlSnapshotIsVisibleToTheTask(Entry entry) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = global(executor);
        TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
        try {
            ttl.set("snapshot");
            ListenableFuture<Object> future = submitSingle(global, entry, "task", ttl::get);

            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("snapshot");
        } finally {
            ttl.remove();
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * Inline execution is still reachable, but only by registering an executor that does it. This
     * replaces a test of the {@code runOnCallerThread} option, which asked the library to elect the
     * submitting thread on rejection: that decision now belongs to the executor alone, declared once
     * where it is registered. A direct executor is the clearest way to ask for it, and the observation
     * it produces must be indistinguishable from a pooled one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void aDirectExecutorRunsTheBodyOnTheSubmittingThreadAndObservesItNormally(Entry entry) throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ConcurrentLinkedQueue<ExecutionPhase> phases = new ConcurrentLinkedQueue<>();
        ParRuntime global = global(direct);
        try {
            observePhases(global, phases);
            AtomicInteger executions = new AtomicInteger();
            AtomicReference<String> ranOn = new AtomicReference<>();
            String caller = Thread.currentThread().getName();

            ListenableFuture<Object> future = submitSingle(global, entry, "task", TaskType.CPU_BOUND, () -> {
                executions.incrementAndGet();
                ranOn.set(Thread.currentThread().getName());
                return "inline";
            });

            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("inline");
            assertThat(executions).hasValue(1);
            assertThat(ranOn.get()).isEqualTo(caller);
            assertThat(phases).containsExactly(ExecutionPhase.RUNNING, ExecutionPhase.TERMINAL);
            assertThat(observation(future).successful()).isTrue();
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    /**
     * A rejected task fails without entering user code, for every task type including {@code
     * CPU_BOUND}. Nothing selects otherwise: the library has no option that elects the submitting
     * thread, so what a rejection means is decided entirely by the executor's own handler.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void rejectionNeverRunsUserCodeByDefault(Entry entry) throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ConcurrentLinkedQueue<ExecutionPhase> phases = new ConcurrentLinkedQueue<>();
        ParRuntime global = global(rejecting);
        try {
            observePhases(global, phases);
            AtomicInteger executions = new AtomicInteger();

            ListenableFuture<Object> future = submitSingle(global, entry, "task", () -> {
                executions.incrementAndGet();
                return "never";
            });

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(executions).hasValue(0);
            TaskCompletion<Object> event = observation(future);
            assertThat(event.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(event.failure()).isInstanceOf(SubmissionException.class);
            assertThat(event.startTimeNanos()).isZero();
            assertThat(event.endTimeNanos()).isZero();
            assertThat(phases).doesNotContain(ExecutionPhase.RUNNING);
            if (entry == Entry.GROUP) {
                TaskGroupReport result = lastGroupResult(global);
                assertThat(Objects.requireNonNull(result.members().get("task")).outcome())
                        .isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
                assertThat(result.members().get("task").failure()).isInstanceOf(SubmissionException.class);
            }
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void cancelBeforeRunSkipsUserCodeAndHintsThePhase(Entry entry) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ConcurrentLinkedQueue<ExecutionPhase> phases = new ConcurrentLinkedQueue<>();
        ParRuntime global = global(executor);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queuedRuns = new AtomicInteger();
        try {
            observePhases(global, phases);
            if (entry == Entry.BATCH) {
                ListenableFuture<Object> queued = global.par(ParId.of("worker"))
                        .submitBatch(
                                Arrays.asList("blocker", "queued"),
                                item -> callUnchecked(() -> runUnlessQueued(item, release, queuedRuns)),
                                BatchOptions.timeout("cancel", Duration.ofSeconds(30))
                                        .parallelism(2))
                        .results()
                        .get(1);
                assertThat(queued.cancel(true)).isTrue();
            } else {
                TypeToken<Object> memberType = TypeToken.of(Object.class);
                TaskGroup<Tuple2<Object, Object>, Void> group = global.groupDraft("cancel", Duration.ofSeconds(30))
                        .par(
                                "blocker",
                                global.par(ParId.of("worker")),
                                TaskOptions.timeout(Duration.ofSeconds(30)),
                                memberType,
                                () -> runUnlessQueued("blocker", release, queuedRuns))
                        .par(
                                "queued",
                                global.par(ParId.of("worker")),
                                TaskOptions.timeout(Duration.ofSeconds(30)),
                                memberType,
                                () -> runUnlessQueued("queued", release, queuedRuns))
                        .submitAll();
                assertThat(group.futureOf("queued", memberType).cancel(true)).isTrue();
                TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
                assertThat(Objects.requireNonNull(result.members().get("queued"))
                                .outcome())
                        .isEqualTo(TaskOutcome.MEMBER_CANCELLED);
            }
            assertThat(phases).contains(ExecutionPhase.CANCELLED_BEFORE_RUN);
            assertThat(queuedRuns).hasValue(0);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void nestedSubmissionRecordsTaskGraphEdge(Entry entry) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global = global(executor);
        try {
            try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
                Object value = global.par(ParId.of("worker"))
                        .submitBatch(
                                Collections.singletonList("outer"),
                                item -> {
                                    try {
                                        return submitSingle(global, entry, "inner", () -> "inner-value")
                                                .get(2, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                    } catch (ExecutionException | TimeoutException e) {
                                        throw new IllegalStateException(e);
                                    }
                                },
                                BatchOptions.timeout("outer", Duration.ofSeconds(30)))
                        .results()
                        .get(0)
                        .get(2, TimeUnit.SECONDS);

                assertThat(value).isEqualTo("inner-value");
                // root->outer plus outer->inner; a missing member/batch edge shows up here.
                assertThat(Objects.requireNonNull(TaskGraphObservationScope.data())
                                .graph()
                                .edges())
                        .hasSize(2);
            }
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    private static Object runUnlessQueued(String item, CountDownLatch release, AtomicInteger queuedRuns)
            throws InterruptedException {
        if ("queued".equals(item)) {
            queuedRuns.incrementAndGet();
            return "queued-ran";
        }
        release.await(2, TimeUnit.SECONDS);
        return "blocked";
    }

    /**
     * Submits one task through the batch or the group path. The two entry points declare their own
     * option types — a batch its {@link BatchOptions}, a group member its {@link TaskOptions} — so
     * the shared execution policy is mirrored on both rather than passed as one option object.
     */
    private static ListenableFuture<Object> submitSingle(
            ParRuntime global, Entry entry, String name, Callable<Object> task) {
        return submitSingle(global, entry, name, TaskType.CPU_BOUND, task);
    }

    private static ListenableFuture<Object> submitSingle(
            ParRuntime global, Entry entry, String name, TaskType taskType, Callable<Object> task) {
        if (entry == Entry.BATCH) {
            return global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("item"),
                            item -> callUnchecked(task),
                            BatchOptions.timeout(name, Duration.ofSeconds(30)).taskType(taskType))
                    .results()
                    .get(0);
        }
        TypeToken<Object> memberType = TypeToken.of(Object.class);
        TaskGroup<Object, Void> group = global.groupDraft("contract", Duration.ofSeconds(30))
                .par(
                        name,
                        global.par(ParId.of("worker")),
                        TaskOptions.timeout(Duration.ofSeconds(30)).taskType(taskType),
                        memberType,
                        task)
                .submitAll();
        LAST_GROUP.set(group);
        return group.futureOf(name, memberType);
    }

    private static Object callUnchecked(Callable<Object> task) {
        try {
            return task.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    // Tracks the group built by submitSingle so assertions can inspect the terminal snapshot
    // without changing the submission call sites. Tests are single-threaded per entry case.

    private static final ThreadLocal<TaskGroup<?, ?>> LAST_GROUP = new ThreadLocal<>();

    private static TaskGroupReport lastGroupResult(ParRuntime global) throws Exception {
        TaskGroup<?, ?> group = LAST_GROUP.get();
        if (group == null) {
            throw new IllegalStateException("no group was built");
        }
        LAST_GROUP.remove();
        return group.completionFuture().get(2, TimeUnit.SECONDS);
    }

    private static TaskCompletion<Object> observation(ListenableFuture<Object> future) throws Exception {
        return ((TaskFuture<Object>) future).completionFuture().get(2, TimeUnit.SECONDS);
    }

    private static ParRuntime global(ExecutorService executor) {
        return ParRuntime.builder().register(ParId.of("worker"), executor).build();
    }

    private static void observePhases(ParRuntime global, ConcurrentLinkedQueue<ExecutionPhase> phases) {
        // The test executors are never raw ThreadPoolExecutor instances, so no purge observer is
        // installed and the phase observer slot is free to claim.
        global.par(ParId.of("worker")).executorRuntime().setPhaseObserver(phases::add);
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }
}

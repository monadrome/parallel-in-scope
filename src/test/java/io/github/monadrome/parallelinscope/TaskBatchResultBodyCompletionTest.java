package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for {@link TaskBatchResult#awaitBodyCompletion(Duration)} and the
 * body-completion bookkeeping of the sliding-window paths: every prepared task must release its
 * slot exactly once, whether it ran, was cancelled, rejected, or abandoned.
 */
class TaskBatchResultBodyCompletionTest {

    private static BatchOptions options(String name) {
        return BatchOptions.timeout(name, Duration.ofSeconds(30));
    }

    /** An executor that accepts runnables into a queue without ever running them. */
    private static final class QueuingExecutor extends AbstractExecutorService {
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
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
            return true;
        }

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }
    }

    /** An executor that rejects every submission. */
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
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }

    /** An executor that accepts the first submission on a real worker and rejects the rest. */
    private static final class RejectAfterFirstExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final AtomicInteger submissions = new AtomicInteger();
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            if (submissions.getAndIncrement() == 0) {
                delegate.execute(command);
            } else {
                throw new RejectedExecutionException("full");
            }
        }
    }

    /** Sleeps interruptibly without a checked exception, for {@code Par.map} functions. */
    private static void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void queuedTasksNeverStartAndLateRunAfterCancellationStaysOutOfTheBody() throws Exception {
        QueuingExecutor queuing = new QueuingExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), queuing).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> executions.incrementAndGet(),
                            options("queued").taskType(TaskType.IO_BOUND));

            // Every task is parked in the executor queue: the wait must not succeed early.
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isFalse();

            // Cancellation wins before any run(): the queued runnables must never enter the body.
            batch.results().get(0).cancel(true);
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();

            Runnable queued;
            while ((queued = queuing.queue.poll()) != null) {
                queued.run();
            }
            assertThat(executions).hasValue(0);
        } finally {
            global.close();
            queuing.shutdownNow();
        }
    }

    @Test
    void cancelAfterEligibilityClaimButBeforeBodyEntryReleasesSlotViaFallback() throws Exception {
        // Kernel-level: pin the window between the phase claim and the user body with a blocking
        // phase observer, then cancel. The body is skipped and the outer finally releases the slot.
        MultiTaskContext unit = MultiTaskContext.resolve(options("claimed").spec(), 1, null);
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState bodyState = tracker.register(unit);
        TaskExecutionContext context = new TaskExecutionContext(unit, 0, System.nanoTime(), bodyState);
        CountDownLatch observerEntered = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        ExecutionPhaseHintFuture<Integer> future =
                TaskSubmissions.prepare(context, executions::incrementAndGet, null, phase -> {
                    if (phase == ExecutionPhase.RUNNING) {
                        observerEntered.countDown();
                        try {
                            releaseObserver.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });

        Thread worker = new Thread(future);
        worker.start();
        assertThat(observerEntered.await(2, TimeUnit.SECONDS)).isTrue();
        future.cancel(true);
        releaseObserver.countDown();
        worker.join(2000);

        assertThat(future.isCancelled()).isTrue();
        assertThat(executions).hasValue(0);
        assertThat(tracker.awaitBodyCompletion(Duration.ZERO)).isTrue();
    }

    @Test
    void placeholderCancellationReleasesTheWindowExternalSlotExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> {
                                executions.incrementAndGet();
                                entered.countDown();
                                sleepInterruptibly(10_000);
                                return value;
                            },
                            options("placeholder-cancel").parallelism(1).taskType(TaskType.IO_BOUND));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Cancelling the placeholder of the window-external element cascades fail-fast: the
            // running first body exits via interruption, the second body never starts.
            batch.results().get(1).cancel(true);
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(executions).hasValue(1);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void cancellingTheSubmitterAbandonsWindowExternalSlots() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2, 3),
                            value -> {
                                executions.incrementAndGet();
                                entered.countDown();
                                sleepInterruptibly(10_000);
                                return value;
                            },
                            options("abandon").parallelism(1).taskType(TaskType.IO_BOUND));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            batch.submitCanceller().cancel(true);
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(executions).hasValue(1);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void initialWindowRejectionReleasesEverySlot() throws Exception {
        RejectingExecutor rejecting = new RejectingExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), rejecting).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2, 3),
                            value -> executions.incrementAndGet(),
                            options("rejected").taskType(TaskType.IO_BOUND));

            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
            assertThat(executions).hasValue(0);
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    @Test
    void midWindowRejectionReleasesTheRemainingSlots() throws Exception {
        RejectAfterFirstExecutor rejecting = new RejectAfterFirstExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), rejecting).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> executions.incrementAndGet(),
                            options("mid-reject").parallelism(1).taskType(TaskType.IO_BOUND));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(executions).hasValue(1);
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    @Test
    void cpuBoundInlineFallbackDoesNotLeakSlots() throws Exception {
        RejectingExecutor rejecting = new RejectingExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), rejecting).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            // CPU-bound elements rejected by the executor run inline; the window-external one runs
            // inline on the submitter thread. All slots must still be released exactly once.
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> executions.incrementAndGet(),
                            options("inline").parallelism(1).taskType(TaskType.CPU_BOUND));

            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
            assertThat(executions).hasValue(2);
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    @Test
    void normalAndExceptionalBodiesDoNotLeakSlots() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> {
                                if (value == 2) {
                                    throw new IllegalStateException("boom");
                                }
                                return value;
                            },
                            options("mixed"));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void emptyBatchReportsImmediateBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        try {
            TaskBatchResult<Integer> batch =
                    global.par(ParName.of("worker")).<Integer, Integer>map(null, value -> value, options("empty"));
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitFromWithinBatchElementBodyIsRejectedAsSelfAwait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        AtomicReference<TaskBatchResult<String>> batchRef = new AtomicReference<>();
        CountDownLatch batchReady = new CountDownLatch(1);
        try {
            TaskBatchResult<String> batch = global.par(ParName.of("worker"))
                    .map(
                            Collections.singletonList("x"),
                            value -> {
                                try {
                                    batchReady.await(5, TimeUnit.SECONDS);
                                    batchRef.get().awaitBodyCompletion(Duration.ofMillis(10));
                                } catch (IllegalStateException guarded) {
                                    return "guarded";
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    return "interrupted";
                                }
                                return "unguarded";
                            },
                            options("self-await"));
            batchRef.set(batch);
            batchReady.countDown();

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo("guarded");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitValidatesArgumentsBeforeCheckingCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(Collections.singletonList(1), value -> value, options("validation"));

            assertThatThrownBy(() -> batch.awaitBodyCompletion(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> batch.awaitBodyCompletion(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulAwaitEstablishesVisibilityOfBodyWrites() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        int[] writes = new int[2];
        try {
            TaskBatchResult<Integer> batch = global.par(ParName.of("worker"))
                    .map(
                            Arrays.asList(0, 1),
                            index -> {
                                writes[index] = index + 1;
                                return index;
                            },
                            options("visibility"));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(writes).containsExactly(1, 2);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}

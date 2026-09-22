package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
 * Acceptance tests for {@link TaskBatch#awaitBodyCompletion(Duration)} and the
 * body-completion bookkeeping of the sliding-window paths: every prepared task must release its
 * slot exactly once, whether it ran, was cancelled, rejected, or abandoned.
 */
class TaskBatchBodyCompletionTest {

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

    /** Awaits the latch, ignoring interrupts, so cancellation cannot force the body out early. */
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void queuedTasksNeverStartAndLateRunAfterCancellationStaysOutOfTheBody() throws Exception {
        QueuingExecutor queuing = new QueuingExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), queuing).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
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

    /**
     * {@code awaitBodyCompletion} returning {@code true} promises that {@code completionFuture()}
     * already carries the final snapshots — the third wait inside it exists only for the window
     * where every element future is settled but the observation listener has not published yet.
     *
     * <p>No test used to reach that window: every {@code false} assertion in this class is answered
     * by the body-exit phase or the element-future phase, so deleting the observation wait entirely
     * changed no result. This pins it by handing the batch an element whose observation settles on a
     * future of its own, which is exactly the state the window describes.
     */
    @Test
    void theWaitIsNotDoneUntilTheBatchObservationItselfHasPublished() throws Exception {
        SettableFuture<String> element = SettableFuture.create();
        SettableFuture<String> observationSignal = SettableFuture.create();
        CancellationToken token = new CancellationToken();
        TaskObservation<String> observation = TaskObservation.of(
                () -> TaskCompletion.snapshot("element", "batch", 0, 0, 0, 0, TaskOutcome.SUCCESS, "done", null),
                observationSignal);
        TaskBatch<String> batch =
                TaskBatch.of(Collections.singletonList(Task.of("element", token, element, observation)));

        // The element itself is settled, so the body-exit and element-future phases both pass.
        element.set("done");
        assertThat(batch.results().get(0).isDone()).isTrue();

        // Only the observation is outstanding, and that alone must keep the answer false.
        assertThat(batch.awaitBodyCompletion(Duration.ofMillis(50))).isFalse();
        assertThat(batch.completionFuture().isDone()).isFalse();

        observationSignal.set("published");

        assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        // The promise the true answer carries: the snapshots are readable now, not merely imminent.
        assertThat(batch.completionFuture().isDone()).isTrue();
        assertThat(batch.completionFuture().get(2, TimeUnit.SECONDS))
                .extracting(TaskCompletion::taskName)
                .containsExactly("element");
    }

    /**
     * The tracker's own body-exit wait, in isolation: an outstanding body means {@code false}, and
     * the public batch method's later phases must not be what produces that answer. Pinning it here
     * keeps the tracker honest even when a caller's later phase would have masked it.
     */
    @Test
    void theTrackerReportsAnOutstandingBodyAsFalseOnItsOwn() throws Exception {
        MultiTaskContext unit = MultiTaskContext.resolve(
                MultiTaskContext.resolution(options("outstanding").spec(), 1));
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState body = tracker.register(unit);

        // Zero budget takes the single-check path; a real budget takes the timed wait. Both have to
        // answer false while the body slot is still held.
        assertThat(tracker.awaitBodyCompletion(Duration.ZERO)).isFalse();
        assertThat(tracker.awaitBodyCompletion(Duration.ofMillis(50))).isFalse();

        // The real release path: a body claims eligibility, runs, then publishes its exit.
        assertThat(body.claimRunning()).isTrue();
        body.exited();

        assertThat(tracker.awaitBodyCompletion(Duration.ZERO)).isTrue();
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void cancelAfterEligibilityClaimButBeforeBodyEntryReleasesSlotViaFallback() throws Exception {
        // Pause body eligibility after run() claimed execution, without a production callback.
        MultiTaskContext unit = MultiTaskContext.resolve(
                MultiTaskContext.resolution(options("claimed").spec(), 1));
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState bodyState = spy(tracker.register(unit));
        TaskExecutionContext context = new TaskExecutionContext(unit, 0, System.nanoTime(), bodyState);
        CountDownLatch claimEntered = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        doAnswer(invocation -> {
                    claimEntered.countDown();
                    Uninterruptibles.awaitUninterruptibly(releaseClaim);
                    return invocation.callRealMethod();
                })
                .when(bodyState)
                .claimRunning();
        ExecutionPhaseHintFuture<Integer> future = TaskSubmissions.prepare(context, executions::incrementAndGet);

        Thread worker = new Thread(future);
        worker.setDaemon(true);
        try {
            worker.start();
            assertThat(claimEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.RUNNING);
            assertThat(future.cancel(true)).isTrue();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
        } finally {
            releaseClaim.countDown();
            worker.join(2000);
        }

        assertThat(worker.isAlive()).isFalse();
        assertThat(future.isCancelled()).isTrue();
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
        assertThat(executions).hasValue(0);
        assertThat(tracker.awaitBodyCompletion(Duration.ZERO)).isTrue();
    }

    @Test
    void windowExternalElementCancellationReleasesTheSlotExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            value -> {
                                executions.incrementAndGet();
                                entered.countDown();
                                sleepInterruptibly(10_000);
                                return value;
                            },
                            options("window-external-cancel").parallelism(1).taskType(TaskType.IO_BOUND));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Cancelling the window-external element's own view cascades fail-fast: the running
            // first body exits via interruption, the second body never starts.
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
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
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
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), rejecting).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
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
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), rejecting).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
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
    void directExecutionDoesNotLeakSlots() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), direct).build();
        AtomicInteger executions = new AtomicInteger();
        try {
            // Every element runs inline on the caller thread: the initial window hands off
            // synchronously, and each element's completion drives the next handoff from inside
            // that completing call. All slots must still be released exactly once.
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            value -> executions.incrementAndGet(),
                            options("inline").parallelism(1).taskType(TaskType.CPU_BOUND));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(executions).hasValue(2);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void normalAndExceptionalBodiesDoNotLeakSlots() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        // Element 2 must throw only after element 1's body has run: the fail-fast cancellation
        // it triggers cancels the sibling future, and an unordered throw wins that race on a
        // slow runner, cancelling element 1 instead of observing its SUCCESS.
        CountDownLatch firstBodyRan = new CountDownLatch(1);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            value -> {
                                if (value == 2) {
                                    if (!Uninterruptibles.awaitUninterruptibly(firstBodyRan, 10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("element 1 never ran");
                                    }
                                    throw new IllegalStateException("boom");
                                }
                                firstBodyRan.countDown();
                                return value;
                            },
                            options("mixed"));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            // Body exit is published before the future goes terminal (listeners run in between),
            // so read outcomes only after the futures themselves complete.
            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> batch.results().get(1).get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeCancelsElementsAndWaitsForBodyExitWithinGrace() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList(1),
                            value -> {
                                entered.countDown();
                                awaitIgnoringInterrupt(release);
                                return value;
                            },
                            options("batch-close"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread closing = new Thread(() -> {
                batch.close();
                closeReturned.countDown();
            });
            closing.start();

            // Cancellation lands through the batch token while the body stays parked: close keeps
            // waiting for the body instead of returning with the cancelled future.
            long pollDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!batch.results().get(0).isDone() && System.nanoTime() < pollDeadline) {
                Thread.sleep(5);
            }
            assertThat(batch.results().get(0).isCancelled()).isTrue();
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(closeReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
            closing.join(2000);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void batchCloseWithDefaultGraceDerivesTheBudgetFromTheRemainingDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // No closeGrace configured: the wait budget is the batch's remaining deadline at close
            // time, so a close after the deadline lapsed returns right after cancelling.
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList(1),
                            value -> {
                                entered.countDown();
                                awaitIgnoringInterrupt(release);
                                return value;
                            },
                            BatchOptions.timeout("batch-derived", Duration.ofMillis(300)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread.sleep(400);
            long closeStart = System.nanoTime();
            batch.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWithAstronomicGraceWaitsForTheBodyToExit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            // Duration.ofSeconds(Long.MAX_VALUE) overflows toNanos(); close() must saturate the
            // grace and keep waiting for the body to exit instead of failing or skipping the wait.
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList(1),
                            value -> {
                                entered.countDown();
                                awaitIgnoringInterrupt(release);
                                return value;
                            },
                            options("astronomic-grace").closeGrace(Duration.ofSeconds(Long.MAX_VALUE)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread closing = new Thread(() -> {
                batch.close();
                closeReturned.countDown();
            });
            closing.start();

            // The saturated budget means close keeps waiting while the body is parked.
            assertThat(closeReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
            closing.join(2000);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void batchCloseWithZeroGraceIsCancelOnly() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList(1),
                            value -> {
                                entered.countDown();
                                awaitIgnoringInterrupt(release);
                                return value;
                            },
                            options("batch-zero-grace").closeGrace(Duration.ZERO));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long closeStart = System.nanoTime();
            batch.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(batch.results().get(0).isDone()).isTrue();
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void batchCloseFromWithinElementBodyIsRejectedAsSelfAwait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        AtomicReference<TaskBatch<String>> batchRef = new AtomicReference<>();
        CountDownLatch batchReady = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            value -> {
                                try {
                                    batchReady.await(5, TimeUnit.SECONDS);
                                    Objects.requireNonNull(batchRef.get()).close();
                                } catch (IllegalStateException guarded) {
                                    return "guarded";
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    return "interrupted";
                                }
                                return "unguarded";
                            },
                            options("batch-self-await"));
            batchRef.set(batch);
            batchReady.countDown();

            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo("guarded");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void emptyBatchReportsImmediateBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .<Integer, Integer>submitBatch(null, value -> value, options("empty"));
            assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitFromWithinBatchElementBodyIsRejectedAsSelfAwait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        AtomicReference<TaskBatch<String>> batchRef = new AtomicReference<>();
        CountDownLatch batchReady = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            value -> {
                                try {
                                    batchReady.await(5, TimeUnit.SECONDS);
                                    Objects.requireNonNull(batchRef.get()).awaitBodyCompletion(Duration.ofMillis(10));
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

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void awaitValidatesArgumentsBeforeCheckingCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(Collections.singletonList(1), value -> value, options("validation"));

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
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        int[] writes = new int[2];
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
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

    @Test
    void awaitBodyCompletionWaitsForFutureSettlementBeyondBodyExit() throws Exception {
        // A body publishes its exit before its future settles; a batch whose element future is
        // still pending after body exit must not report completion yet — the window where
        // report() used to read RUNNING after a successful wait.
        MultiTaskContext unit = MultiTaskContext.resolve(
                MultiTaskContext.resolution(options("settlement-window").spec(), 1));
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState slot = tracker.register(unit);
        SettableFuture<Integer> settle = SettableFuture.create();
        Task<Integer> element = Task.of("settlement-window", unit.cancellationToken(), settle);
        TaskBatch<Integer> batch = TaskBatch.of(
                tracker,
                Futures.immediateVoidFuture(),
                Collections.singletonList(element),
                unit.cancellationToken(),
                null);

        slot.claimRunning();
        slot.exited();

        // The body has exited but the element future is still short of settlement: the
        // conjunction must not report completion yet.
        assertThat(batch.awaitBodyCompletion(Duration.ZERO)).isFalse();

        settle.set(1);
        assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        assertThat(batch.report().stateCounts()).containsExactly(entry(TaskOutcome.SUCCESS, 1));
        assertThat(batch.completionFuture().get(2, TimeUnit.SECONDS))
                .allSatisfy(completion -> assertThat(completion.successful()).isTrue());
    }

    @Test
    void reportIsTerminalAfterSuccessfulAwaitInFailFastBatches() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            // The issue #45 probe: a failing element makes fail-fast cancel its sibling; the
            // failing element's own future settles a moment after its body exit is published.
            for (int i = 0; i < 50; i++) {
                TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                        .submitBatch(
                                Arrays.asList(1, 2),
                                value -> {
                                    if (value == 1) {
                                        throw new IllegalStateException("boom");
                                    }
                                    sleepInterruptibly(10_000);
                                    return value;
                                },
                                options("fail-fast-report").parallelism(1).taskType(TaskType.IO_BOUND));
                assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(5))).isTrue();
                assertThat(batch.report().stateCounts()).doesNotContainKey(TaskOutcome.RUNNING);
                batch.close();
            }
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}

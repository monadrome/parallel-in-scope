package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SlidingWindowSubmitterTest {

    /**
     * Submits through the production two-step shape: the caller builds the element views first, so
     * it can bind them before submission starts, then hands both lists to the submitter. These
     * tests exercise the submitter directly and have nothing to bind, so they pair the two calls
     * here.
     */
    private static <V> TaskBatch<V> submitAllWithViews(
            SlidingWindowSubmitter<V> submitter, List<? extends ExecutionPhaseHintFuture<V>> tasks) {
        return submitter.submitAll(tasks, submitter.viewsFor(tasks));
    }

    @Test
    void installsSubmissionScopeForInitialAndSlidingWindowSubmissions() throws Exception {
        ConcurrentLinkedQueue<MultiTaskContext> submittedBatches = new ConcurrentLinkedQueue<>();
        ThreadPoolExecutor worker =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
                    @Override
                    public void execute(Runnable command) {
                        submittedBatches.add(SubmissionScope.current());
                        super.execute(command);
                    }
                };
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(worker);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            MultiTaskContext batch = context(2, 1, TaskType.IO_BOUND);
            SlidingWindowSubmitter<Integer> executor = new SlidingWindowSubmitter<>(workers, batch, submitter);

            assertThat(submitAllWithViews(executor, futures(() -> 1, () -> 2)).results())
                    .extracting(future -> future.get(1, TimeUnit.SECONDS))
                    .containsExactly(1, 2);
            await().atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(submittedBatches).hasSize(2));
            assertThat(submittedBatches).containsOnly(batch);
            assertThat(SubmissionScope.current()).isNull();
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void emptyBatchCompletesWithoutSubmitting() {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(0, 1, TaskType.IO_BOUND), submitter);
            assertThat(submitAllWithViews(executor, Collections.emptyList()).results())
                    .isEmpty();
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void submitsEveryTaskWhenWindowIsLarge() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 3, TaskType.IO_BOUND), submitter);
            assertThat(submitAllWithViews(executor, futures(() -> 1, () -> 2, () -> 3))
                            .results())
                    .extracting(f -> f.get(1, TimeUnit.SECONDS))
                    .containsExactly(1, 2, 3);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void cancellingSubmitterAbandonsRemainingPlaceholders() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        CountDownLatch release = new CountDownLatch(1);
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 1, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(
                    executor,
                    futures(
                            () -> {
                                release.await(2, TimeUnit.SECONDS);
                                return 1;
                            },
                            () -> 2,
                            () -> 3));
            assertThat(batch.submitCanceller().cancel(true)).isTrue();
            release.countDown();
            for (ListenableFuture<Integer> result : batch.results()) {
                try {
                    result.get(2, TimeUnit.SECONDS);
                } catch (ExecutionException | CancellationException ignored) {
                    // Abandoned placeholders may fail or cancel, but must not remain pending.
                }
                assertThat(result.isDone()).isTrue();
            }
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void ioBatchReportsEveryInitialSubmissionRejection() {
        ListeningExecutorService rejected = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        rejected.shutdownNow();
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(rejected, context(3, 2, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2, () -> 3));
            assertThat(batch.results()).hasSize(3);
            for (ListenableFuture<Integer> result : batch.results()) {
                assertThatThrownBy(result::get).isInstanceOf(ExecutionException.class);
            }
        } finally {
            submitter.shutdownNow();
        }
    }

    @Test
    void cancelledPlaceholderStopsSlidingWindowAndCancelsLaterPlaceholders() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        CountDownLatch release = new CountDownLatch(1);
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 1, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(
                    executor,
                    futures(
                            () -> {
                                release.await(2, TimeUnit.SECONDS);
                                return 1;
                            },
                            () -> 2,
                            () -> 3));
            assertThat(batch.results().get(1).cancel(true)).isTrue();
            release.countDown();
            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(1);
            await().atMost(2, TimeUnit.SECONDS).until(batch.results().get(2)::isDone);
            assertThat(batch.results().get(2).isCancelled()).isTrue();
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void honorsBatchParallelism() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            MultiTaskContext context = context(3, 1, TaskType.IO_BOUND);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            SlidingWindowSubmitter<Integer> executor = new SlidingWindowSubmitter<>(workers, context, submitter);

            assertThat(submitAllWithViews(
                                    executor,
                                    futures(
                                            () -> runTracked(active, maximum, release, 1),
                                            () -> runTracked(active, maximum, release, 2),
                                            () -> runTracked(active, maximum, release, 3)))
                            .results())
                    .hasSize(3);
            assertThat(awaitMaximum(maximum, 1)).isTrue();
            assertThat(maximum.get()).isEqualTo(1);
            release.countDown();
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void batchElementRunsOnTheSubmittingThreadWhenTheExecutorIsDirect() throws Exception {
        ListeningExecutorService direct = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(direct, context(1, 1, TaskType.CPU_BOUND), submitter);
            assertThat(submitAllWithViews(executor, futures(() -> 7))
                            .results()
                            .get(0)
                            .get())
                    .isEqualTo(7);
        } finally {
            submitter.shutdownNow();
            direct.shutdownNow();
        }
    }

    @Test
    void directExecutionPublishesCompletionForSlidingWindow() throws Exception {
        ListeningExecutorService direct = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(direct, context(2, 1, TaskType.CPU_BOUND), submitter);

            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2));

            assertThat(batch.results())
                    .extracting(future -> future.get(1, TimeUnit.SECONDS))
                    .containsExactly(1, 2);
            assertThat(batch.submitCanceller().get(1, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            submitter.shutdownNow();
            direct.shutdownNow();
        }
    }

    @Test
    void failedDirectExecutionStillAdvancesSlidingWindow() throws Exception {
        ListeningExecutorService direct = MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(direct, context(2, 1, TaskType.CPU_BOUND), submitter);

            TaskBatch<Integer> batch = submitAllWithViews(
                    executor,
                    futures(
                            () -> {
                                throw new IllegalStateException("expected failure");
                            },
                            () -> 2));

            assertThatThrownBy(() -> batch.results().get(0).get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(batch.results().get(1).get(1, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(batch.submitCanceller().get(1, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            submitter.shutdownNow();
            direct.shutdownNow();
        }
    }

    /**
     * The default for every task type: a rejected element fails and its body never runs.
     */
    @Test
    void rejectedCpuElementFailsWithoutRunningItsBodyByDefault() throws Exception {
        ListeningExecutorService rejected = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        rejected.shutdownNow();
        AtomicBoolean bodyRan = new AtomicBoolean();
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(rejected, context(1, 1, TaskType.CPU_BOUND), submitter);

            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> {
                bodyRan.set(true);
                return 7;
            }));

            assertThatThrownBy(() -> batch.results().get(0).get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class);
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(bodyRan).isFalse();
        } finally {
            submitter.shutdownNow();
        }
    }

    @Test
    void slidingWindowRejectionReportsSubmissionFailureAndKeepsTheOriginalCause() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        ExecutorService firstThenReject = new AbstractExecutorService() {
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
                if (submissions.getAndIncrement() == 0) command.run();
                else throw new RejectedExecutionException("full");
            }
        };
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(firstThenReject);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(2, 1, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2));

            assertThat(batch.results().get(0).get(1, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> batch.results().get(1).get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class)
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThatThrownBy(() -> batch.submitCanceller().get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RejectedExecutionException.class);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * L7 alignment: a handoff that throws an {@code Error} — a broken executor, or one failing
     * while enqueuing — must fail the batch like a rejection. Catching only {@code RuntimeException}
     * let the error escape {@code submitAll}, orphaning the in-flight window and leaving every
     * not-yet-submitted element pending forever.
     */
    @Test
    void initialWindowHandoffErrorFailsEveryElementAsSubmissionFailure() {
        ExecutorService brokenAtHandoff = new AbstractExecutorService() {
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
                throw new AssertionError("handoff broken");
            }
        };
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(brokenAtHandoff);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 2, TaskType.IO_BOUND), submitter);

            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2, () -> 3));

            assertThat(batch.results()).hasSize(3);
            for (int i = 0; i < 3; i++) {
                int index = i;
                assertThat(batch.results().get(index).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
                assertThatThrownBy(() -> batch.results().get(index).get(1, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(SubmissionException.class)
                        .hasRootCauseInstanceOf(AssertionError.class);
            }
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /** Same contract past the initial window: the async submitter fails the remaining placeholders. */
    @Test
    void slidingWindowHandoffErrorFailsThePlaceholderAsSubmissionFailure() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        ExecutorService firstThenError = new AbstractExecutorService() {
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
                if (submissions.getAndIncrement() == 0) command.run();
                else throw new AssertionError("handoff broken");
            }
        };
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(firstThenError);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(2, 1, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2));

            assertThat(batch.results().get(0).get(1, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> batch.results().get(1).get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class)
                    .hasRootCauseInstanceOf(AssertionError.class);
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThatThrownBy(() -> batch.submitCanceller().get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AssertionError.class);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * The whole-batch escalation path observes an initial-window handoff {@code Error} like any
     * element failure: {@code valuesOrThrow()} propagates it as an {@code ExecutionException}, and
     * {@code report()} keeps the {@code SubmissionException} with the original {@code Error} as
     * its cause.
     */
    @Test
    void valuesOrThrowAndReportEscalateAnInitialWindowHandoffError() {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(handoffExecutor(command -> {
            throw new AssertionError("handoff broken");
        }));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 2, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2, () -> 3));

            assertThatThrownBy(batch::valuesOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class)
                    .hasRootCauseInstanceOf(AssertionError.class);
            TaskBatch.BatchReport report = batch.report();
            assertThat(report.stateCounts()).containsEntry(TaskOutcome.SUBMISSION_FAILURE, 3);
            assertThat(report.firstException())
                    .isInstanceOf(SubmissionException.class)
                    .hasCauseInstanceOf(AssertionError.class);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * The same escalation path observes a sliding-window handoff {@code Error} with no shape
     * difference, and the batch stays closeable afterward: body-completion tracking settles and
     * the abandoned prepared future releases its user callable.
     */
    @Test
    void valuesOrThrowEscalatesASlidingWindowHandoffError() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(handoffExecutor(command -> {
            if (submissions.getAndIncrement() == 0) command.run();
            else throw new AssertionError("handoff broken");
        }));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(2, 1, TaskType.IO_BOUND), submitter);
            List<ExecutionPhaseHintFuture<Integer>> tasks = futures(() -> 1, () -> 2);
            TaskBatch<Integer> batch = submitAllWithViews(executor, tasks);

            assertThatThrownBy(batch::valuesOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class)
                    .hasRootCauseInstanceOf(AssertionError.class);
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(tasks).allMatch(ExecutionPhaseHintFuture::callableReleased);
            batch.close();
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * An initial-window handoff {@code Error} abandons every prepared body: the batch stays
     * closeable, {@code awaitBodyCompletion} settles, and no prepared future retains its user
     * callable.
     */
    @Test
    void initialWindowHandoffErrorReleasesPreparedBodiesAndSettlesTheBatch() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(handoffExecutor(command -> {
            throw new AssertionError("handoff broken");
        }));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 2, TaskType.IO_BOUND), submitter);
            List<ExecutionPhaseHintFuture<Integer>> tasks = futures(() -> 1, () -> 2, () -> 3);
            TaskBatch<Integer> batch = submitAllWithViews(executor, tasks);

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(tasks).allMatch(ExecutionPhaseHintFuture::callableReleased);
            batch.close();
            assertThat(batch.report().stateCounts()).containsEntry(TaskOutcome.SUBMISSION_FAILURE, 3);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * L7's "any failure" is literal: {@code execute(Runnable)} declares no checked exceptions, but
     * an executor can still throw one through generics erasure. The batch catch sites cover {@code
     * Throwable}, so even that terminates the batch as a submission failure instead of escaping
     * {@code submitAll}.
     */
    @Test
    void sneakyCheckedHandoffFailureFailsEveryElementAsSubmissionFailure() {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(handoffExecutor(command -> {
            sneakyThrow(new IOException("sneaky handoff"));
        }));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(2, 2, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(executor, futures(() -> 1, () -> 2));

            assertThatThrownBy(batch::valuesOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class)
                    .hasRootCauseInstanceOf(IOException.class);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * The handoff window: the worker executor blocks inside the second {@code execute} until the
     * cancellation lands, so the submitter loop (running the task and binding the placeholder)
     * races the cancellation callback (abandoning placeholders). The claimed element must stay
     * consistent: once handed to the executor it can only be cancelled through its own future, so
     * its callable runs and the caller sees the real result — never SUBMISSION_FAILURE for a task
     * that ran. Only genuinely unsubmitted placeholders are abandoned.
     */
    @Test
    void cancelDuringHandoffKeepsClaimedElementConsistent() throws Exception {
        CountDownLatch secondExecuteEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger ranValues = new AtomicInteger();
        ExecutorService blocking = new AbstractExecutorService() {
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
                if (executions.incrementAndGet() >= 2) {
                    secondExecuteEntered.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        // cancel(true) interrupts the submitter thread; run the handoff anyway
                        Thread.currentThread().interrupt();
                    }
                }
                command.run();
            }
        };
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(blocking);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(3, 1, TaskType.IO_BOUND), submitter);
            TaskBatch<Integer> batch = submitAllWithViews(
                    executor,
                    futures(
                            () -> 1,
                            () -> {
                                ranValues.addAndGet(2);
                                return 2;
                            },
                            () -> {
                                ranValues.addAndGet(3);
                                return 3;
                            }));

            assertThat(secondExecuteEntered.await(5, TimeUnit.SECONDS)).isTrue();
            batch.submitCanceller().cancel(true);
            release.countDown();

            // The claimed element ran and its caller sees the real result, not a submission failure.
            assertThat(batch.results().get(1).get(2, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskOutcome.SUCCESS);
            // The unclaimed element was abandoned without ever entering user code.
            assertThat(batch.results().get(2).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(ranValues.get()).isEqualTo(2);
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    /**
     * The claim-before-completion-check ordering is what keeps the cancellation callback from
     * abandoning an index the submission loop already accepted: once {@code take()} hands over a
     * slot, {@code nextIndex} is bumped before any check, so the callback abandons only strictly
     * later placeholders and the loop itself disposes of the claimed index. The vulnerable window
     * is nanoseconds wide and cannot be gated deterministically, so this test hammers it: many
     * rounds of submit + cancel at staggered moments, asserting the one observable corruption the
     * race produced — a task body that ran while its placeholder was already abandoned (user code
     * ran, the caller reads "never submitted"). On the fixed code the invariant holds by
     * construction; if the claim ever moves back below the completion check, staggered rounds
     * make the corruption possible again.
     */
    @Test
    void repeatedSubmitAndCancelNeverReportsARanTaskAsUnsubmitted() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            for (int round = 0; round < 100; round++) {
                AtomicInteger ran0 = new AtomicInteger();
                AtomicInteger ran1 = new AtomicInteger();
                SlidingWindowSubmitter<Integer> executor =
                        new SlidingWindowSubmitter<>(workers, context(2, 1, TaskType.IO_BOUND), submitter);
                TaskBatch<Integer> batch = submitAllWithViews(
                        executor,
                        futures(
                                () -> {
                                    ran0.incrementAndGet();
                                    return 1;
                                },
                                () -> {
                                    ran1.incrementAndGet();
                                    return 2;
                                }));

                // Stagger the cancellation across the phases of the submission loop: before the
                // submitter thread parks in take(), while it is parked, right around the moment
                // take() hands over the freed slot, and after the handoff completed.
                switch (round % 5) {
                    case 1:
                        Thread.sleep(1L);
                        break;
                    case 2:
                        Thread.sleep(2L);
                        break;
                    case 3:
                        Thread.sleep(5L);
                        break;
                    case 4:
                        Thread.sleep(10L);
                        break;
                    default:
                        Thread.yield();
                }
                batch.submitCanceller().cancel(true);

                await().atMost(5, TimeUnit.SECONDS)
                        .until(() -> batch.results().get(0).isDone()
                                && batch.results().get(1).isDone());

                // The invariant: a body that entered user code is reported as a real success,
                // never as an abandoned placeholder; an abandoned placeholder never entered user
                // code.
                assertThat(batch.results().get(0).outcome() == TaskOutcome.SUCCESS)
                        .as("round %s element 0", round)
                        .isEqualTo(ran0.get() == 1);
                assertThat(batch.results().get(1).outcome() == TaskOutcome.SUCCESS)
                        .as("round %s element 1", round)
                        .isEqualTo(ran1.get() == 1);
            }
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void cancelledQueuedTaskIsTheSameObjectThePoolPurgeRemoves() throws Exception {
        // Regression for the old completion-service wrapper: cancelling the future returned to the
        // caller must be visible on the exact runnable held by the worker pool's queue, so
        // ThreadPoolExecutor.purge can release it.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(pool);
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            SlidingWindowSubmitter<Integer> executor =
                    new SlidingWindowSubmitter<>(workers, context(2, 2, TaskType.IO_BOUND), submitter);
            List<ExecutionPhaseHintFuture<Integer>> tasks = futures(
                    () -> {
                        workerStarted.countDown();
                        releaseWorker.await();
                        return 1;
                    },
                    () -> 2);
            TaskBatch<Integer> batch = submitAllWithViews(executor, tasks);
            assertThat(batch.results()).hasSize(2);
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(pool.getQueue()).containsExactly((Runnable) tasks.get(1));
            assertThat(tasks.get(1).cancel(false)).isTrue();

            pool.purge();
            assertThat(pool.getQueue()).isEmpty();
        } finally {
            releaseWorker.countDown();
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @SafeVarargs
    private static List<ExecutionPhaseHintFuture<Integer>> futures(Callable<Integer>... tasks) {
        return Arrays.stream(tasks)
                .map(task -> ExecutionPhaseHintFuture.create(task))
                .collect(Collectors.toList());
    }

    /** An executor service whose {@code execute()} delegates to the given handoff. */
    private static ExecutorService handoffExecutor(Consumer<Runnable> handoff) {
        return new AbstractExecutorService() {
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
                handoff.accept(command);
            }
        };
    }

    /** Throws a checked throwable past {@code execute()}'s unchecked signature, the way a hostile executor can. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private static MultiTaskContext context(int tasks, int parallelism, TaskType type) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("batch", Duration.ofSeconds(30))
                        .parallelism(parallelism)
                        .taskType(type)
                        .spec(),
                tasks));
    }

    private static int runTracked(AtomicInteger active, AtomicInteger maximum, CountDownLatch release, int value)
            throws InterruptedException {
        int now = active.incrementAndGet();
        maximum.updateAndGet(previous -> Math.max(previous, now));
        try {
            release.await(2, TimeUnit.SECONDS);
            return value;
        } finally {
            active.decrementAndGet();
        }
    }

    private static boolean awaitMaximum(AtomicInteger maximum, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (maximum.get() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        return maximum.get() >= expected;
    }
}

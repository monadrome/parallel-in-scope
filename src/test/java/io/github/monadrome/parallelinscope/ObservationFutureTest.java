package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the observation futures: {@link TaskFuture#completionFuture()} and {@link
 * TaskBatch#completionFuture()} publish the final immutable {@link TaskCompletion} snapshot
 * of every task — including tasks that never started — once the task future is terminal and the
 * task body has exited.
 */
class ObservationFutureTest {

    private static final Duration SCOPE_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void batchObservationPublishesFinalSnapshotsInInputOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        IllegalStateException boom = new IllegalStateException("boom");
        CountDownLatch firstSettled = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("first", "second", "third"),
                            item -> {
                                if ("second".equals(item)) {
                                    // The fail-fast cascade races the sibling's settle: a body that
                                    // returned but has not settled yet can still be cancelled into
                                    // FAIL_FAST. Failing only after first's future settled keeps the
                                    // expected attribution deterministic. A settled future fires an
                                    // immediately-registered listener inline, so this cannot wedge.
                                    try {
                                        firstSettled.await();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException("interrupted while awaiting the sibling", e);
                                    }
                                    throw boom;
                                }
                                return item;
                            },
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT).parallelism(2));
            batch.results().get(0).addListener(firstSettled::countDown, MoreExecutors.directExecutor());

            List<TaskCompletion<String>> completions = batch.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(completions).hasSize(3);
            // Input order is preserved and every snapshot is final: identity, timings, outcome.
            assertThat(completions).extracting(TaskCompletion::taskIndex).containsExactly(0, 1, 2);
            TaskCompletion<String> first = completions.get(0);
            assertThat(first.taskName()).isEqualTo("orders");
            assertThat(first.successful()).isTrue();
            assertThat(first.result()).isEqualTo("first");
            assertThat(first.startTimeNanos()).isGreaterThanOrEqualTo(first.submitTimeNanos());
            assertThat(first.endTimeNanos()).isGreaterThanOrEqualTo(first.startTimeNanos());
            assertThat(first.executionTime().isNegative()).isFalse();
            assertThat(first.waitTime().isNegative()).isFalse();

            TaskCompletion<String> second = completions.get(1);
            assertThat(second.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(second.failure()).isSameAs(boom);
            assertThat(second.result()).isNull();
            assertThat(second.unitId()).isEqualTo(first.unitId());

            // The batch list and the element observation agree — one snapshot, two views.
            assertThat(batch.results().get(0).completionFuture().get(2, TimeUnit.SECONDS))
                    .isSameAs(first);

            // The list is immutable and the future ignores cancellation.
            assertThatThrownBy(() -> completions.clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(batch.completionFuture().cancel(true)).isFalse();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void batchObservationIncludesNeverStartedElementsWithRealOutcome() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("blocker", "queued-a", "queued-b"),
                            item -> holdUninterruptibly(block, item),
                            BatchOptions.timeout("window", SCOPE_TIMEOUT).parallelism(1));

            // Element 0 occupies the only slot; the other two are still waiting for one and the
            // batch close abandons them before submission.
            batch.close();
            block.countDown();

            List<TaskCompletion<String>> completions = batch.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(completions).hasSize(3);
            for (int index = 1; index < 3; index++) {
                TaskCompletion<String> abandoned = completions.get(index);
                assertThat(abandoned.taskIndex()).isEqualTo(index);
                assertThat(abandoned.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
                // Never started: zero timings and durations, but a real outcome and identity.
                assertThat(abandoned.startTimeNanos()).isZero();
                assertThat(abandoned.endTimeNanos()).isZero();
                assertThat(abandoned.executionTime()).isZero();
                assertThat(abandoned.submitTimeNanos()).isGreaterThan(0L);
            }
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void directCancellationOfAWaitingElementPublishesNeverStartedSnapshot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("blocker", "queued"),
                            item -> holdQuietly(block, item),
                            BatchOptions.timeout("window", SCOPE_TIMEOUT).parallelism(1));

            TaskFuture<String> queued = batch.results().get(1);
            assertThat(queued.cancel(true)).isTrue();

            TaskCompletion<String> snapshot = queued.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.MEMBER_CANCELLED);
            assertThat(snapshot.startTimeNanos()).isZero();
            assertThat(snapshot.endTimeNanos()).isZero();
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void preSubmissionObservationResolvesWithTheRealTimings() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("blocker", "queued"),
                            item -> holdQuietly(block, item),
                            BatchOptions.timeout("window", SCOPE_TIMEOUT).parallelism(1));

            TaskFuture<String> queued = batch.results().get(1);
            ListenableFuture<TaskCompletion<String>> whileWaiting = queued.completionFuture();
            block.countDown();

            assertThat(queued.get(2, TimeUnit.SECONDS)).isEqualTo("queued");
            TaskCompletion<String> snapshot = whileWaiting.get(2, TimeUnit.SECONDS);
            // The observation future captured while the element was still waiting for a slot
            // resolves with the real task's final timings once it runs.
            assertThat(snapshot.successful()).isTrue();
            assertThat(snapshot.result()).isEqualTo("queued");
            assertThat(snapshot.startTimeNanos()).isGreaterThan(0L);
            assertThat(queued.completionFuture()).isSameAs(whileWaiting);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void observationAttributesTimeoutFromTheToken() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskFuture<String> task = global.par(ParId.of("worker"))
                    .submit("slow", () -> holdQuietly(block, "slow"), TaskOptions.timeout(Duration.ofMillis(50)));

            assertThatThrownBy(() -> task.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            TaskCompletion<String> snapshot = task.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void observationAttributesFailFastFromTheSharedBatchToken() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("fail", "victim"),
                            item -> {
                                started.countDown();
                                if ("fail".equals(item)) {
                                    awaitQuietly(started);
                                    throw new IllegalStateException("boom");
                                }
                                return holdQuietly(block, item);
                            },
                            BatchOptions.timeout("fail-fast", SCOPE_TIMEOUT).parallelism(2));

            List<TaskCompletion<String>> completions = batch.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(completions.get(0).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(completions.get(1).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void cancelMidRunPublishesObservationOnlyAfterBodyExit() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        try {
            TaskFuture<String> task = global.par(ParId.of("worker"))
                    .submit(
                            "raced",
                            () -> {
                                bodyEntered.countDown();
                                boolean interrupted = false;
                                for (; ; ) {
                                    try {
                                        if (releaseBody.await(50, TimeUnit.MILLISECONDS)) {
                                            break;
                                        }
                                    } catch (InterruptedException interruptedBody) {
                                        interrupted = true;
                                    }
                                }
                                if (interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return "late";
                            },
                            TaskOptions.timeout(SCOPE_TIMEOUT));

            assertThat(bodyEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(task.cancel(true)).isTrue();

            // The future is already cancelled, but the body is still inside its finally: the
            // observation must wait for the body exit and its final end time.
            assertThat(task.completionFuture().isDone()).isFalse();

            releaseBody.countDown();
            TaskCompletion<String> snapshot = task.completionFuture().get(2, TimeUnit.SECONDS);
            // A direct cancel of a submitted task trips the token's fail-fast binding, exactly as
            // task.outcome() already reads it; the snapshot agrees with that attribution.
            assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
            assertThat(snapshot.startTimeNanos()).isGreaterThan(0L);
            assertThat(snapshot.endTimeNanos()).isGreaterThanOrEqualTo(snapshot.startTimeNanos());
        } finally {
            releaseBody.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void awaitBodyCompletionTrueImpliesObservationAvailable() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2, 3), value -> value, BatchOptions.timeout("awaited", SCOPE_TIMEOUT));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            // No polling and no further wait: the observation future is already done.
            assertThat(batch.completionFuture().isDone()).isTrue();
            assertThat(batch.completionFuture().get()).hasSize(3);
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void observationCallbackRunsOnTheCallersExecutorWithTheFinalSnapshot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        AtomicReference<TaskCompletion<String>> captured = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        try {
            TaskFuture<String> task = global.par(ParId.of("worker"))
                    .submit("observed", () -> "value", TaskOptions.timeout(SCOPE_TIMEOUT));

            Futures.addCallback(
                    task.completionFuture(),
                    new FutureCallback<TaskCompletion<String>>() {
                        @Override
                        public void onSuccess(@Nullable TaskCompletion<String> completion) {
                            captured.set(completion);
                            callbackThread.set(Thread.currentThread());
                        }

                        @Override
                        public void onFailure(Throwable failure) {}
                    },
                    callbackExecutor);

            assertThat(task.get(2, TimeUnit.SECONDS)).isEqualTo("value");
            Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> captured.get() != null);
            assertThat(Objects.requireNonNull(captured.get()).result()).isEqualTo("value");
            assertThat(Objects.requireNonNull(callbackThread.get()).getName())
                    .isNotEqualTo(Thread.currentThread().getName());
        } finally {
            callbackExecutor.shutdownNow();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void groupCompletionObservationCarriesTheGroupLevelSummary() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", SCOPE_TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            TaskCompletion<TaskGroupReport> summary =
                    group.completionFuture().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(summary.taskName()).isEqualTo("page");
            assertThat(summary.unitId()).isEqualTo(group.groupId());
            assertThat(summary.taskIndex()).isZero();
            assertThat(summary.result()).isSameAs(result);
            assertThat(summary.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(summary.submitTimeNanos()).isEqualTo(result.startTimeNanos());
            assertThat(summary.endTimeNanos()).isEqualTo(result.endTimeNanos());

            // The member's own observation keeps its per-task direct attribution, while the group
            // result remains the authority for post-convergence attribution.
            TaskCompletion<String> member = group.futureOf("user", TypeToken.of(String.class))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(member.taskName()).isEqualTo("user");
            assertThat(member.successful()).isTrue();
            assertThat(Objects.requireNonNull(result.members().get("user")).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void groupCompletionObservationCarriesFailureOutcomeAndRecordedFailure() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", SCOPE_TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        throw boom;
                    })
                    .submitAll();

            TaskCompletion<TaskGroupReport> summary =
                    group.completionFuture().completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(summary.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(summary.failure()).isSameAs(boom);
            assertThat(summary.result()).isNotNull();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds until the latch opens, ignoring interruption; restores the flag before returning. */
    private static String holdUninterruptibly(CountDownLatch latch, String item) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return item;
    }

    private static String holdQuietly(CountDownLatch latch, String item) {
        for (; ; ) {
            try {
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    return item;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return item;
            }
        }
    }
}

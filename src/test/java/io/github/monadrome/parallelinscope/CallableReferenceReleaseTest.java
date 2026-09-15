package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.alibaba.ttl.TtlCallable;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Kernel-level reference-release contract (decision §9): the user callable must become unreachable
 * through the framework on every terminal path. Probes are the package-private holder states, not
 * GC timing (decision §16).
 */
class CallableReferenceReleaseTest {

    @Test
    void runFinallyReleasesFutureAndDelegateReferences() throws Exception {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        Fixture fixture = fixture(unit("run-release"), tracker, 0, () -> "ok");
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            fixture.future.submitPrepared(workers, false);

            assertThat(fixture.future.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            // releaseDelegate and the body-exit publish happen before the future completes, so they
            // are happens-before visible here; the future-holder release sits in run()'s finally,
            // just after completion, and must arrive without any GC.
            assertThat(fixture.scoped.delegateReleased()).isTrue();
            assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> assertThat(fixture.future.callableReleased()).isTrue());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void callerThreadFallbackRejectionStillRunsAndReleases() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        AtomicBoolean ran = new AtomicBoolean();
        Fixture fixture = fixture(unit("inline-release"), tracker, 0, () -> {
            ran.set(true);
            return "inline";
        });

        fixture.future.submitPrepared(
                command -> {
                    throw new RejectedExecutionException("pool closed");
                },
                true);

        assertThat(ran).isTrue();
        assertThat(fixture.future.callableReleased()).isTrue();
        assertThat(fixture.scoped.delegateReleased()).isTrue();
        assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
    }

    @Test
    void executorRejectionReleasesWithoutRunningBody() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        AtomicBoolean ran = new AtomicBoolean();
        Fixture fixture = fixture(unit("reject-release"), tracker, 0, () -> {
            ran.set(true);
            return "never";
        });

        fixture.future.submitPrepared(
                command -> {
                    throw new RejectedExecutionException("no capacity");
                },
                false);

        assertThat(ran).isFalse();
        assertThat(fixture.future.isDone()).isTrue();
        assertThatThrownBy(() -> fixture.future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(SubmissionException.class);
        assertThat(fixture.future.callableReleased()).isTrue();
        assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
    }

    @Test
    void cancelBeforeRunReleasesWithoutRunningBody() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        AtomicBoolean ran = new AtomicBoolean();
        Fixture fixture = fixture(unit("cancel-release"), tracker, 0, () -> {
            ran.set(true);
            return "never";
        });

        assertThat(fixture.future.cancel(true)).isTrue();

        assertThat(ran).isFalse();
        assertThat(fixture.future.isCancelled()).isTrue();
        assertThat(fixture.future.callableReleased()).isTrue();
        assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
    }

    @Test
    void windowAbandonmentMarkBodySkippedReleasesWithoutCompletingTheFuture() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        Fixture fixture = fixture(unit("skip-release"), tracker, 0, () -> "never");

        fixture.future.markBodySkipped();

        // The placeholder-only path never completes the engine future; only the body slot and the
        // reference are released.
        assertThat(fixture.future.isDone()).isFalse();
        assertThat(fixture.future.callableReleased()).isTrue();
        assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
    }

    @Test
    void runToleratesHolderClearedBetweenClaimAndDereference() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        AtomicBoolean ran = new AtomicBoolean();
        Fixture fixture = fixture(unit("clear-before-deref"), tracker, 0, () -> {
            ran.set(true);
            return "never";
        });

        // Simulates the sliding-window race where the abandonment callback clears the holder after
        // the worker won the phase claim but before it dereferenced it: from run()'s perspective
        // the clear lands between the claim and the read. The body must not run, no NPE may be
        // published, and the run must still exit through the normal finally, releasing the body
        // slot through the existing fallback publish.
        fixture.future.markBodySkipped();
        fixture.future.run();

        assertThat(ran).isFalse();
        assertThat(fixture.future.isDone()).isFalse();
        assertThat(fixture.future.callableReleased()).isTrue();
        assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
    }

    @Test
    void cancelDuringRunKeepsReferencesUntilBodyExits() throws Exception {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bodyFinally = new CountDownLatch(1);
        Fixture fixture = fixture(unit("cancel-running"), tracker, 0, () -> {
            entered.countDown();
            try {
                awaitUninterruptibly(release);
                return "ignored-cancel";
            } finally {
                bodyFinally.countDown();
            }
        });
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            fixture.future.submitPrepared(workers, false);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.future.cancel(true)).isTrue();
            // Rule 4: a cancelled future whose body is still running must not drop the reference
            // early — the body and its captures are still in use.
            assertThat(fixture.future.callableReleased()).isFalse();
            assertThat(fixture.scoped.delegateReleased()).isFalse();
            assertThat(fixture.context.bodyState().isOutstanding()).isTrue();

            release.countDown();
            assertThat(bodyFinally.await(5, TimeUnit.SECONDS)).isTrue();
            // The holder release precedes the body-exit publish, so observing the released slot
            // implies both references are gone.
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(fixture.context.bodyState().isOutstanding())
                            .isFalse());
            assertThat(fixture.future.callableReleased()).isTrue();
            assertThat(fixture.scoped.delegateReleased()).isTrue();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void batchPathReleasesEveryPreparedFuture() throws Exception {
        MultiTaskContext batch = batchUnit("batch-release", 3, 1);
        BodyCompletionTracker tracker = BodyCompletionTracker.create(3);
        List<Fixture> fixtures = IntStream.range(0, 3)
                .mapToObj(i -> fixture(batch, tracker, i, () -> "element-" + i))
                .collect(Collectors.toList());
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<String> submit =
                    new SlidingWindowSubmitter<>(workers, batch, submitter, tracker, null);
            TaskBatchResult<String> result =
                    submit.submitAll(fixtures.stream().map(f -> f.future).collect(Collectors.toList()));

            for (int i = 0; i < fixtures.size(); i++) {
                assertThat(result.results().get(i).get(5, TimeUnit.SECONDS)).isEqualTo("element-" + i);
            }
            for (Fixture fixture : fixtures) {
                assertThat(fixture.context.bodyState().isOutstanding()).isFalse();
                await().atMost(5, TimeUnit.SECONDS)
                        .untilAsserted(() ->
                                assertThat(fixture.future.callableReleased()).isTrue());
            }
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void batchAbandonmentReleasesUnsubmittedFutures() throws Exception {
        MultiTaskContext batch = batchUnit("batch-abandon-release", 3, 1);
        BodyCompletionTracker tracker = BodyCompletionTracker.create(3);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AtomicBoolean> ran =
                IntStream.range(0, 3).mapToObj(i -> new AtomicBoolean()).collect(Collectors.toList());
        List<Fixture> fixtures = IntStream.range(0, 3)
                .mapToObj(i -> fixture(batch, tracker, i, () -> {
                    ran.get(i).set(true);
                    if (i == 0) {
                        entered.countDown();
                        awaitUninterruptibly(release);
                    }
                    return "element-" + i;
                }))
                .collect(Collectors.toList());
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            SlidingWindowSubmitter<String> submit =
                    new SlidingWindowSubmitter<>(workers, batch, submitter, tracker, null);
            TaskBatchResult<String> result =
                    submit.submitAll(fixtures.stream().map(f -> f.future).collect(Collectors.toList()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(result.submitCanceller().cancel(true)).isTrue();
            release.countDown();
            for (int i = 0; i < fixtures.size(); i++) {
                awaitDone(result.results().get(i));
            }

            assertThat(ran.get(0)).isTrue();
            assertThat(ran.get(1)).isFalse();
            assertThat(ran.get(2)).isFalse();
            // The abandoned (never-submitted) futures release through the skip path, the claimed
            // one through run()'s finally.
            for (Fixture fixture : fixtures) {
                await().atMost(5, TimeUnit.SECONDS)
                        .untilAsserted(() ->
                                assertThat(fixture.future.callableReleased()).isTrue());
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    private static Fixture fixture(
            MultiTaskContext unit, BodyCompletionTracker tracker, int index, Callable<String> body) {
        TaskExecutionContext context =
                new TaskExecutionContext(unit, index, Ticker.systemTicker().read(), tracker.register(unit));
        Callable<String> wrapped = TaskSubmissions.wrapScoped(context, body, Collections.emptyList());
        ScopedCallable<String> scoped = (ScopedCallable<String>) ((TtlCallable<String>) wrapped).unwrap();
        ExecutionPhaseHintFuture<String> future =
                ExecutionPhaseHintFuture.create(wrapped, phase -> {}, context.bodyState());
        return new Fixture(context, scoped, future);
    }

    private static MultiTaskContext unit(String name) {
        return MultiTaskContext.resolve(
                BatchOptions.timeout(name, Duration.ofSeconds(30)).spec(), 1, null);
    }

    private static MultiTaskContext batchUnit(String name, int tasks, int parallelism) {
        return MultiTaskContext.resolve(
                BatchOptions.timeout(name, Duration.ofSeconds(30))
                        .parallelism(parallelism)
                        .taskType(TaskType.IO_BOUND)
                        .spec(),
                tasks,
                null);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitDone(com.google.common.util.concurrent.ListenableFuture<?> future)
            throws InterruptedException, java.util.concurrent.ExecutionException,
                    java.util.concurrent.TimeoutException {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.CancellationException | java.util.concurrent.ExecutionException ignored) {
            // Abandoned placeholders may cancel or fail with the abandonment cause; only
            // completion matters here.
        }
    }

    private static final class Fixture {
        final TaskExecutionContext context;
        final ScopedCallable<String> scoped;
        final ExecutionPhaseHintFuture<String> future;

        Fixture(TaskExecutionContext context, ScopedCallable<String> scoped, ExecutionPhaseHintFuture<String> future) {
            this.context = context;
            this.scoped = scoped;
            this.future = future;
        }
    }
}

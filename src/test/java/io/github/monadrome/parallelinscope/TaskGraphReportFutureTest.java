package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link TaskGraphObservationScope#reportFuture()}: the disabled / no-issue /
 * issue / failure shapes, close-time publication barriers, concurrent and repeated close, callback
 * reentrancy, and cancellation non-propagation.
 */
class TaskGraphReportFutureTest {

    private final AtomicReference<ParRuntime> runtime = new AtomicReference<>();

    @AfterEach
    void cleanUp() {
        TaskGraphObservationScope.restore(null);
        ParRuntime global = runtime.get();
        if (global != null) {
            global.close();
        }
    }

    private ParRuntime runtimeWithDetection(boolean enabled) {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(
                        ParRuntimeDeadlockPolicy.builder().enabled(enabled).build())
                .build();
        runtime.set(global);
        return global;
    }

    /** Records the task cycle a -> b -> a in the current scope with non-deadlock-prone edges. */
    private static void recordTaskCycle() {
        TaskGraphObservationScope.logTaskPair(
                "a",
                "task-a",
                "b",
                "task-b",
                new TaskEdge(1, TaskType.IO_BOUND, "child-exec", "parent-exec", 1, Duration.ofMillis(10), false));
        TaskGraphObservationScope.logTaskPair(
                "b",
                "task-b",
                "a",
                "task-a",
                new TaskEdge(1, TaskType.IO_BOUND, "child-exec", "parent-exec", 1, Duration.ofMillis(10), false));
    }

    @Test
    void disabledPolicyPublishesDisabledReportAndKeepsStaticQueries() throws Exception {
        ParRuntime global = runtimeWithDetection(false);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordTaskCycle();
        // Graph recording and the static queries are unaffected by the disabled policy.
        assertThat(TaskGraphObservationScope.hasTaskCycle()).isTrue();
        scope.close();

        assertThat(scope.reportFuture().isDone()).isTrue();
        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.DISABLED);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
    }

    @Test
    void enabledCleanGraphPublishesNoIssueReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        TaskGraphObservationScope.logTaskPair(
                "r",
                "r",
                "x",
                "x",
                new TaskEdge(1, TaskType.IO_BOUND, "child-exec", "parent-exec", 1, Duration.ofMillis(10), false));
        scope.close();

        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
    }

    @Test
    void enabledCyclicGraphPublishesIssueReportWithEdges() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordTaskCycle();
        scope.close();

        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.taskCycle()).isTrue();
        assertThat(report.selfLoop()).isFalse();
        assertThat(report.taskEdges()).contains("task-a[a] -> task-b[b]", "task-b[b] -> task-a[a]");
    }

    @Test
    void reportFutureIsPendingUntilClose() {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        assertThat(scope.reportFuture().isDone()).isFalse();
        scope.close();
        assertThat(scope.reportFuture().isDone()).isTrue();
    }

    @Test
    void detectionFailureFailsTheFutureWithoutThrowingFromClose() {
        ParRuntime global = runtimeWithDetection(true);
        IllegalStateException failure = new IllegalStateException("snapshot boom");
        TaskGraphObservationScope scope = new TaskGraphObservationScope(global, new FailingGraphData(failure));

        scope.close(); // must not throw the diagnostic failure

        assertThat(scope.reportFuture().isDone()).isTrue();
        assertThatThrownBy(() -> Futures.getDone(scope.reportFuture()))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(failure);
        // The context was restored even though detection failed.
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void detectionErrorIsPublishedBestEffortAndRethrown() {
        ParRuntime global = runtimeWithDetection(true);
        AssertionError error = new AssertionError("snapshot error");
        TaskGraphObservationScope scope = new TaskGraphObservationScope(global, new FailingGraphData(error));

        assertThatThrownBy(scope::close).isSameAs(error);

        assertThat(scope.reportFuture().isDone()).isTrue();
        assertThatThrownBy(() -> Futures.getDone(scope.reportFuture()))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(error);
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void repeatedClosePublishesOnceAndKeepsTheSameReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordTaskCycle();
        scope.close();
        TaskGraphReport first = doneReport(scope.reportFuture());
        scope.close();
        assertThat(doneReport(scope.reportFuture())).isSameAs(first);
        assertThat(scope.closed()).isTrue();
    }

    @Test
    void concurrentCloseAllReturnWithPublishedReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordTaskCycle();
        ExecutorService closers = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] closes = new Future<?>[4];
            for (int i = 0; i < closes.length; i++) {
                closes[i] = closers.submit(() -> {
                    start.await();
                    scope.close();
                    return null;
                });
            }
            start.countDown();
            for (Future<?> close : closes) {
                close.get(10, TimeUnit.SECONDS);
            }
        } finally {
            closers.shutdownNow();
        }
        assertThat(scope.reportFuture().isDone()).isTrue();
        assertThat(doneReport(scope.reportFuture()).status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void lateEdgesNeverRewriteThePublishedReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        TaskGraphData data = TaskGraphObservationScope.data();
        scope.close();
        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);

        // A late write through the already-obtained data reference still cannot reach the report.
        assertThat(data).isNotNull();
        data.logTaskPair(
                "a",
                "task-a",
                "a",
                "task-a",
                new TaskEdge(1, TaskType.IO_BOUND, "e", "e", 1, Duration.ofMillis(10), false));
        assertThat(data.selfLoop()).isTrue();
        assertThat(doneReport(scope.reportFuture())).isSameAs(report);
        assertThat(doneReport(scope.reportFuture()).anyIssue()).isFalse();
    }

    @Test
    void directCallbackSeesRestoredOuterScopeAndMayReenterCloseAndGet() {
        ParRuntime global = runtimeWithDetection(true);
        AtomicReference<TaskGraphObservationScope> currentInCallback = new AtomicReference<>();
        AtomicReference<TaskGraphReport> reportInCallback = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            TaskGraphObservationScope inner = global.openTaskGraphObservation();
            Futures.addCallback(
                    inner.reportFuture(),
                    new FutureCallback<TaskGraphReport>() {
                        @Override
                        public void onSuccess(@Nullable TaskGraphReport report) {
                            try {
                                currentInCallback.set(TaskGraphObservationScope.current());
                                // Reentrant close and get from inside a direct callback must not self-lock.
                                inner.close();
                                reportInCallback.set(doneReport(inner.reportFuture()));
                            } catch (Throwable t) {
                                callbackFailure.set(t);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            callbackFailure.set(t);
                        }
                    },
                    directExecutor());
            inner.close();

            assertThat(callbackFailure.get()).isNull();
            assertThat(reportInCallback.get()).isNotNull();
            // The inner scope was restored before publication: the callback sees the outer scope.
            assertThat(currentInCallback.get()).isSameAs(outer);
            assertThat(TaskGraphObservationScope.current()).isSameAs(outer);
        }
    }

    @Test
    void waitingCloseReturnsWhenTheValueIsSetNotWhenCallbacksFinish() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Futures.addCallback(
                scope.reportFuture(),
                new FutureCallback<TaskGraphReport>() {
                    @Override
                    public void onSuccess(@Nullable TaskGraphReport report) {
                        callbackStarted.countDown();
                        try {
                            releaseCallback.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {}
                },
                directExecutor());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = pool.submit(scope::close);
            assertThat(callbackStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // The loser's barrier is the sink's terminal state: it must not wait out the blocked callback.
            Future<?> contender = pool.submit(scope::close);
            contender.get(10, TimeUnit.SECONDS);

            releaseCallback.countDown();
            winner.get(10, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
            pool.shutdownNow();
        }
        assertThat(scope.reportFuture().isDone()).isTrue();
    }

    @Test
    void cancelOnTheReportFutureReturnsFalseAndPropagatesNowhere() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        assertThat(scope.reportFuture().cancel(true)).isFalse();
        assertThat(scope.reportFuture().isCancelled()).isFalse();
        assertThat(scope.closed()).isFalse();

        recordTaskCycle();
        scope.close();
        // Cancellation attempts did not disturb detection or the published report.
        assertThat(doneReport(scope.reportFuture()).status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(scope.reportFuture().cancel(false)).isFalse();
    }

    @Test
    void callbackRegisteredAfterCloseStillReceivesTheReport() {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        scope.close();

        AtomicReference<TaskGraphReport> seen = new AtomicReference<>();
        Futures.addCallback(
                scope.reportFuture(),
                new FutureCallback<TaskGraphReport>() {
                    @Override
                    public void onSuccess(@Nullable TaskGraphReport report) {
                        seen.set(report);
                    }

                    @Override
                    public void onFailure(Throwable t) {}
                },
                directExecutor());
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
    }

    @Test
    void reportsAreIsolatedPerRuntimeAndNestedScope() throws Exception {
        ParRuntime disabledRuntime = runtimeWithDetection(false);
        TaskGraphObservationScope disabledScope = disabledRuntime.openTaskGraphObservation();
        recordTaskCycle();
        disabledScope.close();
        assertThat(doneReport(disabledScope.reportFuture()).status()).isEqualTo(TaskGraphReport.Status.DISABLED);

        ParRuntime other = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        runtime.set(other);
        TaskGraphObservationScope outer = other.openTaskGraphObservation();
        TaskGraphObservationScope inner = other.openTaskGraphObservation();
        recordTaskCycle(); // lands in the inner scope's graph only
        inner.close();
        assertThat(doneReport(inner.reportFuture()).status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        // The outer scope has not closed, so its report is still pending.
        assertThat(outer.reportFuture().isDone()).isFalse();
        outer.close();
        // The inner scope's edges stay out of the outer report.
        assertThat(doneReport(outer.reportFuture()).status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
    }

    @Test
    void waitingClosePreservesTheInterruptFlag() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        TaskGraphObservationScope scope =
                new TaskGraphObservationScope(global, new BlockingGraphData(snapshotEntered, releaseSnapshot));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = pool.submit(scope::close);
            assertThat(snapshotEntered.await(10, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Boolean> interruptedAtReturn = new AtomicReference<>();
            Future<?> contender = pool.submit(() -> {
                Thread.currentThread().interrupt();
                scope.close();
                interruptedAtReturn.set(Thread.currentThread().isInterrupted());
            });
            // The contender is parked in the publication wait; let it notice the interrupt.
            Thread.sleep(100);
            releaseSnapshot.countDown();

            winner.get(10, TimeUnit.SECONDS);
            contender.get(10, TimeUnit.SECONDS);
            assertThat(interruptedAtReturn.get()).isTrue();
        } finally {
            releaseSnapshot.countDown();
            pool.shutdownNow();
        }
        assertThat(scope.reportFuture().isDone()).isTrue();
    }

    /** Reads a report known to be published; an unexpected detection failure fails the test. */
    private static TaskGraphReport doneReport(
            com.google.common.util.concurrent.ListenableFuture<TaskGraphReport> future) {
        try {
            return java.util.Objects.requireNonNull(Futures.getDone(future));
        } catch (java.util.concurrent.ExecutionException unexpected) {
            throw new AssertionError("report future failed unexpectedly", unexpected);
        }
    }

    /** Graph data whose snapshot computation fails with the given throwable. */
    private static final class FailingGraphData extends TaskGraphData {
        private final Throwable failure;

        FailingGraphData(Throwable failure) {
            this.failure = failure;
        }

        @Override
        Snapshot snapshot() {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        }
    }

    /** Graph data whose snapshot blocks until released, to hold the close winner mid-detection. */
    private static final class BlockingGraphData extends TaskGraphData {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingGraphData(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        Snapshot snapshot() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.snapshot();
        }
    }
}

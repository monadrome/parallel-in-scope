package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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

    /** Records two starvation-prone pools waiting on each other into {@code scope}. */
    private static void recordExecutorCycle(TaskGraphObservationScope scope) {
        scope.record(TaskGraphFixtures.twoPoolCycle());
    }

    /**
     * A disabled policy publishes a DISABLED report, while graph recording and the static queries are
     * unaffected by it.
     */
    @Test
    void disabledPolicyPublishesDisabledReportAndKeepsStaticQueries() throws Exception {
        ParRuntime global = runtimeWithDetection(false);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordExecutorCycle(scope);
        assertThat(TaskGraphObservationScope.hasExecutorCycle()).isTrue();
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
        scope.record(TaskGraph.builder()
                .admit(TaskGraphFixtures.node("r"), TaskGraphFixtures.node("x"))
                .build());
        scope.close();

        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
    }

    @Test
    void enabledCyclicGraphPublishesIssueReportWithEdges() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordExecutorCycle(scope);
        scope.close();

        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.executorCycle()).isTrue();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.taskEdges()).contains("task-a[unit-a] -> task-b[unit-b]", "task-b[unit-b] -> task-c[unit-c]");
        assertThat(report.executorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
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
    void detectionFailurePublishesEvenWhenTheDiagnosticHandlerThrows() {
        ParRuntime global = runtimeWithDetection(true);
        IllegalStateException failure = new IllegalStateException("snapshot boom");
        TaskGraphObservationScope scope = new TaskGraphObservationScope(global, new FailingGraphData(failure));
        Logger observationLogger = Logger.getLogger(TaskGraphObservationScope.class.getName());
        Handler throwing = throwingHandler();
        boolean parentHandlers = observationLogger.getUseParentHandlers();
        Level previousLevel = observationLogger.getLevel();
        observationLogger.addHandler(throwing);
        observationLogger.setUseParentHandlers(false);
        observationLogger.setLevel(Level.ALL);
        try {
            // The user-replaceable handler must not escape close() nor suppress the failed report.
            assertThatCode(scope::close).doesNotThrowAnyException();

            assertThat(scope.reportFuture().isDone()).isTrue();
            assertThatThrownBy(() -> Futures.getDone(scope.reportFuture()))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isSameAs(failure);
            // The context was restored even though detection and diagnostic logging both failed.
            assertThat(TaskGraphObservationScope.current()).isNull();
        } finally {
            observationLogger.removeHandler(throwing);
            observationLogger.setLevel(previousLevel);
            observationLogger.setUseParentHandlers(parentHandlers);
        }
    }

    @Test
    void issueReportSurvivesAnErrorThrowingDiagnosticHandler() {
        ParRuntime global = runtimeWithDetection(true);
        Logger observationLogger = Logger.getLogger(TaskGraphObservationScope.class.getName());
        Handler throwing = errorThrowingHandler();
        Level previousLevel = observationLogger.getLevel();
        boolean parentHandlers = observationLogger.getUseParentHandlers();
        observationLogger.addHandler(throwing);
        observationLogger.setUseParentHandlers(false);
        observationLogger.setLevel(Level.ALL);
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            TaskGraphObservationScope inner = global.openTaskGraphObservation();
            recordExecutorCycle(inner);

            // A handler Error on the real-ISSUE path must not replace the computed report with the
            // logging failure, and must not escape close().
            assertThatCode(inner::close).doesNotThrowAnyException();

            TaskGraphReport report = doneReport(inner.reportFuture());
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
            assertThat(report.anyIssue()).isTrue();
            // The inner scope was restored even though detection diagnostics threw an Error.
            assertThat(TaskGraphObservationScope.current()).isSameAs(outer);
        } finally {
            observationLogger.removeHandler(throwing);
            observationLogger.setLevel(previousLevel);
            observationLogger.setUseParentHandlers(parentHandlers);
        }
    }

    @Test
    void repeatedAndConcurrentCloseAfterDetectionFailureDoNotHangWithABrokenHandler() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        IllegalStateException failure = new IllegalStateException("snapshot boom");
        TaskGraphObservationScope scope = new TaskGraphObservationScope(global, new FailingGraphData(failure));
        Logger observationLogger = Logger.getLogger(TaskGraphObservationScope.class.getName());
        Handler throwing = throwingHandler();
        boolean parentHandlers = observationLogger.getUseParentHandlers();
        observationLogger.addHandler(throwing);
        observationLogger.setUseParentHandlers(false);
        ExecutorService closers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        try {
            assertThatCode(scope::close).doesNotThrowAnyException();

            // Every later close waits on the winner's publication; that wait must end because the
            // failed report was published despite the broken diagnostic handler.
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
            assertThat(scope.reportFuture().isDone()).isTrue();
            assertThatThrownBy(() -> Futures.getDone(scope.reportFuture()))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isSameAs(failure);
        } finally {
            closers.shutdownNow();
            observationLogger.removeHandler(throwing);
            observationLogger.setUseParentHandlers(parentHandlers);
        }
    }

    @Test
    void repeatedClosePublishesOnceAndKeepsTheSameReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        recordExecutorCycle(scope);
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
        recordExecutorCycle(scope);
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

    /**
     * A late write cannot reach the published report: not through the closed scope, which records
     * nothing, and not through a reference to its recorder, which keeps accepting writes the frozen
     * report never sees.
     */
    @Test
    void lateEdgesNeverRewriteThePublishedReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        TaskGraphData data = new TaskGraphData();
        TaskGraphObservationScope scope = new TaskGraphObservationScope(global, data);
        scope.close();
        TaskGraphReport report = doneReport(scope.reportFuture());
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);

        recordExecutorCycle(scope);
        assertThat(scope.graph().asValueGraph().nodes()).isEmpty();
        data.record(TaskGraphFixtures.twoPoolCycle());
        assertThat(data.snapshot().executors().cycle()).isTrue();
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

        recordExecutorCycle(scope);
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
        recordExecutorCycle(disabledScope);
        disabledScope.close();
        assertThat(doneReport(disabledScope.reportFuture()).status()).isEqualTo(TaskGraphReport.Status.DISABLED);

        ParRuntime other = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        runtime.set(other);
        TaskGraphObservationScope outer = other.openTaskGraphObservation();
        TaskGraphObservationScope inner = other.openTaskGraphObservation();
        recordExecutorCycle(Objects.requireNonNull(TaskGraphObservationScope.resolveFor(null, other)));
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
    private static TaskGraphReport doneReport(ListenableFuture<TaskGraphReport> future) {
        try {
            return Objects.requireNonNull(Futures.getDone(future));
        } catch (ExecutionException unexpected) {
            throw new AssertionError("report future failed unexpectedly", unexpected);
        }
    }

    /** A JUL handler that throws on every record, standing in for a user-installed broken handler. */
    private static Handler throwingHandler() {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("handler boom");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    /** A handler whose failure is an {@link Error}, which the quiet-logging guards must also absorb. */
    private static Handler errorThrowingHandler() {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new AssertionError("handler boom");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    /**
     * The interleaving the closed-check leaves open: a writer passes {@code record}'s check while the
     * scope is open and is held there while another thread closes the scope. The report is frozen from
     * the snapshot taken at close and never sees the fragment; the fragment then lands in the recorder
     * whole, never as a part.
     */
    @Test
    void aRecordRacingCloseLandsWholeAfterTheFrozenReport() throws Exception {
        ParRuntime global = runtimeWithDetection(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GatedGraphData data = new GatedGraphData(entered, release);
        TaskGraphObservationScope scope = new TaskGraphObservationScope(global, data);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<?> write = writer.submit(() -> scope.record(TaskGraphFixtures.twoPoolCycle()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            scope.close();
            TaskGraphReport report = doneReport(scope.reportFuture());
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);

            release.countDown();
            write.get(10, TimeUnit.SECONDS);
            assertThat(data.snapshot().tasks().asValueGraph().nodes()).hasSize(3);
            assertThat(data.snapshot().tasks().asValueGraph().edges()).hasSize(2);
            assertThat(doneReport(scope.reportFuture())).isSameAs(report);
        } finally {
            release.countDown();
            writer.shutdownNow();
        }
    }

    /** Graph data that holds a writer after the scope's closed-check, before the merge takes the lock. */
    private static final class GatedGraphData extends TaskGraphData {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        GatedGraphData(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        void record(TaskGraph fragment) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.record(fragment);
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

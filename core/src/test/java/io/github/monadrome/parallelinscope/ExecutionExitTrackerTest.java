package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Direct contract test of the shared close mechanism: counts, bounded waits, the zero-grace close,
 * and interruption during a close wait.
 */
class ExecutionExitTrackerTest {

    @Test
    void emptyTrackerIsDoneWithoutReleases() {
        assertThat(ExecutionExitTracker.empty().isDone()).isTrue();
        assertThat(ExecutionExitTracker.create(0).isDone()).isTrue();
    }

    @Test
    void outstandingExecutionsKeepTheSignalPendingUntilTheLastRelease() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(2);
        TaskExecutionState first = tracker.register(context("first"));
        TaskExecutionState second = tracker.register(context("second"));

        assertThat(tracker.isDone()).isFalse();
        assertThat(first.claimRunning()).isTrue();
        first.exited();
        assertThat(tracker.isDone()).isFalse();
        assertThat(second.claimRunning()).isTrue();
        second.exited();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void awaitBoundedNonPositiveBudgetPerformsASingleCheck() throws Exception {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context("task"));

        assertThat(tracker.awaitBounded(0L)).isFalse();
        assertThat(tracker.awaitBounded(-1L)).isFalse();

        assertThat(slot.claimRunning()).isTrue();
        slot.exited();
        assertThat(tracker.awaitBounded(0L)).isTrue();
        assertThat(tracker.awaitBounded(-1L)).isTrue();
    }

    @Test
    void awaitBoundedZeroBudgetDoesNotConsumeAnInterrupt() throws Exception {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        tracker.register(context("task"));

        Thread.currentThread().interrupt();
        try {
            assertThat(tracker.awaitBounded(0L)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void awaitBoundedPositiveBudgetWaitsForAndReportsCompletion() throws Exception {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context("task"));
        assertThat(slot.claimRunning()).isTrue();
        slot.exited();

        assertThat(tracker.awaitBounded(Duration.ofSeconds(10).toNanos())).isTrue();
    }

    @Test
    void awaitExecutionExitZeroTimeoutIsASingleCheck() throws Exception {
        ExecutionExitTracker pending = ExecutionExitTracker.create(1);
        pending.register(context("task"));
        assertThat(pending.awaitExecutionExit(Duration.ZERO)).isFalse();

        ExecutionExitTracker done = ExecutionExitTracker.create(1);
        TaskExecutionState doneSlot = done.register(context("task"));
        assertThat(doneSlot.claimRunning()).isTrue();
        doneSlot.exited();
        assertThat(done.awaitExecutionExit(Duration.ZERO)).isTrue();
    }

    @Test
    void zeroGraceCloseCancelsWithoutWaitingAndWithoutAWarning() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        tracker.register(context("task"));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<String> warnings = new Vector<>();

        runWithLogCapture(
                warnings::add,
                () -> ExecutionExitTracker.cancelAndAwaitExecutionExit(
                        () -> cancelled.set(true), tracker, 0L, "scope 'x'", logger()));

        assertThat(cancelled).isTrue();
        assertThat(warnings).isEmpty();
    }

    @Test
    void closeGraceWaitReportsOutstandingExecutionsWhenItElapses() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        tracker.register(context("stuck"));
        List<String> warnings = new Vector<>();

        runWithLogCapture(
                warnings::add,
                () -> ExecutionExitTracker.cancelAndAwaitExecutionExit(
                        () -> {}, tracker, Duration.ofMillis(20).toNanos(), "scope 'x'", logger()));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("stuck");
    }

    @Test
    void interruptionDuringACloseWaitRestoresTheFlagAndStopsQuietly() throws Exception {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        tracker.register(context("task"));
        List<String> warnings = new Vector<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                runWithLogCapture(
                        warnings::add,
                        () -> ExecutionExitTracker.cancelAndAwaitExecutionExit(
                                () -> {}, tracker, Duration.ofSeconds(10).toNanos(), "scope 'x'", logger()));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        waiter.start();
        Thread.sleep(200L);
        waiter.interrupt();
        waiter.join(5000L);

        assertThat(failure.get()).isNull();
        assertThat(waiter.isInterrupted())
                .as("the interrupt flag is restored, not swallowed, by the close wait")
                .isTrue();
        assertThat(warnings).isEmpty();
    }

    @Test
    void awaitSettledUninterruptiblyDistinguishesSettledFailedAndElapsed() throws Exception {
        SettableFuture<String> done = SettableFuture.create();
        done.set("value");
        SettableFuture<String> pending = SettableFuture.create();
        long start = System.nanoTime();

        assertThat(ExecutionExitTracker.awaitSettledUninterruptibly(done, 0L, start, null))
                .isTrue();
        assertThat(ExecutionExitTracker.awaitSettledUninterruptibly(pending, 0L, start, null))
                .isFalse();
        assertThat(ExecutionExitTracker.awaitSettledUninterruptibly(
                        pending, Duration.ofMillis(50).toNanos(), start, null))
                .isFalse();

        // A failure discovered during the wait is settled, unless the caller declared the future
        // cannot fail — then it is an implementation defect the wait reports.
        SettableFuture<String> lateFailure = SettableFuture.create();
        failAfter(100L, lateFailure);
        assertThat(ExecutionExitTracker.awaitSettledUninterruptibly(
                        lateFailure, Duration.ofSeconds(10).toNanos(), System.nanoTime(), null))
                .isTrue();
        SettableFuture<String> declaredCannotFail = SettableFuture.create();
        failAfter(100L, declaredCannotFail);
        assertThatThrownBy(() -> ExecutionExitTracker.awaitSettledUninterruptibly(
                        declaredCannotFail,
                        Duration.ofSeconds(10).toNanos(),
                        System.nanoTime(),
                        "declared cannot fail"))
                .isInstanceOf(AssertionError.class);

        // A value published during the uninterruptible wait is also a successful wait.
        SettableFuture<String> lateDone = SettableFuture.create();
        Thread finisher = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                // test-owned thread; the wait below returns regardless
            }
            lateDone.set("late");
        });
        finisher.setDaemon(true);
        finisher.start();
        assertThat(ExecutionExitTracker.awaitSettledUninterruptibly(
                        lateDone, Duration.ofSeconds(10).toNanos(), System.nanoTime(), null))
                .isTrue();
        finisher.join(5000L);
    }

    private static void failAfter(long delayMillis, SettableFuture<String> future) {
        Thread failer = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                future.setException(interrupted);
                return;
            }
            future.setException(new IllegalStateException("boom"));
        });
        failer.setDaemon(true);
        failer.start();
    }

    @Test
    void awaitSettledReportsSettlementAndElapsedBudget() throws Exception {
        SettableFuture<String> done = SettableFuture.create();
        done.set("value");
        SettableFuture<String> pending = SettableFuture.create();
        long start = System.nanoTime();

        assertThat(ExecutionExitTracker.awaitSettled(done, 0L, start, null)).isTrue();
        assertThat(ExecutionExitTracker.awaitSettled(pending, 0L, start, null)).isFalse();
        assertThat(ExecutionExitTracker.awaitSettled(
                        pending, Duration.ofMillis(50).toNanos(), start, null))
                .isFalse();

        // A future that settles during the wait is a successful wait, not a spent budget.
        SettableFuture<String> lateDone = SettableFuture.create();
        Thread finisher = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ignored) {
                // test-owned thread; the wait below returns regardless
            }
            lateDone.set("late");
        });
        finisher.setDaemon(true);
        finisher.start();
        assertThat(ExecutionExitTracker.awaitSettled(
                        lateDone, Duration.ofSeconds(10).toNanos(), System.nanoTime(), null))
                .isTrue();
        finisher.join(5000L);
    }

    @Test
    void stuckExecutionSummaryCountsOutstandingExecutionsByName() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(3);
        TaskExecutionState first = tracker.register(context("worker"));
        tracker.register(context("worker"));
        tracker.register(context("other"));

        assertThat(first.claimRunning()).isTrue();
        first.exited();
        assertThat(tracker.unexitedExecutionSummary())
                .containsEntry("worker", 1)
                .containsEntry("other", 1);
    }

    private static MultiTaskContext context(String taskName) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout(taskName, Duration.ofSeconds(30)).spec(), 1));
    }

    private static Logger logger() {
        return Logger.getLogger(ExecutionExitTrackerTest.class.getName());
    }

    /** Captures WARNING records published while {@code action} runs on this thread's logger. */
    private static void runWithLogCapture(java.util.function.Consumer<String> sink, Runnable action) {
        Logger logger = logger();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    sink.accept(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(capture);
        try {
            action.run();
        } finally {
            logger.removeHandler(capture);
        }
    }
}

package io.github.monadrome.parallelinscope;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Shared task-body completion signal for one submission — a task group or a batch.
 *
 * <p>Every prepared task registers a {@link TaskBodyState} slot before any task is submitted; the
 * {@code bodyExit} future starts with an outstanding count of the total task count and completes
 * only when every task body has exited or has been atomically determined to never enter its body.
 * Waiting on the signal therefore cannot be defeated by tasks that start late: a {@code true}
 * result is monotonic and establishes a happens-before edge from every task body's writes to the
 * waiting thread (via {@link SettableFuture}).
 *
 * <p>The signal is a future rather than a bare latch so it composes with the rest of the library:
 * the owning {@code ParRuntime} aggregates the signals of every admitted submission without
 * dedicating a thread per wait, and a timed wait distinguishes "all bodies exited" from "budget
 * elapsed" instead of merging them.
 *
 * <p>The tracker also carries the identity units needed by the self-await guard: the units of the
 * group's members (and terminal combine) or of the batch. Registration happens on the single
 * submitting thread before submission; the slot list and identity set are never mutated afterwards,
 * and publication to other threads rides the task-submission handoff.
 */
final class BodyCompletionTracker {

    private final AtomicInteger outstanding;
    private final SettableFuture<Void> bodyExit = SettableFuture.create();
    private final Set<MultiTaskContext> identityUnits = Sets.newIdentityHashSet();
    private final List<TaskBodyState> slots;

    private BodyCompletionTracker(int taskCount) {
        this.outstanding = new AtomicInteger(taskCount);
        this.slots = new ArrayList<>(taskCount);
        if (taskCount == 0) {
            bodyExit.set(null);
        }
    }

    /** Creates a tracker whose outstanding count starts at {@code taskCount}. */
    static BodyCompletionTracker create(int taskCount) {
        if (taskCount < 0) {
            throw new IllegalArgumentException("taskCount must not be negative");
        }
        return new BodyCompletionTracker(taskCount);
    }

    /** Creates a tracker for an empty submission: the signal is already complete. */
    static BodyCompletionTracker empty() {
        return new BodyCompletionTracker(0);
    }

    /**
     * Registers one prepared task and records its unit for the self-await guard. Must be called
     * exactly once per task counted at creation, before any task is submitted.
     */
    TaskBodyState register(MultiTaskContext unit) {
        identityUnits.add(unit);
        TaskBodyState slot = new TaskBodyState(this, unit.name());
        slots.add(slot);
        return slot;
    }

    /** Releases one slot; called by {@link TaskBodyState} on the winning terminal transition. */
    void release() {
        if (outstanding.decrementAndGet() == 0) {
            bodyExit.set(null);
        }
    }

    /** The completion signal itself, for composition by the owning topology. */
    ListenableFuture<Void> bodyExit() {
        return bodyExit;
    }

    /** Whether every registered body has exited (or will never be entered). */
    boolean isDone() {
        return bodyExit.isDone();
    }

    /**
     * Counts the bodies that have neither exited nor been determined to never enter, keyed by
     * task name. Used to make a close-grace timeout visible: the outstanding members are named in
     * the warning instead of the wait failing silently.
     */
    Map<String, Integer> stuckBodySummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TaskBodyState slot : slots) {
            if (slot.isOutstanding()) {
                counts.merge(slot.name(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Rejects waits from a thread currently inside a task body owned by this tracker — including a
     * nested inline call, whose unit chains back to an owned unit through structural parents. Only
     * the recognizable current-thread dependency is rejected; cross-thread circular waits and pool
     * starvation remain bounded by the wait budget.
     *
     * @throws IllegalStateException if the current thread is inside an owned task body
     */
    void checkNotSelfAwait() {
        TaskExecutionContext current = TaskExecutionContext.current();
        if (current == null || identityUnits.isEmpty()) {
            return;
        }
        MultiTaskContext unit = current.multiTaskContext();
        while (unit != null) {
            if (identityUnits.contains(unit)) {
                throw new IllegalStateException(
                        "cannot await task-body completion from within a task body of the same scope; "
                                + "use the cancellation entry to stop the scope from inside a task");
            }
            unit = unit.structuralParent();
        }
    }

    /**
     * Waits up to {@code timeout} for every registered task body to exit. Does not cancel anything
     * and does not require a prior close.
     *
     * <p>Validation order: argument checks and the self-await guard run before the interrupt check,
     * which runs before the completion check. A zero timeout performs a single check.
     *
     * @return {@code true} if all task bodies exited (or will never be entered); {@code false} if
     *     the budget elapsed first — unstarted tasks may be included in the outstanding count
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws IllegalStateException if called from within a task body owned by this tracker
     * @throws InterruptedException if the calling thread is interrupted before or during the wait;
     *     the interrupt flag is cleared per Java interruption convention
     */
    boolean awaitBodyCompletion(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        checkNotSelfAwait();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (bodyExit.isDone()) {
            return true;
        }
        return awaitNanos(saturatedNanos(timeout));
    }

    /**
     * Waits up to {@code remainingNanos} of an already-computed budget, distinguishing a successful
     * wait from an elapsed budget. A non-positive budget performs a single check.
     */
    boolean awaitBounded(long remainingNanos) throws InterruptedException {
        if (remainingNanos <= 0) {
            return bodyExit.isDone();
        }
        return awaitNanos(remainingNanos);
    }

    /**
     * Shared close sequence for {@code TaskGroup} and {@code TaskBatchResult}: reject self-awaits,
     * run the scope's cancellation entry, then wait up to the close grace for task bodies to exit.
     * A grace elapsed with bodies still running is reported with the outstanding task names —
     * a leaked body is data, not silence.
     *
     * @param cancel the scope's idempotent cancellation entry
     * @param graceNanos the close grace in nanoseconds; non-positive means cancel without waiting
     * @param scopeLabel identifies the scope in the elapsed-grace warning
     */
    static void cancelAndAwaitBodyExit(
            Runnable cancel, BodyCompletionTracker tracker, long graceNanos, String scopeLabel, Logger logger) {
        tracker.checkNotSelfAwait();
        cancel.run();
        if (graceNanos <= 0 || tracker.isDone()) {
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        boolean exited;
        try {
            exited = tracker.awaitBounded(graceNanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!exited) {
            logger.warning(scopeLabel
                    + " closed with task bodies still running after its close grace: "
                    + tracker.stuckBodySummary()
                    + ". Confirm body exit with awaitBodyCompletion before releasing resources the"
                    + " bodies use.");
        }
    }

    private boolean awaitNanos(long nanos) throws InterruptedException {
        try {
            bodyExit.get(nanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException elapsed) {
            return false;
        } catch (ExecutionException | CancellationException impossible) {
            // The signal is only ever set to null on completion; it cannot fail or be cancelled.
            throw new AssertionError("body-exit signal cannot fail", impossible);
        }
    }

    private static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}

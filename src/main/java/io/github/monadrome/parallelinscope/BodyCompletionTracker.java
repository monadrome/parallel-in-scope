package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shared task-body completion signal for one submission — a task group or a batch.
 *
 * <p>Every prepared task registers a {@link TaskBodyState} slot before any task is submitted; the
 * latch starts at the total task count (zero for an empty submission) and reaches zero only when
 * every task body has exited or has been atomically determined to never enter its body. Waiting on
 * the latch therefore cannot be defeated by tasks that start late: a {@code true} result is
 * monotonic and establishes a happens-before edge from every task body's writes to the waiting
 * thread (via {@link CountDownLatch}).
 *
 * <p>The tracker also carries the identity units needed by the self-await guard: the units of the
 * group's members (and terminal combine) or of the batch. Registration happens on the single
 * submitting thread before submission; the identity set is never mutated afterwards, and
 * publication to other threads rides the task-submission handoff.
 */
final class BodyCompletionTracker {

    private final CountDownLatch latch;
    private final Set<MultiTaskContext> identityUnits = new HashSet<>();

    private BodyCompletionTracker(int taskCount) {
        this.latch = new CountDownLatch(taskCount);
    }

    /** Creates a tracker whose latch starts at {@code taskCount}. */
    static BodyCompletionTracker create(int taskCount) {
        if (taskCount < 0) {
            throw new IllegalArgumentException("taskCount must not be negative");
        }
        return new BodyCompletionTracker(taskCount);
    }

    /** Creates a tracker for an empty submission: the latch starts at zero. */
    static BodyCompletionTracker empty() {
        return new BodyCompletionTracker(0);
    }

    /**
     * Registers one prepared task and records its unit for the self-await guard. Must be called
     * exactly once per task counted at creation, before any task is submitted.
     */
    TaskBodyState register(MultiTaskContext unit) {
        identityUnits.add(unit);
        return new TaskBodyState(this);
    }

    /** Releases one slot; called by {@link TaskBodyState} on the winning terminal transition. */
    void countDown() {
        latch.countDown();
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
        if (latch.getCount() == 0) {
            return true;
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        return latch.await(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to {@code remainingNanos} of an already-computed deadline budget. Used by {@code
     * TaskGroup.close()}, which owns interrupt handling.
     */
    void awaitBounded(long remainingNanos) throws InterruptedException {
        latch.await(remainingNanos, TimeUnit.NANOSECONDS);
    }
}

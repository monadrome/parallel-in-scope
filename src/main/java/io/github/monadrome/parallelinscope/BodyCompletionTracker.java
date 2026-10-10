package io.github.monadrome.parallelinscope;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

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
 * submitting thread before submission; the slot list and identity set are never mutated afterward,
 * and publication to other threads rides the task-submission handoff.
 */
final class BodyCompletionTracker {

    private final AtomicInteger outstanding;
    private final SettableFuture<@Nullable Void> bodyExit = SettableFuture.create();
    private final Set<MultiTaskContext> identityUnits = Sets.newIdentityHashSet();
    private final List<TaskBodyState> slots;

    /**
     * The unit of the most recent registration. Batch submissions register the same unit once
     * per task, and only the first identity-set insert carries information; registration is
     * confined to the single submitting thread, so a plain field suffices.
     */
    private @Nullable MultiTaskContext lastRegisteredUnit;

    private BodyCompletionTracker(int taskCount) {
        this.outstanding = new AtomicInteger(taskCount);
        this.slots = new ArrayList<>(taskCount);
        if (taskCount == 0) {
            complete(bodyExit);
        }
    }

    /** Creates a tracker whose outstanding count starts at {@code taskCount}. */
    static BodyCompletionTracker create(int taskCount) {
        return new BodyCompletionTracker(Validation.requireNonNegative(taskCount, "taskCount"));
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
        if (lastRegisteredUnit != unit) {
            identityUnits.add(unit);
            lastRegisteredUnit = unit;
        }
        TaskBodyState slot = new TaskBodyState(this, unit.name());
        slots.add(slot);
        return slot;
    }

    /** Releases one slot; called by {@link TaskBodyState} on the winning terminal transition. */
    void release() {
        if (outstanding.decrementAndGet() == 0) {
            complete(bodyExit);
        }
    }

    /**
     * Completes a {@code Void} signal by delegating to the already-completed void future rather
     * than {@code set(null)}: passing the null literal trips Error Prone's
     * NullArgumentForNonNullParameter when javac runs on JDK 21. The completion is equivalent —
     * the signal carries no value and completes synchronously.
     */
    static void complete(SettableFuture<@Nullable Void> signal) {
        signal.setFuture(Futures.immediateVoidFuture());
    }

    /** The completion signal itself, for composition by the owning topology. */
    ListenableFuture<@Nullable Void> bodyExit() {
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
        Validation.requireNonNegative(timeout, "timeout");
        checkNotSelfAwait();
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (bodyExit.isDone()) {
            return true;
        }
        return awaitNanos(Deadlines.saturatedNanos(timeout));
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
     * Shared close sequence for {@code TaskGroup} and {@code TaskBatch} (the {@code Par.map}
     * convergence path): reject self-awaits, run the scope's cancellation entry, then wait up to
     * the close grace for task bodies to exit. A grace elapsed with bodies still running is
     * reported with the outstanding task names — a leaked body is data, not silence.
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

    /**
     * Derives the close wait budget shared by {@code TaskGroup.close()} and {@code TaskBatch}'s
     * post-settle body-completion wait for {@code Par.map}: the configured close grace when the
     * scope declared one, otherwise what is left of its execution deadline.
     *
     * <p>The deadline lives in the token's clock domain, so the relative budget is derived from the
     * token's own clock — never from {@link System#nanoTime()}, which a manual deadline clock must
     * not be subtracted from. The derived budget is then spent as a real-time bounded wait: cleanup
     * waits measure the actual passage of time, which no virtual clock may fabricate.
     *
     * @param configured the declared close grace, or null to derive from the deadline
     * @param token the scope's cancellation token, or null when the scope carries none
     * @return the budget in nanoseconds; {@code 0} means cancel without waiting, which is what a
     *     scope with no finite deadline and no configured grace gets — there is nothing to derive a
     *     budget from. A saturated {@link Long#MAX_VALUE} is not that case: it means the derived
     *     budget is astronomical.
     */
    static long closeGraceBudgetNanos(@Nullable Duration configured, @Nullable CancellationToken token) {
        if (configured != null) {
            return Deadlines.saturatedNanos(configured);
        }
        if (token == null || token.deadlineNanos() == Long.MAX_VALUE) {
            return 0;
        }
        return Deadlines.remaining(token.deadlineNanos(), token.ticker().read());
    }

    /**
     * Waits for one future to settle inside an already-running budget, without letting the wait
     * itself become a failure.
     *
     * <p>{@code awaitBodyCompletion} needs this twice over: body exit and future settlement ride the
     * same signals, but a future's {@code get()} waiters can wake before its listeners have run, so
     * a caller that must see the published observation has to wait out that window too. The budget
     * is the caller's total, measured from {@code startNanos}, so successive calls consume one
     * shared allowance rather than each getting the full timeout.
     *
     * @param future the future to wait on
     * @param budgetNanos the caller's whole budget in nanoseconds
     * @param startNanos the {@link System#nanoTime()} reading the budget started at
     * @param cannotFail what this future is, when a failure would be an implementation defect rather
     *     than an outcome — an {@link AssertionError} names it; null accepts failure and
     *     cancellation as settled
     * @return true if the future is settled, false if the budget elapsed first
     * @throws InterruptedException if the calling thread is interrupted while waiting, which the
     *     public {@code awaitBodyCompletion} methods document and must not convert into a
     *     budget-elapsed {@code false}
     */
    static boolean awaitSettled(
            ListenableFuture<?> future, long budgetNanos, long startNanos, @Nullable String cannotFail)
            throws InterruptedException {
        if (future.isDone()) {
            return true;
        }
        long remainingNanos = budgetNanos - (System.nanoTime() - startNanos);
        if (remainingNanos <= 0) {
            return false;
        }
        try {
            future.get(remainingNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException | CancellationException settled) {
            if (cannotFail != null) {
                throw new AssertionError(cannotFail + " cannot fail", settled);
            }
            // A terminal future is all this wait needs; the outcome is the report's business.
            return true;
        } catch (TimeoutException elapsed) {
            return false;
        }
    }

    private boolean awaitNanos(long nanos) throws InterruptedException {
        try {
            bodyExit.get(nanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException elapsed) {
            return false;
        } catch (ExecutionException | CancellationException impossible) {
            // The signal only ever completes normally; it cannot fail or be cancelled.
            throw new AssertionError("body-exit signal cannot fail", impossible);
        }
    }

    /** Uses one total budget across interruptions and publication barriers, restoring the flag. */
    static boolean awaitSettledUninterruptibly(
            ListenableFuture<?> future, long budgetNanos, long startNanos, @Nullable String cannotFail) {
        if (future.isDone()) {
            return true;
        }
        long remaining = budgetNanos - (System.nanoTime() - startNanos);
        if (remaining <= 0) {
            return false;
        }
        try {
            Uninterruptibles.getUninterruptibly(future, remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException | CancellationException settled) {
            if (cannotFail != null) {
                throw new AssertionError(cannotFail + " cannot fail", settled);
            }
            return true;
        } catch (TimeoutException elapsed) {
            return false;
        }
    }

    static void warnUnfinished(String label, Map<String, Integer> unfinished, Logger logger) {
        if (unfinished.isEmpty()) return;
        try {
            logger.warning(label + " returned with task bodies still running: " + unfinished);
        } catch (Throwable ignored) {
            // A user-installed log handler must not change a completed execution's result.
        }
    }
}

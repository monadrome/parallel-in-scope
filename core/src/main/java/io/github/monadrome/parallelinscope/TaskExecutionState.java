package io.github.monadrome.parallelinscope;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Per-task execution lifecycle slot registered with a {@link ExecutionExitTracker} before submission.
 *
 * <p>State machine:
 *
 * <pre>
 * PENDING -&gt; RUNNING -&gt; EXITED
 * PENDING -&gt; SKIPPED
 * </pre>
 *
 * <p>{@code RUNNING} means the task claimed execution eligibility; it does not require the user
 * {@code Callable} to have been entered yet. The slot covers the gap between eligibility and execution
 * exit. Cancellation, rejection, and window abandonment race the task entry through the same atomic
 * state, so every transition to {@code EXITED} or {@code SKIPPED} releases the shared slot exactly
 * once. A task marked {@code SKIPPED} must never enter the user task code, even when an executor invokes
 * it later.
 */
final class TaskExecutionState {

    /** Execution lifecycle states; terminal states are EXITED and SKIPPED. */
    enum State {
        /** Registered before submission; neither claimed nor skipped yet. */
        PENDING,
        /** Execution eligibility claimed by the future's run(). */
        RUNNING,
        /** The task execution finally completed (or eligibility was released without entering it). */
        EXITED,
        /** Cancellation, rejection, or abandonment won before execution eligibility. */
        SKIPPED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final ExecutionExitTracker tracker;
    private final String name;

    /*
     * Terminal signal of this slot: set exactly once, by the winning transition to EXITED or
     * SKIPPED, before the shared tracker is released. Observation futures key on it so a waiter
     * that observes execution exit through the tracker already finds this slot terminal.
     */
    private final SettableFuture<@Nullable Void> terminal = SettableFuture.create();

    TaskExecutionState(ExecutionExitTracker tracker, String name) {
        this.tracker = tracker;
        this.name = name;
    }

    /** The task name carried for diagnostics. */
    String name() {
        return name;
    }

    /** Returns the current internal state for the task wrapper. */
    State stateValue() {
        return Verify.verifyNotNull(state.get());
    }

    /** Returns the public live status represented by this claim state. */
    TaskStatus status() {
        switch (Verify.verifyNotNull(state.get())) {
            case PENDING:
                return TaskStatus.PENDING;
            case RUNNING:
                return TaskStatus.CLAIMED;
            case EXITED:
            case SKIPPED:
                return TaskStatus.EXITED;
            default:
                throw new AssertionError("unknown task execution state");
        }
    }

    /** Returns the disposition fixed by the claim CAS when a terminal result is frozen. */
    ExecutionDisposition disposition() {
        State current = Verify.verifyNotNull(state.get());
        return current == State.RUNNING || current == State.EXITED
                ? ExecutionDisposition.CLAIMED
                : ExecutionDisposition.NOT_CLAIMED;
    }

    /** Whether the execution has neither exited nor been determined to never enter. */
    boolean isOutstanding() {
        State current = state.get();
        return current == State.PENDING || current == State.RUNNING;
    }

    /**
     * Claims execution eligibility. Returns {@code false} when cancellation or abandonment already
     * marked the task skipped; the caller must not enter the user task code in that case.
     */
    boolean claimRunning() {
        return state.compareAndSet(State.PENDING, State.RUNNING);
    }

    /**
     * Publishes execution exit, releasing the slot once. Idempotent: the normal path publishes after the
     * user task code's finally and the outer future finally retries as a fallback, so a missed inner
     * publish (for example a context-install failure) cannot leak the slot. The terminal signal is
     * set before the tracker release, so observation futures keyed on it are published before any
     * execution exit waiter can return.
     */
    void exited() {
        if (state.compareAndSet(State.RUNNING, State.EXITED)) {
            ExecutionExitTracker.complete(terminal);
            tracker.release();
        }
    }

    /** Marks the task as never entering its execution, releasing the slot once. */
    void skipped() {
        if (state.compareAndSet(State.PENDING, State.SKIPPED)) {
            ExecutionExitTracker.complete(terminal);
            tracker.release();
        }
    }

    /** Marks a still-unclaimed execution skipped and reports whether this call won the claim race. */
    boolean skipIfPending() {
        if (!state.compareAndSet(State.PENDING, State.SKIPPED)) {
            return false;
        }
        ExecutionExitTracker.complete(terminal);
        tracker.release();
        return true;
    }

    /** The terminal signal of this slot, for observation-future barriers. */
    ListenableFuture<@Nullable Void> terminal() {
        return terminal;
    }
}

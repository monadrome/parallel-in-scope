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
 * UNCLAIMED -&gt; CLAIMED -&gt; EXITED
 * UNCLAIMED -&gt; SKIPPED
 * </pre>
 *
 * <p>{@code CLAIMED} means the task claimed execution eligibility; it does not require the user
 * {@code Callable} to have been entered yet. The slot covers the gap between eligibility and execution
 * exit. Cancellation, rejection, and window abandonment race the task entry through the same atomic
 * state, so every transition to {@code EXITED} or {@code SKIPPED} releases the shared slot exactly
 * once. A task marked {@code SKIPPED} must never enter the user task code, even when an executor invokes
 * it later.
 */
final class TaskExecutionState {

    private final AtomicReference<ExecutionState> state = new AtomicReference<>(ExecutionState.UNCLAIMED);
    private final ExecutionExitTracker tracker;
    private final String name;

    private final SettableFuture<@Nullable Void> terminal = SettableFuture.create();

    TaskExecutionState(ExecutionExitTracker tracker, String name) {
        this.tracker = tracker;
        this.name = name;
    }

    /** The task name carried for diagnostics. */
    String name() {
        return name;
    }

    /** Returns the current execution state; never blocks and never changes the record. */
    ExecutionState state() {
        return Verify.verifyNotNull(state.get());
    }

    /** Returns the public live task state represented by this claim state. */
    TaskState taskState() {
        switch (Verify.verifyNotNull(state.get())) {
            case UNCLAIMED:
                return TaskState.PENDING;
            case CLAIMED:
                return TaskState.CLAIMED;
            case EXITED:
            case SKIPPED:
                return TaskState.EXITED;
            default:
                throw new AssertionError("unknown task execution state");
        }
    }

    /** Returns the disposition fixed by the claim CAS when a terminal result is frozen. */
    ExecutionDisposition disposition() {
        ExecutionState current = Verify.verifyNotNull(state.get());
        return current == ExecutionState.CLAIMED || current == ExecutionState.EXITED
                ? ExecutionDisposition.CLAIMED
                : ExecutionDisposition.NOT_CLAIMED;
    }

    /** Whether the execution has neither exited nor been determined to never enter. */
    boolean isOutstanding() {
        ExecutionState current = state.get();
        return current == ExecutionState.UNCLAIMED || current == ExecutionState.CLAIMED;
    }

    /**
     * Claims execution eligibility. Returns {@code false} when cancellation or abandonment already
     * marked the task skipped; the caller must not enter the user task code in that case.
     */
    boolean claim() {
        return state.compareAndSet(ExecutionState.UNCLAIMED, ExecutionState.CLAIMED);
    }

    /**
     * Publishes execution exit, releasing the slot once. Idempotent: the normal path publishes after the
     * user task code's finally and the outer future finally retries as a fallback, so a missed inner
     * publish (for example a context-install failure) cannot leak the slot. The terminal signal is
     * set before the tracker release, so observation futures keyed on it are published before any
     * execution exit waiter can return.
     */
    void exited() {
        if (state.compareAndSet(ExecutionState.CLAIMED, ExecutionState.EXITED)) {
            ExecutionExitTracker.complete(terminal);
            tracker.release();
        }
    }

    /** Marks the task as never entering its execution, releasing the slot once. */
    void skipped() {
        if (state.compareAndSet(ExecutionState.UNCLAIMED, ExecutionState.SKIPPED)) {
            ExecutionExitTracker.complete(terminal);
            tracker.release();
        }
    }

    /** Marks a still-unclaimed execution skipped and reports whether this call won the claim race. */
    boolean skipIfPending() {
        if (!state.compareAndSet(ExecutionState.UNCLAIMED, ExecutionState.SKIPPED)) {
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

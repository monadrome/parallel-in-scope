package io.github.monadrome.parallelinscope;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-task body lifecycle slot registered with a {@link BodyCompletionTracker} before submission.
 *
 * <p>State machine:
 *
 * <pre>
 * PENDING -&gt; RUNNING -&gt; EXITED
 * PENDING -&gt; SKIPPED
 * </pre>
 *
 * <p>{@code RUNNING} means the task claimed execution eligibility; it does not require the user
 * {@code Callable} to have been entered yet. The slot covers the gap between eligibility and body
 * exit. Cancellation, rejection, and window abandonment race the task entry through the same
 * atomic state, so every transition to {@code EXITED} or {@code SKIPPED} releases the shared slot
 * exactly once. A task marked {@code SKIPPED} must never enter the user body, even when an
 * executor invokes it later.
 */
final class TaskBodyState {

    /** Body lifecycle states; terminal states are EXITED and SKIPPED. */
    enum State {
        /** Registered before submission; neither claimed nor skipped yet. */
        PENDING,
        /** Execution eligibility claimed by the future's run(). */
        RUNNING,
        /** The task body finally completed (or eligibility was released without entering it). */
        EXITED,
        /** Cancellation, rejection, or abandonment won before execution eligibility. */
        SKIPPED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final BodyCompletionTracker tracker;

    TaskBodyState(BodyCompletionTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Claims execution eligibility. Returns {@code false} when cancellation or abandonment already
     * marked the task skipped; the caller must not enter the user body in that case.
     */
    boolean claimRunning() {
        return state.compareAndSet(State.PENDING, State.RUNNING);
    }

    /**
     * Publishes body exit, releasing the slot once. Idempotent: the normal path publishes after the
     * user body's finally and the outer future finally retries as a fallback, so a missed inner
     * publish (for example a context-install failure) cannot leak the slot.
     */
    void exited() {
        if (state.compareAndSet(State.RUNNING, State.EXITED)) {
            tracker.countDown();
        }
    }

    /** Marks the task as never entering its body, releasing the slot once. */
    void skipped() {
        if (state.compareAndSet(State.PENDING, State.SKIPPED)) {
            tracker.countDown();
        }
    }
}

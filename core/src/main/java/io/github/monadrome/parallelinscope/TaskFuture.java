package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The kernel's view of one task execution: a {@link ListenableFuture} that also reports the task's
 * name, its deadline budget, and how it ended.
 *
 * <p>Package-private, with {@link Task} as its only implementation: batch elements, group members,
 * the terminal combine, and the group completion summary are all {@code Task} views. No public
 * signature exposes it; callers receive frozen {@link ImmediateResult} and {@link TaskExitObservation}
 * data instead. Control handles that do not represent a task execution, such as a batch's
 * submission canceller, stay plain futures.
 *
 * <p>Attribution is per future. {@link #termination()} is pinned when this future settles, from
 * the cancellation token that owns this one task, and the batch freeze consumes that pin; a read
 * that wins the race against the pin's publication recomputes the same attribution from the
 * settled future and the token. The enclosing scope's frozen attribution — each member's {@link
 * TaskExitObservation}, a batch element's {@link ImmediateResult} — is the authority; it can carry
 * a finer cause than the token chain of a single future reveals at settle time.
 *
 * <p>The metadata queries {@link #taskName()}, {@link #termination()}, {@link #executionState()},
 * {@link #deadlineNanos()}, {@link #remaining()}, and {@link #failure()} are safe on any thread: they do not block, throw, or
 * change the future. Inherited future operations retain their ordinary blocking and cancellation
 * semantics. {@link #termination()} and {@link #failure()} report a terminal value once the future
 * is done and never fall back to pending afterward. Reads taken once the enclosing scope has
 * converged agree with each other.
 *
 * @param <T> the task result type
 */
interface TaskFuture<T> extends ListenableFuture<T> {

    /** Returns the task name: the batch name, the declared member or combine name, or the group name. */
    String taskName();

    /**
     * Returns the absolute deadline in {@link System#nanoTime()} units, or {@link Long#MAX_VALUE}
     * for none.
     */
    long deadlineNanos();

    /** Returns the remaining budget before {@link #deadlineNanos()}; never negative. */
    Duration remaining();

    /**
     * Returns the failure behind a {@link FailureCause.Kind#USER_CODE_FAILURE} or {@code
     * SUBMISSION_REJECTED} termination; null otherwise.
     */
    @Nullable
    Throwable failure();

    /**
     * Returns the immutable logical termination once this future is done, or {@code null} while it
     * is still pending. The value is frozen at logical termination and does not wait for physical
     * execution exit.
     */
    @Nullable
    TaskTermination<T> termination();

    /**
     * Returns a live read of this task's execution line, or {@code null} when the view has no
     * direct execution (a derived view, the group completion summary, or a test-created task).
     *
     * <p>The value comes from the task's execution record alone and moves only along {@code
     * UNCLAIMED -> CLAIMED -> EXITED} or {@code UNCLAIMED -> SKIPPED}; {@code EXITED} or {@code
     * SKIPPED} means the execution is over, the same fact the enclosing scope's execution-exit
     * wait counts. Pair it with {@link #termination()} for the result line: a non-null termination
     * with {@code CLAIMED} means the result is settled while the user code still unwinds. The two
     * reads are separate, so a pair taken without the scope converged may straddle a transition.
     * The call never waits, throws, or changes the future.
     */
    @Nullable
    ExecutionState executionState();

    /**
     * Returns the observation future of this task: a {@code ListenableFuture} of the task's final
     * immutable {@link TaskExitObservation} snapshot — identity, submit/start/end times, queue wait,
     * termination, failure, and on success the result.
     *
     * <p>The snapshot is published only after this future is terminal <em>and</em> the task execution
     * has exited (or was determined to never run), so the recorded end time is always final: a
     * callback consuming it never hits the window in which the future settled before the user
     * {@code finally}. A task that never started reports zero start/end times and durations with
     * its real termination — submission failure and pre-execution cancellation included. Once the
     * enclosing scope has completed (every future terminal and every task execution exited), this future
     * is guaranteed to be done; {@link TaskBatch#awaitExecutionExit(Duration)} returning {@code
     * true} implies the data is already available.
     *
     * <p>User task failure, cancellation, and rejection all complete this future successfully with
     * the real termination data. The future never returns {@code null}, never requires polling, and
     * ignores cancellation ({@code cancel(...)} returns {@code false} and propagates nowhere).
     * Compose it with {@code Futures.addCallback} on an executor of the caller's choice for
     * immediate reaction; the attribution here is the task's own direct one — a group member's
     * authoritative post-convergence attribution remains {@link TaskGroupResult#members()}.
     */
    ListenableFuture<TaskExitObservation<T>> completionFuture();
}

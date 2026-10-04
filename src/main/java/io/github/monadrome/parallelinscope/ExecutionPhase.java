package io.github.monadrome.parallelinscope;

/**
 * A best-effort execution phase queried from a prepared future.
 *
 * <p>These values describe which lifecycle transition won inside the future. They do not prove
 * executor queue membership or that user code has started running.
 */
enum ExecutionPhase {
    /** No call to {@code run()} or {@code cancel()} has claimed the future yet. */
    SUBMITTED,
    /** The future's {@code run()} method claimed execution. */
    RUNNING,
    /** Cancellation won before {@code run()} claimed execution. */
    CANCELLED_BEFORE_RUN,
    /** The future is cancelled and {@code run()} owns execution cleanup. */
    CANCEL_REQUESTED_RUNNING,
    /** Execution cleanup finished, or submission failure prevented execution. */
    TERMINAL
}

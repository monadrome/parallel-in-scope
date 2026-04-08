package io.github.huatalk.vformation;

import com.google.common.base.Throwables;

/**
 * Cooperative cancellation checkpoint utility.
 * <p>
 * Since Java's {@link Thread#interrupt()} only affects blocking operations,
 * this class provides explicit checkpoint methods that CPU-bound code can call
 * to check whether it should abort early.
 * <p>
 * Two types of cancellation exceptions:
 * <ul>
 *   <li>{@link FatCancellationException}: preserves full stack trace, useful for debugging</li>
 *   <li>{@link LeanCancellationException}: skips stack trace, better for production performance</li>
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class Checkpoints {

    private Checkpoints() {
    }

    /**
     * Standard checkpoint: checks CancellationToken state from TaskScopeTl.
     * Throws cancellation exception if the task has been canceled.
     *
     * @param taskName the task name for validation
     * @param lean     true to throw LeanCancellationException (no stack trace),
     *                 false to throw FatCancellationException (full stack trace)
     */
    public static void checkpoint(String taskName, boolean lean) {
        ParOptions options = TaskScopeTl.getParallelOptions();
        if (taskName == null || options == null
                || !taskName.equals(options.getTaskName())) {
            return;
        }
        CancellationToken cancelToken = TaskScopeTl.getCancellationToken();
        if (cancelToken != null) {
            if (cancelToken.getState().shouldInterruptCurrentThread()) {
                throw lean
                        ? new LeanCancellationException("Cancel during running")
                        : new FatCancellationException("Cancel during running");
            }
        }
    }

    /**
     * Raw checkpoint: only checks thread interrupt flag.
     * For scenarios not using CancellationToken.
     */
    public static void rawCheckpoint() {
        if (Thread.interrupted()) {
            throw new FatCancellationException("Cancel during running by interruption");
        }
    }

    /**
     * Cancellation-aware sleep. Converts {@link InterruptedException} into
     * a {@link FatCancellationException} so that task cancellation via
     * thread interrupt is treated uniformly as a cooperative cancellation.
     *
     * @param millis sleep duration in milliseconds
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FatCancellationException("Cancel during sleep by interruption");
        }
    }

    /**
     * Re-throws if the throwable is a cancellation exception.
     *
     * @param ex the exception to check
     */
    public static void propagateCancellation(Throwable ex) {
        Throwables.throwIfInstanceOf(ex, FatCancellationException.class);
        Throwables.throwIfInstanceOf(ex, LeanCancellationException.class);
    }
}

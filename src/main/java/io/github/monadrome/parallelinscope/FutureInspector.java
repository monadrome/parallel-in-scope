package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.Futures;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Java 8-compatible utility for inspecting {@link Future} state.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class FutureInspector {

    private FutureInspector() {}

    /**
     * Returns the exception from a failed future.
     *
     * <p>The read is independent of the calling thread's interrupt status: a caller carrying the
     * interrupt flag inspects a done future exactly as an uninterrupted caller does, and keeps the
     * flag.
     *
     * @param future the future to inspect (must be done and failed)
     * @return the cause exception
     * @throws IllegalStateException if the future is not in a failed state
     */
    public static Throwable exceptionNow(Future<?> future) {
        if (!future.isDone()) {
            throw new IllegalStateException("task has not completed");
        }
        if (future.isCancelled()) {
            throw new IllegalStateException("task was cancelled");
        }
        try {
            // The future is done here, so read it with Futures.getDone: unlike get(), it never throws
            // InterruptedException, and a calling thread that happens to carry the interrupt flag can
            // no longer turn a failed task into an inspection error. It preserves that flag.
            Futures.getDone(future);
            throw new IllegalStateException("task completed with a result");
        } catch (ExecutionException e) {
            // an ExecutionException raised by the read always wraps the task's failure
            return Objects.requireNonNull(e.getCause());
        }
    }
}

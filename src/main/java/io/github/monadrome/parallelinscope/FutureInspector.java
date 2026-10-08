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
     * flag. The done future is read with {@link Futures#getDone}, which never throws {@link
     * InterruptedException}, unlike {@link Future#get()}; the {@link ExecutionException} that read
     * raises always wraps the task's failure.
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
            Futures.getDone(future);
            throw new IllegalStateException("task completed with a result");
        } catch (ExecutionException e) {
            return Objects.requireNonNull(e.getCause());
        }
    }
}

package io.github.monadrome.parallelinscope;

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
     * @param future the future to inspect (must be done and failed)
     * @return the cause exception
     * @throws IllegalStateException if the future is not in a failed state
     */
    public static Throwable exceptionNow(Future<?> future) {
        if (!future.isDone()) {
            throw new IllegalStateException("task has not completed");
        }
        if (future.isCancelled()) {
            throw new IllegalStateException("task was canceled");
        }
        try {
            future.get();
            throw new IllegalStateException("task completed with a result");
        } catch (ExecutionException e) {
            // an ExecutionException raised by Future.get() always wraps the task's failure
            return Objects.requireNonNull(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while inspecting future", e);
        }
    }
}

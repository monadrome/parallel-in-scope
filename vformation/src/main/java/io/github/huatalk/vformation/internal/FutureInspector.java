package io.github.huatalk.vformation.internal;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Java 8-compatible utility for inspecting {@link Future} state.
 * <p>
 * Replaces the dependency on cffu2 {@code CompletableFutureUtils} which requires Java 19+.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class FutureInspector {

    private FutureInspector() {
    }

    /**
     * Returns the current state of the given future.
     *
     * @param future the future to inspect
     * @return the current {@link FutureState}
     */
    public static FutureState state(Future<?> future) {
        if (!future.isDone()) {
            return FutureState.RUNNING;
        }
        if (future.isCancelled()) {
            return FutureState.CANCELLED;
        }
        try {
            future.get();
            return FutureState.SUCCESS;
        } catch (ExecutionException e) {
            return FutureState.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FutureState.FAILED;
        }
    }

    /**
     * Returns the exception from a failed future.
     *
     * @param future the future to inspect (must be done and failed)
     * @return the cause exception
     * @throws IllegalStateException if the future is not in FAILED state
     */
    public static Throwable exceptionNow(Future<?> future) {
        if (!future.isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        if (future.isCancelled()) {
            throw new IllegalStateException("Task was cancelled");
        }
        try {
            future.get();
            throw new IllegalStateException("Task completed with a result");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while inspecting future", e);
        }
    }
}

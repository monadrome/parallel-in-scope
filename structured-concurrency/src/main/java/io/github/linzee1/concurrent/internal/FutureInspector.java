package io.github.linzee1.concurrent.internal;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Future state inspection utility.
 * <p>
 * Provides state inspection for {@link Future} instances without blocking,
 * replacing the cffu2 dependency for lightweight open-source usage.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public final class FutureInspector {

    /**
     * Possible states of a Future.
     */
    public enum State {
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELED
    }

    private FutureInspector() {
    }

    /**
     * Inspects the state of a future without blocking.
     *
     * @param future the future to inspect
     * @return the current state
     */
    public static State state(Future<?> future) {
        if (future.isCancelled()) {
            return State.CANCELED;
        }
        if (!future.isDone()) {
            return State.RUNNING;
        }
        try {
            future.get(0, TimeUnit.NANOSECONDS);
            return State.SUCCESS;
        } catch (CancellationException e) {
            return State.CANCELED;
        } catch (ExecutionException e) {
            return State.FAILED;
        } catch (TimeoutException e) {
            return State.RUNNING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return State.RUNNING;
        }
    }

    /**
     * Extracts the exception from a failed future without blocking.
     * Returns null if the future is not in FAILED state.
     *
     * @param future the future to inspect
     * @return the exception, or null
     */
    public static Throwable exceptionNow(Future<?> future) {
        if (!future.isDone() || future.isCancelled()) {
            return null;
        }
        try {
            future.get(0, TimeUnit.NANOSECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return null;
        }
    }
}

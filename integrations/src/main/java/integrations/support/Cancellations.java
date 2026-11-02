package integrations.support;

import io.github.monadrome.parallelinscope.Checkpoints;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Tells a downstream failure apart from the library cancelling the body that observed it.
 *
 * <p>Fail-fast, a batch or group deadline, and request cancellation interrupt running bodies. The
 * body then fails with a cancellation-shaped exception that says nothing about the health of the
 * downstream it was calling. Resilience and flow-control libraries must not count such a failure:
 * a circuit breaker would open because siblings were cancelled, a retry would spend the deadline
 * that just expired, and Sentinel's exception statistics would trip a degrade rule.
 */
public final class Cancellations {

    private Cancellations() {}

    /**
     * Returns whether {@code failure} was caused by cancellation rather than by the downstream.
     *
     * <p>The answer is true when the current scoped task is already cancelled or past its deadline,
     * when the current thread's interrupt flag is set, or when the cause chain contains a {@link
     * CancellationException}, an {@link InterruptedException}, a {@link ClosedByInterruptException},
     * or an {@link InterruptedIOException} other than a {@link SocketTimeoutException}. A socket
     * timeout is a downstream failure and stays counted. The check reads state only: it never
     * clears the interrupt flag and never cancels anything. Call it on the thread that ran the
     * failed call, as the resilience decorators in this module do.
     *
     * @param failure the failure the downstream call ended with
     * @return true when the failure must not be counted against the downstream
     */
    public static boolean causedByCancellation(Throwable failure) {
        if (scopeCancelled() || Thread.currentThread().isInterrupted()) {
            return true;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof CancellationException
                    || cause instanceof InterruptedException
                    || cause instanceof ClosedByInterruptException
                    || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the current scoped task is cancelled or past its deadline.
     *
     * <p>Outside a task run by this library the answer is false. The query does not touch the
     * interrupt flag.
     *
     * @return true when the enclosing batch or group has cancelled this body
     */
    public static boolean scopeCancelled() {
        try {
            Checkpoints.checkpoint();
            return false;
        } catch (CancellationException cancelled) {
            return true;
        }
    }
}

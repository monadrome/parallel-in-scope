package io.github.huatalk.parallelinscope.control;

import javax.annotation.Nullable;

/**
 * Thrown by a {@link LifecycleBlockingQueue} blocking operation that cannot complete because the
 * queue's lifecycle service is shutting down or has already stopped.
 *
 * <p>This exception is unchecked because it replaces {@link InterruptedException} on methods whose
 * signatures are fixed by {@link java.util.concurrent.BlockingQueue}. It is raised in exactly two
 * situations:
 *
 * <ul>
 *   <li>the call was already blocked inside the backing queue when shutdown selected it and
 *       interrupted it; the consumed {@link InterruptedException} is retained as the {@linkplain
 *       #getCause() cause};
 *   <li>the call arrived after admission was closed and therefore never reached the backing queue;
 *       the cause is then {@code null}.
 * </ul>
 *
 * <p>A thread that receives this exception does <b>not</b> have its interrupt status set: the
 * interrupt was issued by the lifecycle itself, scoped to the failed call, and is consumed before
 * the exception is thrown. Interrupts that do not originate from shutdown are never converted; they
 * propagate as {@link InterruptedException} with the interrupt semantics of the backing queue.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see LifecycleBlockingQueue
 */
public class QueueShutdownException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a call that was rejected before reaching the backing queue.
     *
     * @param message the detail message
     */
    public QueueShutdownException(String message) {
        super(message);
    }

    /**
     * Creates an exception for a blocked call that shutdown interrupted.
     *
     * @param message the detail message
     * @param cause the {@link InterruptedException} raised by the backing queue, or {@code null} if
     *     the call never reached it
     */
    public QueueShutdownException(String message, @Nullable InterruptedException cause) {
        super(message, cause);
    }
}

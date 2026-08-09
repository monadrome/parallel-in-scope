package io.github.huatalk.parallelinscope.control;

import javax.annotation.Nullable;

/**
 * Thrown when a lifecycle-aware queue rejects an operation because shutdown has begun or completed.
 *
 * <p>This exception is unchecked because {@link java.util.concurrent.BlockingQueue} method
 * signatures cannot express lifecycle rejection. It may be raised either by a blocked operation that
 * was released by lifecycle shutdown or by a new operation rejected after admission closed. Concrete
 * queue implementations determine how blocked callers are released: {@link LifecycleQueueV2} uses
 * Monitor guards and does not interrupt threads.
 *
 * <p>External interrupts remain {@link InterruptedException}; lifecycle rejection is never used to
 * disguise an external interrupt.
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
     * Creates an exception with an implementation-specific blocked-call cause.
     *
     * @param message the detail message
     * @param cause the interrupted wait failure from an implementation that uses interruption, or
     *     {@code null}
     */
    public QueueShutdownException(String message, @Nullable InterruptedException cause) {
        super(message, cause);
    }
}

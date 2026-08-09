package io.github.huatalk.parallelinscope.control;

/**
 * Thrown when a lifecycle-aware queue rejects an operation because shutdown has begun or completed.
 *
 * <p>This exception is unchecked because {@link java.util.concurrent.BlockingQueue} method
 * signatures cannot express lifecycle rejection. It may be raised either by a blocked operation that
 * was released by lifecycle shutdown or by a new operation rejected after admission closed.
 * {@link StoppableBlockingQueue} uses Monitor guards to release blocked callers and does not interrupt
 * threads.
 *
 * <p>External interrupts remain {@link InterruptedException}; lifecycle rejection is never used to
 * disguise an external interrupt.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see StoppableBlockingQueue
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

}

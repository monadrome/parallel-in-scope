package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.Executor;

/**
 * Delivery of the runtime's diagnostic log messages on a runtime-owned executor. Every diagnostic
 * log station of one runtime delivers through its single instance.
 *
 * <p>An admitting thread never calls a logging handler, which may throw, block, or re-enter. A
 * failing handler drops the message, and so does a delivery the executor cannot start: a rejection
 * after the runtime's services shut down, or a failure creating the executor's thread, which runs
 * on the admitting thread and can be an {@link OutOfMemoryError}. Every {@link Throwable} is dropped
 * on both paths: diagnostics are best effort and must not change what the caller observes. Messages
 * are delivered independently, so they may appear out of admission order.
 */
final class Announcements {
    private final Executor delivery;

    /**
     * Creates the delivery for one runtime.
     *
     * @param delivery the runtime-owned executor that runs each message
     */
    Announcements(Executor delivery) {
        this.delivery = checkNotNull(delivery, "delivery cannot be null");
    }

    /** Hands {@code message} to the delivery executor; never throws, whatever the message or executor does. */
    void deliver(Runnable message) {
        try {
            delivery.execute(() -> {
                try {
                    message.run();
                } catch (Throwable handlerFailure) {
                    return;
                }
            });
        } catch (Throwable deliveryNotStarted) {
            return;
        }
    }
}

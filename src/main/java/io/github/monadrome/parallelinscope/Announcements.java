package io.github.monadrome.parallelinscope;

import java.util.concurrent.Executor;

/**
 * Delivery of the runtime's diagnostic log messages on a runtime-owned executor.
 *
 * <p>An admitting thread never calls a logging handler, which may throw, block, or re-enter. A
 * failing handler drops the message, and so does a delivery the executor cannot start: a rejection
 * after the runtime's services shut down, or a failure creating the executor's thread, which runs
 * on the admitting thread. Diagnostics are best effort and must not change what the caller
 * observes; messages are delivered independently, so they may appear out of admission order.
 */
final class Announcements {
    private Announcements() {}

    static void deliver(Executor announcements, Runnable message) {
        try {
            announcements.execute(() -> {
                try {
                    message.run();
                } catch (RuntimeException handlerFailure) {
                    return;
                }
            });
        } catch (RuntimeException deliveryNotStarted) {
            return;
        }
    }
}

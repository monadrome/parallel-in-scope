package io.github.monadrome.parallelinscope;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Delivery of the runtime's diagnostic log messages on a runtime-owned executor.
 *
 * <p>An admitting thread never calls a logging handler, which may throw, block, or re-enter. A
 * failing handler and a rejected delivery after the runtime's services shut down both drop the
 * message: diagnostics are best effort and must not change what the caller observes.
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
        } catch (RejectedExecutionException servicesShutDown) {
            return;
        }
    }
}

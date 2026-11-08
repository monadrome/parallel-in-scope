package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.alibaba.ttl.TransmittableThreadLocal.Transmitter;
import java.util.concurrent.Executor;

/**
 * Executors that preserve the calling thread's registered context while retaining direct execution.
 *
 * <p>{@link #directExecutor()} captures the TTL context when it is created. Each command then runs
 * synchronously on the thread that calls {@link Executor#execute(Runnable)}, with the captured context
 * installed for the command and the caller's context restored afterwards. Create one executor for the
 * context that belongs to a listener registration; do not share one across unrelated requests.
 *
 * <p>This executor is intended for application callbacks such as Guava {@code ListenableFuture}
 * listeners. A wrapper that captures inside {@code execute} captures the completion thread instead of
 * the listener-registration thread and therefore cannot provide the same guarantee.
 *
 * @since 0.5.0
 */
public final class ContextExecutors {

    private ContextExecutors() {}

    /**
     * Creates a direct executor bound to the current thread's TTL context.
     *
     * @return an executor that always runs commands inline and restores the caller's context
     */
    public static Executor directExecutor() {
        return new CapturedDirectExecutor(Transmitter.capture());
    }

    private static final class CapturedDirectExecutor implements Executor {
        private final Object captured;

        private CapturedDirectExecutor(Object captured) {
            this.captured = captured;
        }

        @Override
        public void execute(Runnable command) {
            checkNotNull(command, "command cannot be null");
            Object previous = Transmitter.replay(captured);
            try {
                command.run();
            } finally {
                Transmitter.restore(previous);
            }
        }
    }
}

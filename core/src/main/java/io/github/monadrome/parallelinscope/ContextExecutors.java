package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.alibaba.ttl.TransmittableThreadLocal.Transmitter;
import java.util.concurrent.Executor;

/**
 * Executors that preserve a selected TTL context while retaining direct execution.
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
 * <p>Capture/replay/restore restores the TTL bindings, not the deep state of values held by those
 * bindings. A mutable value returned by {@code TransmittableThreadLocal.get()} can still be shared and
 * changed by a command when the TTL copier keeps the same reference. Use immutable or copy-on-write
 * context values, or configure a copier that makes an appropriate deep copy when isolation is required.
 *
 * <p>Creating one executor per request context prevents unrelated requests from sharing a captured
 * snapshot, but it does not turn mutable values into independent copies.
 *
 * <p>{@link #lazyDirectExecutor()} provides a different contract: it captures the current TTL
 * context on every {@link Executor#execute(Runnable)} call. Use it when the call site's context is
 * authoritative, or when a configured TTL copier should create a fresh per-command value. It does
 * not propagate the context of the thread that created the executor, and copied values are not
 * guaranteed to retain object identity.
 *
 * @since 0.5.0
 */
public final class ContextExecutors {

    private ContextExecutors() {}

    /**
     * Creates a direct executor bound to the current thread's TTL context.
     *
     * @return an executor that always runs commands inline and restores the caller's context
     * @since 0.5.0
     */
    public static Executor directExecutor() {
        return new CapturedDirectExecutor(Transmitter.capture());
    }

    /**
     * Creates a direct executor that captures the calling thread's TTL context for each command.
     *
     * <p>Unlike {@link #directExecutor()}, this executor does not retain a creation-time snapshot.
     * The context at each {@link Executor#execute(Runnable)} call is copied, replayed around the
     * command, and restored afterwards. A configured TTL copier therefore runs once per command;
     * use immutable or properly isolated values when commands need independent mutable state.
     *
     * @return an executor that captures the execute caller's context for every command
     * @since 0.5.0
     */
    public static Executor lazyDirectExecutor() {
        return new LazyDirectExecutor();
    }

    private static final class CapturedDirectExecutor implements Executor {
        private final Object captured;

        private CapturedDirectExecutor(Object captured) {
            this.captured = captured;
        }

        @Override
        public void execute(Runnable command) {
            checkNotNull(command, "command cannot be null");
            runWithCaptured(captured, command);
        }

        @Override
        public String toString() {
            return "ContextExecutors.directExecutor()";
        }
    }

    private static final class LazyDirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            checkNotNull(command, "command cannot be null");
            runWithCaptured(Transmitter.capture(), command);
        }

        @Override
        public String toString() {
            return "ContextExecutors.lazyDirectExecutor()";
        }
    }

    /**
     * Runs a command with a captured TTL context and restores the context that was active on entry.
     *
     * <p>The command runs synchronously on the calling thread. If the command throws, its failure
     * is propagated after the entry context has been restored.
     *
     * @param captured the non-null context to replay
     * @param command the non-null command to run
     */
    private static void runWithCaptured(Object captured, Runnable command) {
        Object previous = Transmitter.replay(captured);
        try {
            command.run();
        } finally {
            Transmitter.restore(previous);
        }
    }
}

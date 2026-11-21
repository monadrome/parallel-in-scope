package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.Executor;

/**
 * Direct executors that preserve application context using TTL by default or an explicit provider.
 *
 * <p>{@link #directContextExecutor()} captures the TTL context at each {@link Executor#execute(Runnable)} call.
 * Each command then runs synchronously on the calling thread, with that call's context installed for
 * the command and the caller's context restored afterwards. This is the general-purpose direct
 * executor and can be reused by unrelated requests.
 *
 * <p>For callbacks whose context belongs to registration rather than execution, use
 * {@link #registrationContextExecutor()}. A Guava {@code ListenableFuture} listener is one such
 * case: its executor may be called later by the future's completion thread.
 *
 * <p>Capture/replay/restore restores the TTL bindings, not the deep state of values held by those
 * bindings. A mutable value returned by {@code TransmittableThreadLocal.get()} can still be shared and
 * changed by a command when the TTL copier keeps the same reference. Use immutable or copy-on-write
 * context values, or configure a copier that makes an appropriate deep copy when isolation is required.
 *
 * <p>{@link #registrationContextExecutor()} captures once when it is created and replays that fixed
 * snapshot for every command. It is intended for a listener or callback group that belongs to one
 * registration context; create a separate instance for each such context.
 */
public final class ContextExecutors {

    private ContextExecutors() {}

    /**
     * Returns a reusable direct executor that captures the calling context for each command.
     *
     * <p>This is the default context-propagating direct executor. It does not retain the context of
     * the thread that created it. A configured TTL copier runs once per command, and the command's
     * entry context is restored afterwards.
     *
     * @return an executor that always runs commands inline and restores the caller's context
     */
    public static Executor directContextExecutor() {
        return ContextPropagatingDirectExecutor.INSTANCE;
    }

    /**
     * Returns an inline executor that captures the supplied provider at each execution call.
     *
     * @param propagation provider shared safely across unrelated execution callers
     * @return an inline executor restoring each caller's entering context
     * @throws NullPointerException if the provider is null
     */
    public static Executor directContextExecutor(ContextPropagation propagation) {
        checkNotNull(propagation, "context propagation cannot be null");
        return command -> runWithPropagation(propagation, command);
    }

    /**
     * Creates a direct executor bound to the current thread's TTL context.
     *
     * <p>Each command replays this creation-time snapshot and restores the execution thread's entry
     * context afterwards. Use this when commands belong to the context that registered or created
     * the executor, such as a group of Future listeners. Do not share one instance across unrelated
     * request contexts.
     *
     * @return an executor that replays the creation-time context for every command
     */
    public static Executor registrationContextExecutor() {
        return registrationContextExecutor(ContextPropagation.ttl());
    }

    /**
     * Captures a provider now and reuses that snapshot for inline callbacks, including concurrent calls.
     *
     * @param propagation provider whose registration-time context belongs to these callbacks
     * @return an executor retaining this capture until the executor becomes unreachable
     * @throws NullPointerException if the provider or its capture is null
     */
    public static Executor registrationContextExecutor(ContextPropagation propagation) {
        return new CapturedDirectExecutor(
                ContextPropagationSupport.capture(checkNotNull(propagation, "context propagation cannot be null")));
    }

    /**
     * Stateless direct executor whose context snapshot belongs to each command, making one shared
     * instance safe for unrelated callers.
     */
    private enum ContextPropagatingDirectExecutor implements Executor {
        INSTANCE;

        @Override
        public void execute(Runnable command) {
            runWithPropagation(ContextPropagation.ttl(), command);
        }

        @Override
        public String toString() {
            return "ContextExecutors.directContextExecutor()";
        }
    }

    private static final class CapturedDirectExecutor implements Executor {
        private final ContextSnapshot captured;

        private CapturedDirectExecutor(ContextSnapshot captured) {
            this.captured = captured;
        }

        @Override
        public void execute(Runnable command) {
            checkNotNull(command, "command cannot be null");
            ContextPropagationSupport.run(captured, command);
        }

        @Override
        public String toString() {
            return "ContextExecutors.registrationContextExecutor()";
        }
    }

    /**
     * Validates the command before capturing invocation-time application context, then runs inline.
     *
     * <p>The command runs synchronously on the calling thread. If the command throws, its failure
     * is propagated after the entry context has been restored.
     *
     * @param propagation the non-null provider to capture for this invocation
     * @param command the non-null command to run
     */
    private static void runWithPropagation(ContextPropagation propagation, Runnable command) {
        checkNotNull(command, "command cannot be null");
        ContextPropagationSupport.run(ContextPropagationSupport.capture(propagation), command);
    }
}

package io.github.huatalk.parallelinscope.context;

import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Task-level thread-local storage using regular {@link ThreadLocal} (NOT TransmittableThreadLocal).
 * <p>
 * Stores {@link CancellationToken} and {@link ParOptions} for the currently executing task
 * on the current thread. Intentionally non-propagating -- this data belongs only to the current
 * task node and should not be inherited by child threads.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class TaskScopeTl {

    private static final ThreadLocal<CancellationToken> CANCELLATION_TOKEN_TL = new ThreadLocal<>();
    private static final ThreadLocal<ParOptions> PARALLEL_OPTIONS_TL = new ThreadLocal<>();

    private TaskScopeTl() {
    }

    /**
     * Returns the current task's cancellation token.
     *
     * @return the current token, or {@code null} outside a scoped task
     */
    public static @Nullable CancellationToken getCancellationToken() {
        return CANCELLATION_TOKEN_TL.get();
    }

    /**
     * Stores the current task's cancellation token.
     *
     * @param token the token to store
     */
    public static void setCancellationToken(CancellationToken token) {
        CANCELLATION_TOKEN_TL.set(token);
    }

    /**
     * Returns the current task's parallel options.
     *
     * @return the current options, or {@code null} outside a scoped task
     */
    public static @Nullable ParOptions getParallelOptions() {
        return PARALLEL_OPTIONS_TL.get();
    }

    /**
     * Stores the current task's parallel options.
     *
     * @param options the options to store
     */
    public static void setParallelOptions(ParOptions options) {
        PARALLEL_OPTIONS_TL.set(options);
    }

    /**
     * Initializes both cancellation token and parallel options for the current task.
     *
     * @param token   the current cancellation token
     * @param options the current parallel options
     */
    public static void init(CancellationToken token, ParOptions options) {
        CANCELLATION_TOKEN_TL.set(token);
        PARALLEL_OPTIONS_TL.set(options);
    }

    /**
     * Cleans up both ThreadLocal entries. Must be called in finally blocks.
     */
    public static void remove() {
        CANCELLATION_TOKEN_TL.remove();
        PARALLEL_OPTIONS_TL.remove();
    }
}

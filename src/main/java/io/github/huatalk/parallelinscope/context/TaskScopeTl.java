package io.github.huatalk.parallelinscope.context;

import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Thread-local installation point for one task's explicit batch context. */
public final class TaskScopeTl {
    private static final ThreadLocal<CancellationToken> CANCELLATION_TOKEN_TL = new ThreadLocal<>();
    private static final ThreadLocal<BatchExecutionContext> BATCH_CONTEXT_TL = new ThreadLocal<>();

    private TaskScopeTl() { }

    /** Returns the current batch context, or null outside a GlobalPar task. */
    public static @Nullable BatchExecutionContext getBatchExecutionContext() {
        return BATCH_CONTEXT_TL.get();
    }

    /** Returns the current batch cancellation token, or null outside a task. */
    public static @Nullable CancellationToken getCancellationToken() {
        BatchExecutionContext context = BATCH_CONTEXT_TL.get();
        return context == null ? CANCELLATION_TOKEN_TL.get() : context.cancellationToken();
    }

    /** Installs a batch context for one scoped task. */
    public static void setBatchExecutionContext(BatchExecutionContext context) {
        BATCH_CONTEXT_TL.set(context);
        CANCELLATION_TOKEN_TL.set(context.cancellationToken());
    }

    /** Removes all task-local state. */
    public static void remove() {
        CANCELLATION_TOKEN_TL.remove();
        BATCH_CONTEXT_TL.remove();
    }

    /** Restores a previously captured task scope. */
    public static void restore(@Nullable CancellationToken token,
                               @Nullable BatchExecutionContext context) {
        if (token == null) CANCELLATION_TOKEN_TL.remove(); else CANCELLATION_TOKEN_TL.set(token);
        if (context == null) BATCH_CONTEXT_TL.remove(); else BATCH_CONTEXT_TL.set(context);
    }
}

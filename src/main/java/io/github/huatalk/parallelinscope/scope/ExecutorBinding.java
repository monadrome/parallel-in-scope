package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.Objects;

/** Stable internal association between one executor registration and its capabilities. */
final class ExecutorBinding {

    private final String name;
    private final ListeningExecutorService executor;
    private final boolean deadlockProne;
    private final Runnable queuedCancellationObserver;

    /** Creates one immutable registration binding. */
    ExecutorBinding(
            String name,
            ListeningExecutorService executor,
            boolean deadlockProne,
            Runnable queuedCancellationObserver) {
        this.name = Objects.requireNonNull(name);
        this.executor = Objects.requireNonNull(executor);
        this.deadlockProne = deadlockProne;
        this.queuedCancellationObserver = Objects.requireNonNull(queuedCancellationObserver);
    }

    /** Returns the stable logical name. */
    String getName() {
        return name;
    }

    /** Returns the executor used for task submission. */
    ListeningExecutorService getExecutor() {
        return executor;
    }

    /** Returns the registration-time deadlock-risk snapshot. */
    boolean isDeadlockProne() {
        return deadlockProne;
    }

    /** Returns the queued-cancellation callback bound to the registered executor. */
    Runnable getQueuedCancellationObserver() {
        return queuedCancellationObserver;
    }
}

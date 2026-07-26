package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.ListeningExecutorService;
import io.github.huatalk.parallelinscope.spi.ExecutionPhase;

import java.util.Objects;
import java.util.function.Consumer;

/** Stable internal association between one executor registration and its capabilities. */
final class ExecutorBinding {

    private final String name;
    private final ListeningExecutorService executor;
    private final boolean deadlockProne;
    private final Consumer<? super ExecutionPhase> phaseObserver;

    /** Creates one immutable registration binding. */
    ExecutorBinding(
            String name,
            ListeningExecutorService executor,
            boolean deadlockProne,
            Consumer<? super ExecutionPhase> phaseObserver) {
        this.name = Objects.requireNonNull(name);
        this.executor = Objects.requireNonNull(executor);
        this.deadlockProne = deadlockProne;
        this.phaseObserver = Objects.requireNonNull(phaseObserver);
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

    /** Returns the execution-phase observer bound to the registered executor. */
    Consumer<? super ExecutionPhase> getPhaseObserver() {
        return phaseObserver;
    }
}

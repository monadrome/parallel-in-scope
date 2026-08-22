package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import io.github.huatalk.parallelinscope.spi.ExecutionPhase;

/** Runtime capability record for one supplied executor. Internal to the GlobalPar package. */
final class ExecutorRuntime {
    private final ExecutorService suppliedExecutor;
    private final ListeningExecutorService submissionExecutor;
    private final boolean adapter;
    private final ExecutorIdentity identity;
    private final BlockingRisk blockingRisk;
    private volatile Consumer<? super ExecutionPhase> phaseObserver = phase -> { };

    ExecutorRuntime(ExecutorService suppliedExecutor) {
        this(suppliedExecutor, detectRisk(suppliedExecutor));
    }

    ExecutorRuntime(ExecutorService suppliedExecutor, BlockingRisk blockingRisk) {
        this.suppliedExecutor = Objects.requireNonNull(suppliedExecutor);
        this.identity = new ExecutorIdentity(suppliedExecutor);
        this.blockingRisk = Objects.requireNonNull(blockingRisk);
        if (suppliedExecutor instanceof ListeningExecutorService) {
            this.submissionExecutor = (ListeningExecutorService) suppliedExecutor;
            this.adapter = false;
        } else {
            this.submissionExecutor = MoreExecutors.listeningDecorator(suppliedExecutor);
            this.adapter = true;
        }
    }

    ExecutorService suppliedExecutor() { return suppliedExecutor; }
    ListeningExecutorService submissionExecutor() { return submissionExecutor; }
    boolean submissionExecutorIsAdapter() { return adapter; }
    ExecutorIdentity identity() { return identity; }
    BlockingRisk blockingRisk() { return blockingRisk; }
    Consumer<? super ExecutionPhase> phaseObserver() { return phaseObserver; }
    void setPhaseObserver(Consumer<? super ExecutionPhase> observer) {
        this.phaseObserver = Objects.requireNonNull(observer);
    }

    private static BlockingRisk detectRisk(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor) {
            return BlockingRisk.BOUNDED_PLATFORM_POOL;
        }
        return BlockingRisk.UNKNOWN;
    }
}

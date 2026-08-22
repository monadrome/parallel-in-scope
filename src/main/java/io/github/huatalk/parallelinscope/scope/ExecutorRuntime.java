package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import io.github.huatalk.parallelinscope.spi.ExecutionPhase;

/** Runtime capability record for one supplied executor. */
public final class ExecutorRuntime {
    private final ExecutorService suppliedExecutor;
    private final ListeningExecutorService submissionExecutor;
    private final boolean adapter;
    private final ExecutorIdentity identity;
    private final BlockingRisk blockingRisk;
    private volatile Consumer<? super ExecutionPhase> phaseObserver = phase -> { };

    public ExecutorRuntime(ExecutorService suppliedExecutor) {
        this(suppliedExecutor, detectRisk(suppliedExecutor));
    }

    public ExecutorRuntime(ExecutorService suppliedExecutor, BlockingRisk blockingRisk) {
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

    public ExecutorService suppliedExecutor() { return suppliedExecutor; }
    public ListeningExecutorService submissionExecutor() { return submissionExecutor; }
    public boolean submissionExecutorIsAdapter() { return adapter; }
    public ExecutorIdentity identity() { return identity; }
    public BlockingRisk blockingRisk() { return blockingRisk; }
    public Consumer<? super ExecutionPhase> phaseObserver() { return phaseObserver; }
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

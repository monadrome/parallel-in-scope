package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/**
 * Runtime capability record for one supplied executor. Internal to the {@code ParRuntime} package.
 *
 * <p>The supplied executor is the resource identity used for queue inspection, purge, and blocking
 * risk. The submission executor is either that same object or a Guava listening adapter used only
 * to obtain {@code ListenableFuture}s; the adapter must never be mistaken for the physical pool.
 *
 * <p>Both capability facts below are read once from the supplied object's structure, never from its
 * class name or from runtime statistics: a fact that cannot be read stays {@link
 * BlockingRisk#UNKNOWN} rather than being guessed.
 */
final class ExecutorRuntime {
    private final ExecutorService suppliedExecutor;
    private final ListeningExecutorService submissionExecutor;
    private final boolean adapter;
    private final ExecutorIdentity identity;
    private final BlockingRisk blockingRisk;
    private final boolean starvationProne;
    private volatile Consumer<? super ExecutionPhase> phaseObserver = phase -> {};

    ExecutorRuntime(ExecutorService suppliedExecutor) {
        this(suppliedExecutor, detectRisk(suppliedExecutor));
    }

    ExecutorRuntime(ExecutorService suppliedExecutor, BlockingRisk blockingRisk) {
        this.suppliedExecutor = Objects.requireNonNull(suppliedExecutor);
        this.identity = new ExecutorIdentity(suppliedExecutor);
        this.blockingRisk = Objects.requireNonNull(blockingRisk);
        this.starvationProne = detectStarvationProne(suppliedExecutor);
        if (suppliedExecutor instanceof ListeningExecutorService) {
            this.submissionExecutor = (ListeningExecutorService) suppliedExecutor;
            this.adapter = false;
        } else {
            this.submissionExecutor = MoreExecutors.listeningDecorator(suppliedExecutor);
            this.adapter = true;
        }
    }

    ExecutorService suppliedExecutor() {
        return suppliedExecutor;
    }

    ListeningExecutorService submissionExecutor() {
        return submissionExecutor;
    }

    boolean submissionExecutorIsAdapter() {
        return adapter;
    }

    ExecutorIdentity identity() {
        return identity;
    }

    BlockingRisk blockingRisk() {
        return blockingRisk;
    }

    /**
     * Whether a task body on this executor can be starved of a thread while it blocks on a child
     * task's result.
     *
     * <p>The deciding fact is the thread upper bound, not queue boundedness: a fixed pool with an
     * unbounded queue is as deadlock-prone as a bounded one, because its worker count is capped by
     * {@code maximumPoolSize} and every worker can block on a child that has no thread left to run
     * on. An executor that can always add a thread is not starvation-prone, whatever its queue
     * holds; that risk is unbounded growth rather than deadlock.
     *
     * <p>{@code false} for executors whose thread bound cannot be read — the same structural
     * honesty {@link #blockingRisk()} applies: no fact, no claim.
     */
    boolean starvationProne() {
        return starvationProne;
    }

    /**
     * Whether {@code rejectEnqueue} can actually take effect for this executor, which requires the
     * supplied executor to be a {@link ThreadPoolExecutor} whose work queue is a {@link
     * SmartBlockingQueue} — the only implementation that reads the flag.
     */
    boolean rejectEnqueueEffective() {
        return suppliedExecutor instanceof ThreadPoolExecutor
                && ((ThreadPoolExecutor) suppliedExecutor).getQueue() instanceof SmartBlockingQueue;
    }

    Consumer<? super ExecutionPhase> phaseObserver() {
        return phaseObserver;
    }

    void setPhaseObserver(Consumer<? super ExecutionPhase> observer) {
        this.phaseObserver = Objects.requireNonNull(observer);
    }

    private static BlockingRisk detectRisk(ExecutorService executor) {
        if (!(executor instanceof ThreadPoolExecutor)) {
            return BlockingRisk.UNKNOWN;
        }
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        boolean boundedQueue = !hasUnboundedQueue(pool.getQueue());
        boolean boundedThreads = pool.getMaximumPoolSize() != Integer.MAX_VALUE;
        return boundedQueue && boundedThreads ? BlockingRisk.BOUNDED_PLATFORM_POOL : BlockingRisk.UNBOUNDED;
    }

    /**
     * Reads the starvation fact behind {@link #starvationProne()}: a {@link ThreadPoolExecutor}
     * whose thread count is capped cannot grow a worker for a child task when every worker is
     * blocked on one.
     */
    private static boolean detectStarvationProne(ExecutorService executor) {
        return executor instanceof ThreadPoolExecutor
                && ((ThreadPoolExecutor) executor).getMaximumPoolSize() != Integer.MAX_VALUE;
    }

    /**
     * Reads a queue's configured capacity from the {@link BlockingQueue} contract alone: {@code
     * remainingCapacity()} is documented as the number of elements the queue can ideally accept
     * without blocking, or {@link Integer#MAX_VALUE} when it has no intrinsic limit, and the JDK
     * implementations — as well as this library's own {@code VariableLinkedBlockingQueue} — compute
     * it as {@code capacity - size}. The sum is therefore the capacity, without requiring a
     * concrete queue type; the {@code long} keeps a queue that reports {@link Integer#MAX_VALUE}
     * regardless of its size from overflowing.
     */
    private static boolean hasUnboundedQueue(BlockingQueue<Runnable> queue) {
        return (long) queue.size() + queue.remainingCapacity() >= Integer.MAX_VALUE;
    }
}

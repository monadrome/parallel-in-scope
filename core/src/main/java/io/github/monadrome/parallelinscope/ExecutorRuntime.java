package io.github.monadrome.parallelinscope;

import com.alibaba.ttl.TtlUnwrap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Runtime capability record for one supplied executor. Internal to the {@code ParRuntime} package.
 *
 * <p>Capability facts come from the introspectable object's structure, not from class-name guesses
 * or runtime load statistics. A fact that cannot be read yields a conservative answer.
 */
final class ExecutorRuntime {

    private final ExecutorService suppliedExecutor;
    private final ExecutorService introspectableExecutor;
    private final ListeningExecutorService submissionExecutor;
    private final boolean adapter;
    private final ExecutorIdentity identity;
    private final boolean starvationProne;

    ExecutorRuntime(ExecutorService suppliedExecutor) {
        this.suppliedExecutor = Objects.requireNonNull(suppliedExecutor);
        this.introspectableExecutor = TtlUnwrap.unwrap(suppliedExecutor);
        this.identity = new ExecutorIdentity(suppliedExecutor);
        this.starvationProne = detectStarvationProne(introspectableExecutor);
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

    /**
     * The object the capability facts are read from: the supplied executor, or the physical
     * executor behind a TTL wrapper. Callers that inspect the pool's structure MUST use this rather
     * than {@link #suppliedExecutor()}, whose type may be a wrapper that hides every fact.
     */
    ExecutorService introspectableExecutor() {
        return introspectableExecutor;
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

    /**
     * Whether a task execution on this executor can be starved of a thread while it blocks on a child
     * task's result.
     *
     * <p>{@code false} for executors whose queue cannot be read: no fact, no claim.
     */
    boolean starvationProne() {
        return starvationProne;
    }

    /**
     * Whether a requested {@code rejectEnqueue} can actually take effect for this executor, which
     * requires the introspectable executor to be a {@link ThreadPoolExecutor} whose work queue is a
     * {@link SubmissionAwareBlockingQueue}, the only implementation that reads it.
     */
    boolean enqueueRefusalEffective() {
        return introspectableExecutor instanceof ThreadPoolExecutor
                && ((ThreadPoolExecutor) introspectableExecutor).getQueue() instanceof SubmissionAwareBlockingQueue;
    }

    /**
     * Returns whether the executor visibly buffers work behind busy workers.
     */
    private static boolean detectStarvationProne(ExecutorService executor) {
        return executor instanceof ThreadPoolExecutor && queueCapacity(((ThreadPoolExecutor) executor).getQueue()) > 0;
    }

    /**
     * Whether the pool behind this executor is a {@link ThreadPoolExecutor}, read through {@link
     * #introspectableExecutor()} so a TTL-wrapped pool still counts.
     */
    boolean threadPoolBacked() {
        return introspectableExecutor instanceof ThreadPoolExecutor;
    }

    /**
     * Returns the queue's reported total capacity without integer overflow.
     */
    private static long queueCapacity(BlockingQueue<Runnable> queue) {
        return (long) queue.size() + queue.remainingCapacity();
    }
}

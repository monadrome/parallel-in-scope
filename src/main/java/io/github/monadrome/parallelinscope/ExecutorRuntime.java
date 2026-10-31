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
    /*
     * The supplied executor is the resource identity and the submission target. Capability facts —
     * queue inspection and starvation proneness — are read from the introspectableExecutor()
     * instead, which looks through a TTL executor wrapper: a wrapper hides the physical pool, and a
     * registration whose every fact silently downgrades to "unknown" is worse than one diagnostic.
     * The identity deliberately does not follow that unwrapping (see ExecutorIdentity), so a
     * physical pool registered twice — bare and wrapped — stays two graph entries. The submission
     * executor is either the supplied object or a Guava listening adapter used only to obtain
     * ListenableFutures; the adapter must never be mistaken for the physical pool.
     */

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
     * Whether a task body on this executor can be starved of a thread while it blocks on a child
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
     * {@link SmartBlockingQueue}, the only implementation that reads it.
     */
    boolean enqueueRefusalEffective() {
        return introspectableExecutor instanceof ThreadPoolExecutor
                && ((ThreadPoolExecutor) introspectableExecutor).getQueue() instanceof SmartBlockingQueue;
    }

    /**
     * Returns whether the executor visibly buffers work behind busy workers.
     */
    private static boolean detectStarvationProne(ExecutorService executor) {
        /*
         * ThreadPoolExecutor offers to its queue before growing past corePoolSize. A buffering
         * queue can park a child behind the worker synchronously waiting for it; a zero-capacity
         * handoff queue instead forces growth or explicit rejection. This structural test does not
         * claim that the pool is currently starved.
         */

        return executor instanceof ThreadPoolExecutor && queueCapacity(((ThreadPoolExecutor) executor).getQueue()) > 0;
    }

    /**
     * Whether the pool behind this executor is a {@link ThreadPoolExecutor}, read through {@link
     * #introspectableExecutor()} so a TTL-wrapped pool still counts.
     */
    boolean threadPoolBacked() {
        /*
         * Named for what it tests rather than for the conclusion it supports, because the two are
         * not equivalent in either direction: a TPE configured with AbortPolicy answers true and
         * never runs a task inline, while a foreign executor that always does answers false. What
         * the type does establish is an implication callers can rely on — a TPE dispatches to a
         * worker or throws, so the only path by which a submitted task reaches the caller's own
         * stack is its java.util.concurrent.RejectedExecutionHandler. For such a pool, "the body
         * ran on the submitting thread" and "a rejection handler ran it" are therefore the same
         * fact.
         *
         * That is what makes it sound to treat inline execution as a violation for a TPE and not
         * for anything else. An executor that always runs inline — MoreExecutors.directExecutor()
         * and friends — is expressing its whole contract rather than a symptom of load, and it was
         * chosen explicitly by whoever registered it; acting on that signal would break a
         * documented allowance (see CombineBody) on the strength of an observation that carries no
         * information.
         */

        return introspectableExecutor instanceof ThreadPoolExecutor;
    }

    /**
     * Returns the queue's reported total capacity without integer overflow.
     */
    private static long queueCapacity(BlockingQueue<Runnable> queue) {
        /*
         * BlockingQueue.remainingCapacity reports ideally available slots, or Integer.MAX_VALUE
         * when there is no intrinsic bound. JDK queues and VariableLinkedBlockingQueue compute it
         * as capacity - size; summing with size recovers the reported capacity. The long sum also
         * avoids overflow for a queue that always reports Integer.MAX_VALUE regardless of its size.
         */

        return (long) queue.size() + queue.remainingCapacity();
    }
}

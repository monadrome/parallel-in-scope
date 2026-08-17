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
 * <p>The supplied executor is the resource identity and the submission target. Capability facts —
 * queue inspection and starvation proneness — are read from the {@linkplain
 * #introspectableExecutor() introspectable executor} instead, which looks through a TTL executor
 * wrapper: a wrapper hides the physical pool, and a registration whose every fact silently
 * downgrades to "unknown" is worse than one diagnostic. The identity deliberately does not follow
 * that unwrapping (see {@link ExecutorIdentity}), so a physical pool registered twice — bare and
 * wrapped — stays two graph entries. The submission executor is either the supplied object or a
 * Guava listening adapter used only to obtain {@code ListenableFuture}s; the adapter must never be
 * mistaken for the physical pool.
 *
 * <p>Every capability fact below is read once from the introspectable object's structure, never from
 * its class name or from runtime statistics: a fact that cannot be read yields the conservative
 * answer rather than a guess.
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
     * The object the capability facts are read from: the supplied executor, or the physical executor
     * behind a TTL wrapper. Callers that inspect the pool's structure MUST use this rather than
     * {@link #suppliedExecutor()}, whose type may be a wrapper that hides every fact.
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
     * <p>The deciding fact is where a submission goes when every worker is busy, which {@link
     * ThreadPoolExecutor} decides by offering to the queue before it grows beyond {@code
     * corePoolSize}. A buffering queue accepts the offer, so the child parks behind the blocked
     * worker and the pool never gets the chance to add one — whatever {@code maximumPoolSize}
     * says, and whether or not that bound is finite. A zero-capacity handoff queue is the
     * opposite: it refuses, which forces a new worker or an explicit rejection, so the child
     * either runs or fails visibly instead of stalling.
     *
     * <p>{@code false} for executors whose queue cannot be read: no fact, no claim.
     */
    boolean starvationProne() {
        return starvationProne;
    }

    /**
     * Whether {@code rejectEnqueue} can actually take effect for this executor, which requires the
     * introspectable executor to be a {@link ThreadPoolExecutor} whose work queue is a {@link
     * SmartBlockingQueue} — the only implementation that reads the flag.
     */
    boolean rejectEnqueueEffective() {
        return introspectableExecutor instanceof ThreadPoolExecutor
                && ((ThreadPoolExecutor) introspectableExecutor).getQueue() instanceof SmartBlockingQueue;
    }

    /**
     * Reads the starvation fact behind {@link #starvationProne()}: a {@link ThreadPoolExecutor}
     * whose work queue buffers a submission while every worker is busy parks that submission
     * behind a blocked worker. {@code execute} offers to the queue before it grows past {@code
     * corePoolSize}, so the pool is only forced to add a worker — or to reject — when the offer
     * fails, which is exactly what a zero-capacity handoff queue guarantees.
     */
    private static boolean detectStarvationProne(ExecutorService executor) {
        return executor instanceof ThreadPoolExecutor && queueCapacity(((ThreadPoolExecutor) executor).getQueue()) > 0;
    }

    /**
     * Whether the pool behind this executor is a {@link ThreadPoolExecutor}, read through {@link
     * #introspectableExecutor()} so a TTL-wrapped pool still counts.
     *
     * <p>Named for what it tests rather than for the conclusion it supports, because the two are not
     * equivalent in either direction: a TPE configured with {@code AbortPolicy} answers true and
     * never runs a task inline, while a foreign executor that always does answers false. What the
     * type does establish is an implication callers can rely on — a TPE dispatches to a worker or
     * throws, so the only path by which a submitted task reaches the caller's own stack is its {@link
     * java.util.concurrent.RejectedExecutionHandler}. For such a pool, "the body ran on the
     * submitting thread" and "a rejection handler ran it" are therefore the same fact.
     *
     * <p>That is what makes it sound to treat inline execution as a violation for a TPE and not for
     * anything else. An executor that always runs inline — {@code MoreExecutors.directExecutor()} and
     * friends — is expressing its whole contract rather than a symptom of load, and it was chosen
     * explicitly by whoever registered it; acting on that signal would break a documented allowance
     * (see {@code CombineBody}) on the strength of an observation that carries no information.
     */
    boolean threadPoolBacked() {
        return introspectableExecutor instanceof ThreadPoolExecutor;
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
    private static long queueCapacity(BlockingQueue<Runnable> queue) {
        return (long) queue.size() + queue.remainingCapacity();
    }
}

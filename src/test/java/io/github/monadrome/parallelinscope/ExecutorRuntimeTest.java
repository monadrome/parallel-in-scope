package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.ttl.TtlUnwrap;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Structural risk classification of one supplied executor: what {@link ExecutorRuntime} can read
 * from the executor's own shape, and what it refuses to claim.
 */
class ExecutorRuntimeTest {

    @Test
    void boundedQueueAndBoundedThreadsClassifyAsBoundedPlatformPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
        try {
            ExecutorRuntime runtime = new ExecutorRuntime(pool);

            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.BOUNDED_PLATFORM_POOL);
            assertThat(runtime.starvationProne()).isTrue();
            assertThat(runtime.rejectEnqueueEffective()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fixedPoolWithUnboundedQueueIsUnboundedAndStillStarvationProne() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ExecutorRuntime runtime = new ExecutorRuntime(executor);

            // The queue absorbs without limit, so the classification names the resource fact...
            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.UNBOUNDED);
            // ...while the buffering queue is what decides whether a child task can starve.
            assertThat(runtime.starvationProne()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cachedPoolWithHandoffQueueIsUnboundedAndCanAlwaysAddAThread() {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            ExecutorRuntime runtime = new ExecutorRuntime(executor);

            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.UNBOUNDED);
            assertThat(runtime.starvationProne()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void poolWithSmartBlockingQueueIsBoundedAndHonoursRejectEnqueue() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 4, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(8));
        try {
            ExecutorRuntime runtime = new ExecutorRuntime(pool);

            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.BOUNDED_PLATFORM_POOL);
            assertThat(runtime.starvationProne()).isTrue();
            assertThat(runtime.rejectEnqueueEffective()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUnboundedThreadBoundDoesNotSaveABufferingQueue() {
        // execute() offers to the queue before it grows past corePoolSize, so the buffering queue
        // parks the child behind the blocked worker and maximumPoolSize is never consulted: one
        // core thread with an unbounded thread bound still starves.
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(1, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            ExecutorRuntime runtime = new ExecutorRuntime(pool);

            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.UNBOUNDED);
            assertThat(runtime.starvationProne()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aHandoffQueueForcesANewWorkerEvenWhenTheThreadBoundIsSmall() {
        // The opposite of the case above: the offer is refused, so the pool grows to its bound
        // instead of parking the child, and a blocked worker cannot starve it.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 4, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        try {
            ExecutorRuntime runtime = new ExecutorRuntime(pool);

            // A handoff queue is a bounded queue, so the resource shape is still a bounded pool.
            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.BOUNDED_PLATFORM_POOL);
            assertThat(runtime.starvationProne()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void executorWithoutReadableStructureStaysUnknownAndClaimsNoStarvation() {
        ExecutorService single = Executors.newSingleThreadExecutor();
        ExecutorService decorated = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));
        try {
            ExecutorRuntime singleRuntime = new ExecutorRuntime(single);
            ExecutorRuntime decoratedRuntime = new ExecutorRuntime(decorated);

            assertThat(singleRuntime.blockingRisk()).isEqualTo(BlockingRisk.UNKNOWN);
            assertThat(singleRuntime.starvationProne()).isFalse();
            assertThat(singleRuntime.rejectEnqueueEffective()).isFalse();
            // A user-supplied decorator hides the physical pool, so the queue is unreadable too.
            assertThat(decoratedRuntime.blockingRisk()).isEqualTo(BlockingRisk.UNKNOWN);
            assertThat(decoratedRuntime.starvationProne()).isFalse();
            assertThat(decoratedRuntime.rejectEnqueueEffective()).isFalse();
        } finally {
            single.shutdownNow();
            decorated.shutdownNow();
        }
    }

    @Test
    void ttlWrappedPoolIsReadThroughWhileItsIdentityStaysOnTheRegisteredObject() {
        // A TTL wrapper is not a ThreadPoolExecutor, so reading the registered object would make
        // every structural fact unknown and would hide the pool from purge. TtlUnwrap is the public
        // way through. Identity deliberately does not follow, so one physical pool registered both
        // bare and wrapped stays two entries in the executor graph.
        ThreadPoolExecutor physical =
                new ThreadPoolExecutor(1, 4, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(8));
        ExecutorService wrapped = TtlExecutors.getTtlExecutorService(physical);
        try {
            assertThat(TtlUnwrap.isWrapper(wrapped)).isTrue();

            ExecutorRuntime runtime = new ExecutorRuntime(wrapped);

            assertThat(runtime.suppliedExecutor()).isSameAs(wrapped);
            assertThat(runtime.introspectableExecutor()).isSameAs(physical);
            assertThat(runtime.blockingRisk()).isEqualTo(BlockingRisk.BOUNDED_PLATFORM_POOL);
            assertThat(runtime.starvationProne()).isTrue();
            assertThat(runtime.rejectEnqueueEffective()).isTrue();
            assertThat(runtime.identity()).isNotEqualTo(new ExecutorRuntime(physical).identity());
        } finally {
            physical.shutdownNow();
        }
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.threadpool.TtlExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Builder validation matrix for {@link ParRuntime} policies. */
class ParRuntimePoliciesTest {

    @Test
    void registerRejectsDuplicatesAndDefaultParMustBeRegistered() {
        assertThatThrownBy(() -> ParRuntime.builder()
                        .register(ParId.of("same"), Executors.newSingleThreadExecutor())
                        .register(ParId.of("same"), Executors.newSingleThreadExecutor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate Par id")
                .hasMessageContaining("'same'");
        assertThatThrownBy(() ->
                        ParRuntime.builder().defaultPar(ParId.of("absent")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default Par is not registered");
    }

    @Test
    void deadlockPolicyBuilderExposesFluentSelfReturns() {
        ParRuntimeDeadlockPolicy.Builder deadlock = ParRuntimeDeadlockPolicy.builder();
        assertThat(deadlock.enabled(true)).isSameAs(deadlock);
        assertThat(deadlock.build().enabled()).isTrue();
    }

    @Test
    void discardingRejectionPoliciesFailTheBuildNamingTheParAndThePolicy() {
        // DiscardPolicy and DiscardOldestPolicy accept a task and drop it: execute() neither runs it
        // nor throws, so the prepared future stays pending and every batch or group submitted to
        // that pool waits forever. Refuse the pool at the composition root instead.
        ExecutorService discard = poolWith(new ThreadPoolExecutor.DiscardPolicy());
        ExecutorService discardOldest = poolWith(new ThreadPoolExecutor.DiscardOldestPolicy());
        try {
            assertThatThrownBy(() -> ParRuntime.builder()
                            .register(ParId.of("orders"), discard)
                            .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("orders")
                    .hasMessageContaining("DiscardPolicy")
                    .hasMessageContaining("AbortPolicy");
            assertThatThrownBy(() -> ParRuntime.builder()
                            .register(ParId.of("orders"), discardOldest)
                            .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DiscardOldestPolicy");
        } finally {
            discard.shutdownNow();
            discardOldest.shutdownNow();
        }
    }

    @Test
    void terminatingRejectionPoliciesStillBuild() {
        // AbortPolicy surfaces RejectedExecutionException (which the kernel already turns into
        // SUBMISSION_FAILURE) and CallerRunsPolicy runs the task inline, so both keep the "every
        // prepared future reaches a terminal state" guarantee and stay registrable.
        ExecutorService abort = poolWith(new ThreadPoolExecutor.AbortPolicy());
        ExecutorService callerRuns = poolWith(new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            ParRuntime runtime = ParRuntime.builder()
                    .register(ParId.of("abort"), abort)
                    .register(ParId.of("caller-runs"), callerRuns)
                    .build();
            try {
                assertThat(runtime.pars()).containsOnlyKeys(ParId.of("abort"), ParId.of("caller-runs"));
            } finally {
                runtime.close();
            }
        } finally {
            abort.shutdownNow();
            callerRuns.shutdownNow();
        }
    }

    @Test
    void undetectableDiscardingShapesAreNotGuarded() {
        // Pins the boundary of the guard: it reads the handler of a directly registered
        // ThreadPoolExecutor once, at build time. A custom handler that discards silently is
        // indistinguishable from a well-behaved one, and it is not the guard's job to guess -- see
        // design/extension-and-wrapping.md L8 for the scope and the U2 obligation it leaves to the
        // caller. This test documents that gap rather than pretending it is closed.
        RejectedExecutionHandler silentDrop = (task, executor) -> {};
        ExecutorService pool = poolWith(silentDrop);
        try {
            ParRuntime runtime =
                    ParRuntime.builder().register(ParId.of("orders"), pool).build();
            runtime.close();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTtlWrappedDiscardingPoolIsStillRefusedInsteadOfSlidingPastTheGuard() {
        // The guard reads the structure of the object it can see, and a TTL wrapper is not a
        // ThreadPoolExecutor. Before the wrapper was looked through, registering this pool skipped
        // the guard entirely and the silent-drop configuration the guard exists to refuse built
        // successfully, surfacing only as a late timeout with no task body ever entered.
        ThreadPoolExecutor physical = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        ExecutorService wrapped = TtlExecutors.getTtlExecutorService(physical);
        try {
            assertThatThrownBy(() -> ParRuntime.builder()
                            .register(ParId.of("orders"), wrapped)
                            .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("orders")
                    .hasMessageContaining("DiscardPolicy");
        } finally {
            physical.shutdownNow();
        }
    }

    @Test
    void aTtlWrappedPoolIsAdvisedAboutTheDoubleCaptureRatherThanCalledOpaque() {
        // The wrapper is looked through, so the generic "cannot see through" diagnostic would be
        // false here. What stays true is the second TTL capture the executor boundary adds on top
        // of the one prepare already performs -- that is the caller's executor, not ours to unwrap.
        ThreadPoolExecutor physical =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ExecutorService wrapped = TtlExecutors.getTtlExecutorService(physical);
        Logger runtimeLogger = Logger.getLogger(ParRuntime.class.getName());
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        runtimeLogger.addHandler(capture);
        try {
            ParRuntime runtime =
                    ParRuntime.builder().register(ParId.of("orders"), wrapped).build();
            runtime.close();

            assertThat(records).hasSize(1);
            assertThat(records.get(0).getMessage())
                    .contains("orders")
                    .contains("TTL wrapper")
                    .contains("twice")
                    .doesNotContain("cannot see through");
        } finally {
            runtimeLogger.removeHandler(capture);
            physical.shutdownNow();
        }
    }

    /** A one-thread pool with the given rejection handler, shaped so the guard can inspect it. */
    private static ThreadPoolExecutor poolWith(RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), handler);
    }
}

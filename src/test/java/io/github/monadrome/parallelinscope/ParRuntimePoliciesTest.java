package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.threadpool.TtlExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Builder validation matrix for {@link ParRuntime} policies and its task-listener overrides. */
class ParRuntimePoliciesTest {

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void parTaskListenerRejectsBlankNamesAndNullListenersAndAppendsPerPar() {
        ParRuntime.Builder builder = ParRuntime.builder();
        TaskListener first = event -> {};
        TaskListener second = event -> {};

        assertThat(builder.parTaskListener(ParId.of("orders"), first)).isSameAs(builder);

        assertThatThrownBy(() -> builder.parTaskListener(null, first)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.parTaskListener(ParId.of(""), first))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParRuntime.builder().parTaskListener(ParId.of("fresh"), null))
                .isInstanceOf(NullPointerException.class);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("billing"), executor)
                .taskListener(first)
                .parTaskListener(ParId.of("billing"), first)
                .parTaskListener(ParId.of("billing"), second)
                .build();
        try {
            assertThat(global.closed()).isFalse();
            assertThat(global.taskListeners()).containsExactly(first);
            assertThat(global.taskListenersFor(ParId.of("billing"))).containsExactly(first, second);
            assertThatThrownBy(
                            () -> global.taskListenersFor(ParId.of("billing")).clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void taskListenerOverridesWithoutRegisteredNameFailBuildAndRegisterRejectsDuplicates() {
        assertThatThrownBy(() -> ParRuntime.builder()
                        .parTaskListener(ParId.of("ghost"), event -> {})
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
        assertThatThrownBy(() -> ParRuntime.builder()
                        .register(ParId.of("same"), java.util.concurrent.Executors.newSingleThreadExecutor())
                        .register(ParId.of("same"), java.util.concurrent.Executors.newSingleThreadExecutor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate Par id")
                .hasMessageContaining("'same'");
        assertThatThrownBy(() ->
                        ParRuntime.builder().defaultPar(ParId.of("absent")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default Par is not registered");
    }

    @Test
    void purgePolicyThresholdsAcceptBoundsOnly() {
        ParRuntimePurgePolicy policy = ParRuntimePurgePolicy.builder()
                .queuePressureThreshold(1.0)
                .canceledTaskRatioThreshold(1.0)
                .build();
        assertThat(policy).isNotNull();

        ParRuntimePurgePolicy.Builder builder = ParRuntimePurgePolicy.builder();
        assertThatThrownBy(() -> builder.queuePressureThreshold(1.0000001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.queuePressureThreshold(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.queuePressureThreshold(-0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.queuePressureThreshold(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.canceledTaskRatioThreshold(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.canceledTaskRatioThreshold(0d)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.canceledTaskRatioThreshold(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purgePolicyGettersExposeConfiguredValuesExactly() {
        ParRuntimePurgePolicy defaults = ParRuntimePurgePolicy.builder().build();
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.queuePressureThreshold()).isEqualTo(0.80d);
        assertThat(defaults.canceledTaskRatioThreshold()).isEqualTo(0.05d);

        ParRuntimePurgePolicy custom = ParRuntimePurgePolicy.builder()
                .enabled(true)
                .queuePressureThreshold(0.5d)
                .canceledTaskRatioThreshold(0.25d)
                .build();
        assertThat(custom.enabled()).isTrue();
        assertThat(custom.queuePressureThreshold()).isEqualTo(0.5d);
        assertThat(custom.canceledTaskRatioThreshold()).isEqualTo(0.25d);
    }

    @Test
    void purgeThresholdsAndEnablementAreAdjustableAtRuntime() {
        ParRuntime runtime = ParRuntime.builder()
                .purgePolicy(ParRuntimePurgePolicy.builder().enabled(true).build())
                .build();
        try {
            assertThat(runtime.purgeEnabled()).isTrue();
            assertThat(runtime.queuePressureThreshold()).isEqualTo(0.80d);
            assertThat(runtime.canceledTaskRatioThreshold()).isEqualTo(0.05d);

            runtime.adjustPurgeThresholds(0.5d, 0.25d);
            assertThat(runtime.queuePressureThreshold()).isEqualTo(0.5d);
            assertThat(runtime.canceledTaskRatioThreshold()).isEqualTo(0.25d);

            // The build-time policy is a snapshot; runtime adjustment does not rewrite it.
            assertThat(runtime.purgePolicy().queuePressureThreshold()).isEqualTo(0.80d);

            // Validation happens before either threshold changes.
            assertThatThrownBy(() -> runtime.adjustPurgeThresholds(0.0d, 0.25d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runtime.adjustPurgeThresholds(0.5d, Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(runtime.queuePressureThreshold()).isEqualTo(0.5d);
            assertThat(runtime.canceledTaskRatioThreshold()).isEqualTo(0.25d);

            runtime.setPurgeEnabled(false);
            assertThat(runtime.purgeEnabled()).isFalse();
            runtime.setPurgeEnabled(true);
            assertThat(runtime.purgeEnabled()).isTrue();
        } finally {
            runtime.close();
        }
    }

    @Test
    void deadlockPolicyAndTaskListenerBuildersExposeFluentSelfReturns() {
        ParRuntimeDeadlockPolicy.Builder deadlock = ParRuntimeDeadlockPolicy.builder();
        assertThat(deadlock.enabled(true)).isSameAs(deadlock);
        assertThat(deadlock.build().enabled()).isTrue();

        ParRuntime.Builder listeners = ParRuntime.builder();
        TaskListener listener = event -> {};
        assertThat(listeners.taskListener(listener)).isSameAs(listeners);
        ParRuntime global = listeners.build();
        try {
            assertThat(global.taskListeners()).containsExactly(listener);
        } finally {
            global.close();
        }
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

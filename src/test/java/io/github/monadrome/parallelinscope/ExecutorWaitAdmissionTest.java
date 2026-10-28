package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The executor waits every public admission records into its runtime's wait graph: nested map,
 * mapAndCombine, and group calls; foreign runtimes and pools that cannot starve; and that the cycle
 * warning never runs on the admitting thread.
 *
 * <p>Every buffering pool has spare threads, so a structural self-loop still runs to completion while
 * the graph reports it.
 */
class ExecutorWaitAdmissionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final List<ExecutorService> pools = new ArrayList<>();
    private final List<ParRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void shutDown() {
        for (ParRuntime runtime : runtimes) {
            runtime.close();
        }
        for (ExecutorService pool : pools) {
            pool.shutdownNow();
        }
    }

    private ExecutorService buffering() {
        ExecutorService pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(64));
        pools.add(pool);
        return pool;
    }

    private ExecutorService handoff() {
        ExecutorService pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        pools.add(pool);
        return pool;
    }

    private ParRuntime runtime(Object... idsAndPools) {
        ParRuntime.Builder builder = ParRuntime.builder();
        for (int index = 0; index < idsAndPools.length; index += 2) {
            builder.register(ParId.of((String) idsAndPools[index]), (ExecutorService) idsAndPools[index + 1]);
        }
        ParRuntime runtime = builder.build();
        runtimes.add(runtime);
        return runtime;
    }

    private static List<Set<ParId>> ids(String... names) {
        List<Set<ParId>> ids = new ArrayList<>();
        for (String name : names) {
            ids.add(ImmutableSet.of(ParId.of(name)));
        }
        return ids;
    }

    private static List<Integer> nested(ParRuntime runtime, String outer, String inner) {
        return runtime.par(ParId.of(outer))
                .map(
                        Arrays.asList(1, 2),
                        value -> runtime.par(ParId.of(inner))
                                .map(Arrays.asList(value), item -> item + 1, BatchOptions.inheritTimeout("inner"))
                                .valuesOrThrow()
                                .get(0),
                        BatchOptions.timeout("outer", TIMEOUT))
                .valuesOrThrow();
    }

    @Test
    void aTopLevelMapRecordsNoWait() {
        ParRuntime runtime = runtime("io", buffering());

        runtime.par(ParId.of("io")).map(Arrays.asList(1, 2), value -> value, BatchOptions.timeout("top", TIMEOUT));

        assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
    }

    @Test
    void aNestedMapOnTheSamePoolIsAnExecutorSelfLoop() {
        ParRuntime runtime = runtime("io", buffering());

        assertThat(nested(runtime, "io", "io")).containsExactly(2, 3);

        List<ExecutorWaitReport.Component> cycles = runtime.executorWaitReport().cyclicComponents();
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).cycle()).containsExactlyElementsOf(ids("io"));
        assertThat(cycles.get(0).toString()).contains("outer[").contains("inner[");
    }

    @Test
    void aNestedMapOnAPoolThatCannotStarveRecordsNoWait() {
        ParRuntime runtime = runtime("direct", handoff());

        assertThat(nested(runtime, "direct", "direct")).containsExactly(2, 3);

        assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
    }

    @Test
    void waitsFromTwoUnrelatedCallsFormOneCrossRequestCycle() {
        ParRuntime runtime = runtime("a", buffering(), "b", buffering());

        nested(runtime, "a", "b");
        assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
        nested(runtime, "b", "a");

        List<ExecutorWaitReport.Component> cycles = runtime.executorWaitReport().cyclicComponents();
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).members()).containsExactlyElementsOf(ids("a", "b"));
    }

    @Test
    void aNestedCombineOnTheCallersPoolIsASelfLoopButTheBatchFeedingItIsNot() {
        ParRuntime runtime = runtime("caller", buffering(), "batch", buffering());

        List<Integer> totals = runtime.par(ParId.of("caller"))
                .map(
                        Arrays.asList(1),
                        ignored -> runtime.par(ParId.of("batch"))
                                .mapAndCombine(
                                        Arrays.asList(1, 2),
                                        value -> value,
                                        BatchOptions.inheritTimeout("batch"),
                                        runtime.par(ParId.of("caller")),
                                        values -> Objects.requireNonNull(values).size())
                                .terminalValueOrThrow(),
                        BatchOptions.timeout("outer", TIMEOUT))
                .valuesOrThrow();

        assertThat(totals).containsExactly(2);
        List<ExecutorWaitReport.Component> cycles = runtime.executorWaitReport().cyclicComponents();
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).cycle()).containsExactlyElementsOf(ids("caller"));
    }

    @Test
    void aNestedGroupWaitsOnEveryMemberAndItsCombine() {
        ParRuntime runtime = runtime("caller", buffering(), "members", buffering(), "combine", buffering());
        TaskKey<Integer> left = TaskKey.named("left");
        TaskKey<Integer> right = TaskKey.named("right");
        TaskKey<Integer> sum = TaskKey.named("sum");

        List<@Nullable Integer> sums = runtime.par(ParId.of("caller"))
                .map(
                        Arrays.asList(1),
                        ignored -> runtime.groupInheriting("group")
                                .par(left, runtime.par(ParId.of("members")), () -> 1)
                                .par(right, runtime.par(ParId.of("caller")), () -> 2)
                                .combine(sum, runtime.par(ParId.of("combine")), values -> 3)
                                .runAll()
                                .terminalValueOrThrow(),
                        BatchOptions.timeout("outer", TIMEOUT))
                .valuesOrThrow();
        assertThat(sums).containsExactly(3);
        assertThat(runtime.executorWaitReport().cyclicComponents()).hasSize(1);
        assertThat(runtime.executorWaitReport().cyclicComponents().get(0).cycle())
                .containsExactlyElementsOf(ids("caller"));

        nested(runtime, "members", "caller");
        nested(runtime, "combine", "caller");
        List<ExecutorWaitReport.Component> cycles = runtime.executorWaitReport().cyclicComponents();
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).members()).containsExactlyElementsOf(ids("caller", "members", "combine"));
    }

    @Test
    void aTopLevelGroupRecordsNoWait() {
        ParRuntime runtime = runtime("io", buffering());
        TaskKey<Integer> only = TaskKey.named("only");

        TaskGroupResult<Integer, Void> result = runtime.group("top", TIMEOUT)
                .par(only, runtime.par(ParId.of("io")), () -> 1)
                .runAll();

        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
    }

    @Test
    void aCallerOnAnotherRuntimesPoolIsOutsideThisRuntimesGraph() {
        ParRuntime outer = runtime("outer", buffering());
        ParRuntime inner = runtime("inner", buffering());

        outer.par(ParId.of("outer"))
                .map(
                        Arrays.asList(1),
                        value -> inner.par(ParId.of("inner"))
                                .map(
                                        Arrays.asList(value),
                                        item -> inner.par(ParId.of("inner"))
                                                .map(Arrays.asList(item), x -> x, BatchOptions.inheritTimeout("deep"))
                                                .valuesOrThrow()
                                                .get(0),
                                        BatchOptions.inheritTimeout("inner"))
                                .valuesOrThrow()
                                .get(0),
                        BatchOptions.timeout("outer", TIMEOUT))
                .valuesOrThrow();

        assertThat(outer.executorWaitReport().cyclicComponents()).isEmpty();
        assertThat(inner.executorWaitReport().cyclicComponents()).hasSize(1);
        assertThat(inner.executorWaitReport().cyclicComponents().get(0).cycle())
                .containsExactlyElementsOf(ids("inner"));
    }

    @Test
    void aPoolRegisteredUnderTwoIdsIsOneExecutorCarryingBothIds() {
        ExecutorService shared = buffering();
        ParRuntime runtime = runtime("orders", shared, "billing", shared);

        nested(runtime, "orders", "billing");

        List<ExecutorWaitReport.Component> cycles = runtime.executorWaitReport().cyclicComponents();
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).cycle()).containsExactly(ImmutableSet.of(ParId.of("orders"), ParId.of("billing")));
    }

    @Test
    void theCycleWarningRunsOffTheAdmittingThreadAndABlockedHandlerDelaysNoAdmission() throws Exception {
        ParRuntime runtime = runtime("io", buffering());
        Logger logger = Logger.getLogger(ExecutorWaitGraph.class.getName());
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<@Nullable String> warningThread = new AtomicReference<>();
        AtomicReference<@Nullable String> message = new AtomicReference<>();
        Handler blocking = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warningThread.set(Thread.currentThread().getName());
                message.set(record.getMessage());
                published.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        boolean parents = logger.getUseParentHandlers();
        logger.addHandler(blocking);
        logger.setUseParentHandlers(false);
        try {
            AtomicReference<@Nullable String> admittingThread = new AtomicReference<>();
            runtime.par(ParId.of("io"))
                    .map(
                            Arrays.asList(1),
                            value -> {
                                admittingThread.set(Thread.currentThread().getName());
                                return runtime.par(ParId.of("io"))
                                        .map(Arrays.asList(value), item -> item, BatchOptions.inheritTimeout("inner"))
                                        .valuesOrThrow()
                                        .get(0);
                            },
                            BatchOptions.timeout("outer", TIMEOUT))
                    .valuesOrThrow();
            nested(runtime, "io", "io");

            assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(warningThread.get()).isNotNull().isNotEqualTo(admittingThread.get());
            assertThat(message.get()).contains("Potential executor deadlock").contains("[io] -> [io]");
        } finally {
            release.countDown();
            logger.removeHandler(blocking);
            logger.setUseParentHandlers(parents);
        }
    }
}

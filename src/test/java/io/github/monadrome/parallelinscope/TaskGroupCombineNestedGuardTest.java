package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Locks the inline guard's behaviour when work nests: a combine body that opens another group or
 * batch, and a combine whose own {@code Par} is the one the nested work runs on.
 *
 * <p>The guard compares the executing thread against the thread still inside {@code
 * executor.execute()} for the same future. Nesting puts several such windows on one thread at once —
 * an outer combine running on a worker submits inner work from that worker — so the per-future
 * scoping is what keeps them from being confused for one another. These tests pin that: an inner
 * submission must be judged by its own handoff, never by an enclosing one.
 */
class TaskGroupCombineNestedGuardTest {

    private static ThreadPoolExecutor saturatingPool(String threadName) {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.SECONDS,
                SmartBlockingQueue.create(100),
                r -> new Thread(r, threadName),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Test
    void aCombineBodyMayRunANestedBatchOnItsOwnPar() throws Exception {
        // The outer combine runs on a combine-worker and submits a batch to the very same Par from
        // inside its body. The outer combine's handoff window closed long before, so nothing about
        // the outer submission may leak into the judgement of the inner one — and the inner elements
        // carry no guard at all, since the guard is only ever set on a combine.
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ThreadPoolExecutor combinePool = saturatingPool("combine-worker");
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("c"), combinePool)
                .build();
        AtomicReference<String> combineThread = new AtomicReference<>();
        AtomicReference<List<String>> nestedThreads = new AtomicReference<>();
        try {
            TaskGroup<?, String> group = runtime.groupDraft("outer", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .combine(
                            "sum",
                            runtime.par(ParId.of("c")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                combineThread.set(Thread.currentThread().getName());
                                List<String> ran = runtime.par(ParId.of("c"))
                                        .submitBatch(
                                                Arrays.asList(1, 2, 3),
                                                i -> Thread.currentThread().getName(),
                                                BatchOptions.timeout("inner", Duration.ofSeconds(5)))
                                        .valuesOrThrow();
                                nestedThreads.set(ran);
                                return "combined:" + ran.size();
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(combineThread.get()).isEqualTo("combine-worker");
            assertThat(nestedThreads.get()).hasSize(3);
        } finally {
            runtime.close();
            memberPool.shutdownNow();
            combinePool.shutdownNow();
        }
    }

    @Test
    void aNestedGroupsCombineIsJudgedByItsOwnHandoffNotTheOuterOne() throws Exception {
        // Two combines, one inside the other, on two different Pars. The inner group is declared and
        // submitted from inside the outer combine's body, so the inner combine's submitting thread is
        // the outer combine's worker. Both must succeed: neither combine ran inside its own handoff.
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ThreadPoolExecutor outerCombinePool = saturatingPool("outer-combine");
        ThreadPoolExecutor innerCombinePool = saturatingPool("inner-combine");
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("outer"), outerCombinePool)
                .register(ParId.of("inner"), innerCombinePool)
                .build();
        AtomicReference<String> outerThread = new AtomicReference<>();
        AtomicReference<String> innerThread = new AtomicReference<>();
        try {
            TaskGroup<?, String> group = runtime.groupDraft("outer", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .combine(
                            "outer-sum",
                            runtime.par(ParId.of("outer")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                outerThread.set(Thread.currentThread().getName());
                                TaskGroup<?, String> nested = runtime.groupDraft("inner", Duration.ofSeconds(5))
                                        .par(
                                                "x",
                                                runtime.par(ParId.of("m")),
                                                TaskOptions.inheritTimeout(),
                                                TypeToken.of(String.class),
                                                () -> "x")
                                        .combine(
                                                "inner-sum",
                                                runtime.par(ParId.of("inner")),
                                                TaskOptions.inheritTimeout(),
                                                TypeToken.of(String.class),
                                                inner -> {
                                                    innerThread.set(Thread.currentThread()
                                                            .getName());
                                                    return "inner-done";
                                                })
                                        .submitAll();
                                return nested.terminalFuture()
                                        .orElseThrow(() -> new AssertionError("no inner combine"))
                                        .get(5, TimeUnit.SECONDS);
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(outerThread.get()).isEqualTo("outer-combine");
            assertThat(innerThread.get()).isEqualTo("inner-combine");
        } finally {
            runtime.close();
            memberPool.shutdownNow();
            outerCombinePool.shutdownNow();
            innerCombinePool.shutdownNow();
        }
    }

    @Test
    void aNestedCombineIsStillRefusedWhenItsOwnPoolWouldInlineIt() throws Exception {
        // The guard must reach a nested combine too, and on the nested combine's own terms: here the
        // inner Par is saturated, so its combine would run on the thread submitting it — which is the
        // outer combine's worker, not a caller thread. That is the same violation one level down.
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ThreadPoolExecutor outerCombinePool = saturatingPool("outer-combine");
        ThreadPoolExecutor innerCombinePool = saturatingPool("inner-combine");
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("outer"), outerCombinePool)
                .register(ParId.of("inner"), innerCombinePool)
                .build();
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            innerCombinePool.execute(() -> {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        AtomicReference<String> innerThread = new AtomicReference<>();
        AtomicReference<TaskOutcome> innerOutcome = new AtomicReference<>();
        try {
            TaskGroup<?, String> group = runtime.groupDraft("outer", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .combine(
                            "outer-sum",
                            runtime.par(ParId.of("outer")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                TaskGroup<?, String> nested = runtime.groupDraft("inner", Duration.ofSeconds(5))
                                        .par(
                                                "x",
                                                runtime.par(ParId.of("m")),
                                                TaskOptions.inheritTimeout(),
                                                TypeToken.of(String.class),
                                                () -> "x")
                                        .combine(
                                                "inner-sum",
                                                runtime.par(ParId.of("inner")),
                                                TaskOptions.inheritTimeout().rejectEnqueue(true),
                                                TypeToken.of(String.class),
                                                inner -> {
                                                    innerThread.set(Thread.currentThread()
                                                            .getName());
                                                    return "inner-done";
                                                })
                                        .submitAll();
                                innerOutcome.set(nested.completionFuture()
                                        .get(5, TimeUnit.SECONDS)
                                        .outcome());
                                return "outer-done";
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(innerOutcome.get()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(innerThread.get()).isNull();
        } finally {
            release.countDown();
            runtime.close();
            memberPool.shutdownNow();
            outerCombinePool.shutdownNow();
            innerCombinePool.shutdownNow();
        }
    }
}

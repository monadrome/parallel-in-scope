package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Locks the safety of an executor that runs every task on the thread that submits it.
 *
 * <p>{@code MoreExecutors.newDirectExecutorService()} is a legitimate registration — it is how a
 * caller asks for synchronous execution, and this library's own graph export has a case for it. But it
 * makes every task borrow the submitting thread, so the thread state that crosses that boundary is no
 * longer an edge case reachable only under pool saturation: it is the whole behavior of the executor.
 *
 * <p>Two things used to cross it. A task body that restores its interrupt flag — the textbook response
 * to {@code InterruptedException} — left that flag on the caller, and the caller's next library call
 * then failed: {@code valuesOrThrow()} threw {@code LeanCancellationException: interrupted while
 * awaiting batch values} for a batch in which every element had succeeded. And {@code
 * SubmissionScope}, which wraps the submission action, stayed installed while the body ran, so a
 * submission the body made on its own behalf inherited the enclosing unit's enqueue policy.
 *
 * <p>What must not change is the TTL contract. The snapshot is captured at prepare and replayed around
 * the body, which on a borrowed thread degenerates to swap-in, run, swap-back. The body therefore sees
 * submit-time values and the caller's own values survive the body mutating them. That already held and
 * is locked here so the isolation above cannot regress it.
 */
class DirectExecutorSafetyTest {

    private static final TransmittableThreadLocal<String> TTL = new TransmittableThreadLocal<>();

    @Test
    void aBodyThatRestoresItsInterruptFlagDoesNotBreakTheCallersNextCall() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("d"), direct).build();
        try {
            TaskBatch<String> batch = runtime.par(ParId.of("d"))
                    .submitBatch(
                            Arrays.asList(1, 2, 3),
                            // Every element restores the flag, which is what a correct body does after
                            // catching InterruptedException. Every element matters: if only an earlier
                            // one dirtied the flag, the next task's clear-on-entry would hide a missing
                            // restore-on-exit, and the last element is the one whose exit the caller
                            // actually observes.
                            i -> {
                                Thread.currentThread().interrupt();
                                return "v" + i;
                            },
                            BatchOptions.timeout("b", Duration.ofSeconds(5)));

            // The flag must not have followed the body out onto this thread.
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            // And the consequence that made it a bug rather than untidiness: reading the result.
            assertThatCode(batch::report).doesNotThrowAnyException();
            assertThat(batch.valuesOrThrow()).containsExactly("v1", "v2", "v3");
        } finally {
            Thread.interrupted();
            runtime.close();
            direct.shutdownNow();
        }
    }

    @Test
    void aBodyRunningOnTheSubmittingThreadSeesNoSubmissionScope() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("d"), direct).build();
        AtomicReference<String> seen = new AtomicReference<>("<never ran>");
        try {
            runtime.par(ParId.of("d"))
                    .submitBatch(
                            Arrays.asList(1),
                            i -> {
                                MultiTaskContext unit = SubmissionScope.current();
                                seen.set(unit == null ? "null" : unit.name());
                                return "ok";
                            },
                            BatchOptions.timeout("outer", Duration.ofSeconds(5)))
                    .valuesOrThrow();

            // The pooled path has always shown null here; the borrowed path must agree, or a nested
            // raw submission from inside the body picks up this unit's enqueue policy by accident.
            assertThat(seen.get()).isEqualTo("null");
        } finally {
            runtime.close();
            direct.shutdownNow();
        }
    }

    @Test
    void theCallersOwnScopeIsRestoredAfterTheBodyDisturbsIt() throws Exception {
        // The submitting thread may itself be inside a batch of its own. Isolation must put back what
        // it found rather than merely clearing, or an outer submission loop loses its scope midway.
        ThreadPoolExecutor outerPool = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                SmartBlockingQueue.create(50),
                r -> new Thread(r, "outer-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("outer"), outerPool)
                .register(ParId.of("d"), direct)
                .build();
        AtomicReference<String> innerSaw = new AtomicReference<>("<never ran>");
        try {
            List<String> out = runtime.par(ParId.of("outer"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            i -> {
                                // A nested batch on the direct executor: its bodies borrow this
                                // worker, which is itself mid-submission for the outer batch.
                                try {
                                    runtime.par(ParId.of("d"))
                                            .submitBatch(
                                                    Arrays.asList(9),
                                                    j -> {
                                                        MultiTaskContext unit = SubmissionScope.current();
                                                        innerSaw.set(unit == null ? "null" : unit.name());
                                                        return "inner";
                                                    },
                                                    BatchOptions.timeout("inner", Duration.ofSeconds(5)))
                                            .valuesOrThrow();
                                } catch (java.util.concurrent.ExecutionException e) {
                                    throw new IllegalStateException(e);
                                }
                                return "outer" + i;
                            },
                            BatchOptions.timeout("outer", Duration.ofSeconds(10)))
                    .valuesOrThrow();

            assertThat(out).containsExactly("outer1", "outer2");
            assertThat(innerSaw.get()).isEqualTo("null");
        } finally {
            runtime.close();
            outerPool.shutdownNow();
            direct.shutdownNow();
        }
    }

    @Test
    void theTtlSnapshotStillSurroundsABorrowedBody() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("d"), direct).build();
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            TTL.set("submit-time");
            runtime.par(ParId.of("d"))
                    .submitBatch(
                            Arrays.asList(1),
                            i -> {
                                seen.set(TTL.get());
                                TTL.set("mutated-by-body");
                                return "ok";
                            },
                            BatchOptions.timeout("b", Duration.ofSeconds(5)))
                    .valuesOrThrow();

            // Clear-run-restore around the body: it reads the prepare-time snapshot, and what it
            // writes does not escape onto the thread it borrowed.
            assertThat(seen.get()).isEqualTo("submit-time");
            assertThat(TTL.get()).isEqualTo("submit-time");
        } finally {
            TTL.remove();
            runtime.close();
            direct.shutdownNow();
        }
    }

    @Test
    void aDirectExecutorRunsEveryBodyOnTheSubmittingThread() throws Exception {
        // The characterising fact, pinned so the tests above are known to exercise the borrowed path
        // rather than passing for some unrelated reason.
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("d"), direct).build();
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            inputs.add(i);
        }
        String caller = Thread.currentThread().getName();
        try {
            List<String> threads = runtime.par(ParId.of("d"))
                    .submitBatch(
                            inputs,
                            i -> Thread.currentThread().getName(),
                            BatchOptions.timeout("b", Duration.ofSeconds(5)))
                    .valuesOrThrow();

            assertThat(threads).allMatch(caller::equals);
        } finally {
            runtime.close();
            direct.shutdownNow();
        }
    }

    @Test
    void aGroupMemberOnADirectExecutorIsIsolatedToo() throws Exception {
        // The batch path is not the only borrower: a group member submitted to a direct executor runs
        // on the submitAll thread.
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("d"), direct).build();
        try {
            TaskGroup<?, ?> group = runtime.groupDraft("g", Duration.ofSeconds(5))
                    .par(
                            "a",
                            runtime.par(ParId.of("d")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> {
                                Thread.currentThread().interrupt();
                                return "a";
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(5, TimeUnit.SECONDS);

            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            Thread.interrupted();
            runtime.close();
            direct.shutdownNow();
        }
    }

    @Test
    void aPooledBodyRestoringItsFlagStillLeavesTheWorkerUsable() throws Exception {
        // Control group: the isolation must not change the pooled path, where it is a no-op because
        // ThreadPoolExecutor.runWorker already clears the flag before each task. Two sequential
        // batches on a single-threaded pool, the first of which dirties the flag.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, SmartBlockingQueue.create(50), r -> new Thread(r, "solo-worker"));
        ParRuntime runtime = ParRuntime.builder().register(ParId.of("p"), pool).build();
        try {
            List<String> first = runtime.par(ParId.of("p"))
                    .submitBatch(
                            Arrays.asList(1),
                            i -> {
                                Thread.currentThread().interrupt();
                                return Thread.currentThread().getName();
                            },
                            BatchOptions.timeout("first", Duration.ofSeconds(5)))
                    .valuesOrThrow();
            List<String> second = runtime.par(ParId.of("p"))
                    .submitBatch(
                            Arrays.asList(1),
                            i -> Thread.currentThread().getName() + ":"
                                    + Thread.currentThread().isInterrupted(),
                            BatchOptions.timeout("second", Duration.ofSeconds(5)))
                    .valuesOrThrow();

            assertThat(first).containsExactly("solo-worker");
            assertThat(second).containsExactly("solo-worker:false");
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}

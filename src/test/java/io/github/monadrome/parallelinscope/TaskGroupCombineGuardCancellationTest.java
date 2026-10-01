package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Locks the inline guard's interaction with group teardown: that it is deterministic under
 * repetition, and that a cancellation arriving before the combine is submitted keeps its own
 * attribution rather than being reported as a submission failure.
 *
 * <p>What these tests deliberately do <em>not</em> claim to cover: the narrow interleaving in which a
 * cancellation lands between the phase claim in {@code run()} and the guard's {@code setException} a
 * few instructions later. Measured over the repetitions below, that window is never hit — the first
 * test settles as {@code SUBMISSION_FAILURE} every time and the second as {@code GROUP_CANCELLED}
 * every time, because a cancellation that wins at all wins early enough that the combine is never
 * submitted and the guard never runs. Constructing the interleaving would require injecting a barrier
 * into {@code run()}.
 *
 * <p>That window is nonetheless safe by construction, and the reasoning is the reason no test forces
 * it: {@code AbstractFuture} serialises completion, so exactly one of {@code cancel} and {@code
 * setException} takes effect and the loser is a no-op. The guard introduces no state of its own on
 * that path — it calls {@code setException} and falls into the same {@code finally} as every other
 * outcome — so whichever wins, the phase advance, the observer release and the body-exit publish all
 * run exactly once. If cancellation wins, the outcome reports the cancellation's cause, which outranks
 * the combine's: a group being torn down has a reason of its own, and {@code classifyCancelled}
 * already encodes that precedence for every member.
 */
class TaskGroupCombineGuardCancellationTest {

    /**
     * Every outcome that is a correct answer here. The assertions below also pin the one actually
     * observed, so this set is the weaker claim that survives a timing shift rather than a licence for
     * any of them: a run that started reporting a different member of the set would still be a change
     * worth seeing, and the exact assertion is what would show it.
     */
    private static final Set<TaskOutcome> LEGITIMATE = EnumSet.of(
            TaskOutcome.SUBMISSION_FAILURE, TaskOutcome.TIMEOUT, TaskOutcome.FAIL_FAST, TaskOutcome.GROUP_CANCELLED);

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
    void theGuardSettlesTheSameWayOnEveryRun() throws Exception {
        // Forty runs with the combine pool saturated throughout, so every iteration reaches the guard
        // and must settle identically. The deadline is generous on purpose: at 1ms the member itself
        // gets cancelled before it finishes, the combine is then never submitted, and the run measures
        // deadline attribution rather than the guard. (That path has a pre-existing attribution race of
        // its own — an uncommitted group token reports MEMBER_CANCELLED where TIMEOUT is expected, see
        // TaskGroup.classifyCancelled — which is not this test's subject.)
        for (int attempt = 0; attempt < 40; attempt++) {
            ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
            ThreadPoolExecutor combinePool = saturatingPool("combine-worker");
            ParRuntime runtime = ParRuntime.builder()
                    .register(ParId.of("m"), memberPool)
                    .register(ParId.of("c"), combinePool)
                    .build();
            CountDownLatch release = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) {
                combinePool.execute(() -> {
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            AtomicBoolean bodyRan = new AtomicBoolean();
            try {
                TaskGroup<?, String> group = runtime.group("g", Duration.ofSeconds(10))
                        .par(
                                "a",
                                runtime.par(ParId.of("m")),
                                TaskOptions.inheritTimeout(),
                                TypeToken.of(String.class),
                                () -> "a")
                        .combine(
                                "sum",
                                runtime.par(ParId.of("c")),
                                TaskOptions.inheritTimeout().rejectEnqueue(true),
                                TypeToken.of(String.class),
                                values -> {
                                    bodyRan.set(true);
                                    return "combined";
                                })
                        .submitAll();

                TaskGroupResult result = group.completionFuture().get(10, TimeUnit.SECONDS);

                assertThat(result.outcome()).isIn(LEGITIMATE);
                assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
                assertThat(bodyRan).isFalse();
                // A stranded slot here would mean the guard's path skipped the exit publish that its
                // phase claim made it responsible for.
                assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            } finally {
                release.countDown();
                runtime.close();
                memberPool.shutdownNow();
                combinePool.shutdownNow();
            }
        }
    }

    @Test
    void aCancelThatBeatsTheSubmissionKeepsItsOwnAttribution() throws Exception {
        // cancel() immediately after submitAll() wins every time, early enough that the combine is
        // never submitted and the guard never runs. The outcome must therefore be the cancellation's
        // own, not SUBMISSION_FAILURE: the guard must not be able to relabel a group that was
        // cancelled for an unrelated reason.
        for (int attempt = 0; attempt < 20; attempt++) {
            ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
            ThreadPoolExecutor combinePool = saturatingPool("combine-worker");
            ParRuntime runtime = ParRuntime.builder()
                    .register(ParId.of("m"), memberPool)
                    .register(ParId.of("c"), combinePool)
                    .build();
            CountDownLatch release = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) {
                combinePool.execute(() -> {
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            AtomicBoolean bodyRan = new AtomicBoolean();
            try {
                TaskGroup<?, String> group = runtime.group("g", Duration.ofSeconds(10))
                        .par(
                                "a",
                                runtime.par(ParId.of("m")),
                                TaskOptions.inheritTimeout(),
                                TypeToken.of(String.class),
                                () -> "a")
                        .combine(
                                "sum",
                                runtime.par(ParId.of("c")),
                                TaskOptions.inheritTimeout().rejectEnqueue(true),
                                TypeToken.of(String.class),
                                values -> {
                                    bodyRan.set(true);
                                    return "combined";
                                })
                        .submitAll();
                group.cancel();

                TaskGroupResult result = group.completionFuture().get(10, TimeUnit.SECONDS);

                assertThat(result.outcome()).isIn(LEGITIMATE);
                assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
                assertThat(bodyRan).isFalse();
                assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            } finally {
                release.countDown();
                runtime.close();
                memberPool.shutdownNow();
                combinePool.shutdownNow();
            }
        }
    }
}

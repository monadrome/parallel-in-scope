package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Concurrency probes for the lock-free convergence path: I2 (one failure name fixes the group for
 * good) and I5 (the barrier publishes every member's classification and timestamps before the
 * snapshot is taken).
 *
 * <p>Both probes assert with bounded timeouts on purpose. A broken barrier stalls the completion
 * future rather than throwing, so an unbounded wait would hang the whole suite instead of failing
 * the test.
 *
 * <p>The visibility probe is meaningful on hardware with a weak memory model. On x86 (TSO) the
 * store buffer alone almost never reorders these writes, so a missing happens-before edge is
 * unlikely to reproduce there; Apple Silicon is where it shows.
 */
class TaskGroupConvergenceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MEMBERS = 4;
    private static final int ROUNDS = 200;

    @Test
    void firstWriterNamesTheGroupUnderConcurrentMemberFailures() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(MEMBERS);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        // The group bind aggregates member futures, so a run with several failing members makes
        // Guava log a SEVERE "more than one input Future failure" per extra failure. This loop
        // provokes that deliberately and its own assertions cover the behavior, so the noise is
        // muted for the duration and the level restored afterwards.
        Logger aggregateFuture = Logger.getLogger("com.google.common.util.concurrent.AggregateFuture");
        Level previousLevel = aggregateFuture.getLevel();
        try {
            aggregateFuture.setLevel(Level.OFF);
            for (int round = 0; round < ROUNDS; round++) {
                CountDownLatch ready = new CountDownLatch(MEMBERS);
                CountDownLatch fire = new CountDownLatch(1);
                TaskGroupDefinition.Builder builder = global.defineGroup("first-failure-" + round, TIMEOUT);
                List<TaskGroupDefinition.Member<Integer>> handles = new ArrayList<>();
                for (int member = 0; member < MEMBERS; member++) {
                    handles.add(builder.task("m" + member, global.par(ParId.of("worker"))));
                }
                TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                    for (int member = 0; member < MEMBERS; member++) {
                        int index = member;
                        bindings.task(handles.get(index), () -> {
                            ready.countDown();
                            fire.await(30, TimeUnit.SECONDS);
                            // Only half the members fail, so the recorded name has to come from
                            // the failing half rather than from any member of the group.
                            if (index % 2 == 1) {
                                throw new IllegalStateException("boom-" + index);
                            }
                            return index;
                        });
                    }
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                fire.countDown();

                TaskGroupResult result = group.completionFuture().get(10, TimeUnit.SECONDS);
                String named = result.failedTaskName();
                assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
                assertThat(named).isIn("m1", "m3");
                assertThat(result.members().get(named).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
                // Once fixed, the failing task name is never rewritten.
                assertThat(result.failedTaskName()).isEqualTo(named);
            }
        } finally {
            aggregateFuture.setLevel(previousLevel);
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void convergenceSnapshotPublishesEveryMemberClassificationAndTimestamp() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(MEMBERS);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CountDownLatch started = new CountDownLatch(MEMBERS);
                CountDownLatch release = new CountDownLatch(1);
                TaskGroupDefinition.Builder builder = global.defineGroup("visibility-" + round, TIMEOUT);
                List<TaskGroupDefinition.Member<Integer>> handles = new ArrayList<>();
                for (int member = 0; member < MEMBERS; member++) {
                    handles.add(builder.task("m" + member, global.par(ParId.of("worker"))));
                }
                TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                    for (TaskGroupDefinition.Member<Integer> handle : handles) {
                        bindings.task(handle, () -> {
                            started.countDown();
                            release.await(30, TimeUnit.SECONDS);
                            return 1;
                        });
                    }
                });
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();

                TaskGroupResult result = group.completionFuture().get(10, TimeUnit.SECONDS);
                assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
                assertThat(result.members()).hasSize(MEMBERS);
                for (TaskCompletion<?> member : result.members().values()) {
                    assertThat(member.outcome()).isEqualTo(TaskOutcome.SUCCESS);
                    assertThat(member.submitTimeNanos()).isGreaterThan(0L);
                    assertThat(member.startTimeNanos()).isGreaterThan(0L);
                    assertThat(member.endTimeNanos()).isGreaterThan(member.startTimeNanos());
                }
            }
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}

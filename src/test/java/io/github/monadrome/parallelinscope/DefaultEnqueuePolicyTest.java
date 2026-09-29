package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Locks the consequence of the default task type and enqueue policy on the pool shape this library
 * recommends: a {@link SmartBlockingQueue} paired with a {@code CallerRunsPolicy}.
 *
 * <p>{@code SmartBlockingQueue.offer} refuses when the unit's type is {@code CPU_BOUND} <em>or</em>
 * its {@code rejectEnqueue} flag is set. Both conditions were once the default, so a queue built
 * with any capacity refused every task submitted with default options: the capacity went unused, the
 * pool grew to its maximum, and every further task ran on whichever thread called {@code execute} —
 * a parallel library running its work serially on the caller. These tests pin the defaults by their
 * observable effect rather than their value, so a future change to either default that reintroduces
 * that behaviour fails here and not only in the options unit tests.
 */
class DefaultEnqueuePolicyTest {

    /** The pool shape the library's own registration diagnostics point callers toward. */
    private static ThreadPoolExecutor recommendedPool(String threadName, int capacity) {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.SECONDS,
                SmartBlockingQueue.create(capacity),
                r -> new Thread(r, threadName),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Test
    void defaultOptionsEnqueueOntoASmartQueueInsteadOfRunningOnTheCaller() throws Exception {
        ThreadPoolExecutor pool = recommendedPool("worker", 100);
        ParRuntime runtime = ParRuntime.builder().register(ParId.of("w"), pool).build();
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            inputs.add(i);
        }
        String callerThread = Thread.currentThread().getName();
        try {
            List<String> threads = runtime.par(ParId.of("w"))
                    .map(
                            inputs,
                            i -> Thread.currentThread().getName(),
                            BatchOptions.timeout("b", Duration.ofSeconds(30)))
                    .valuesOrThrow();

            // Every body ran on a pool worker. With the old defaults the queue refused all 12, the
            // pool saturated at two threads, and the remaining ten ran on the caller.
            assertThat(threads).doesNotContain(callerThread);
            assertThat(threads).allMatch(name -> name.equals("worker"));
            assertThat(pool.getCompletedTaskCount()).isEqualTo(12);
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }

    @Test
    void requestingCpuBoundStillRefusesTheQueueSoTheOptInIsIntact() throws Exception {
        ThreadPoolExecutor pool = recommendedPool("cpu-worker", 100);
        ParRuntime runtime = ParRuntime.builder().register(ParId.of("w"), pool).build();
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            inputs.add(i);
        }
        String callerThread = Thread.currentThread().getName();
        try {
            List<String> threads = runtime.par(ParId.of("w"))
                    .map(
                            inputs,
                            i -> Thread.currentThread().getName(),
                            BatchOptions.timeout("b", Duration.ofSeconds(30)).taskType(TaskType.CPU_BOUND))
                    .valuesOrThrow();

            // The control group: asking for CPU_BOUND must still reach the rejection handler, which
            // with CallerRunsPolicy means the caller runs the overflow. Otherwise the new defaults
            // would have silently removed the capability rather than stopped imposing it.
            assertThat(threads).contains(callerThread);
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }

    @Test
    void requestingRejectEnqueueAloneAlsoStillRefusesTheQueue() throws Exception {
        ThreadPoolExecutor pool = recommendedPool("flag-worker", 100);
        ParRuntime runtime = ParRuntime.builder().register(ParId.of("w"), pool).build();
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            inputs.add(i);
        }
        String callerThread = Thread.currentThread().getName();
        try {
            List<String> threads = runtime.par(ParId.of("w"))
                    .map(
                            inputs,
                            i -> Thread.currentThread().getName(),
                            BatchOptions.timeout("b", Duration.ofSeconds(30)).rejectEnqueue(true))
                    .valuesOrThrow();

            // The other half of the control group: the flag is an independent opt-in, so it must
            // reach the handler on its own without the task type being changed too.
            assertThat(threads).contains(callerThread);
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}

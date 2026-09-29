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
            // pool saturated at its two threads, and the remaining ten ran on the caller.
            //
            // The thread names are the whole assertion. getCompletedTaskCount() is not used: a worker
            // completes the future inside the task and only increments that counter afterwards in
            // afterExecute, so a run that has observed every value can still read 11 — the JDK
            // documents the count as approximate for exactly this reason.
            assertThat(threads).doesNotContain(callerThread);
            assertThat(threads).allMatch(name -> name.equals("worker"));
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
            // with CallerRunsPolicy means the submitting thread runs the overflow. Otherwise the new
            // defaults would have silently removed the capability rather than stopped imposing it.
            //
            // The assertion is "something ran off the pool", not "the test thread ran it": a batch
            // submits its first window from the calling thread and refills from a library submitter
            // thread, so which of the two absorbs a given overflow element is not this test's claim.
            assertThat(threads).isNotEmpty();
            assertThat(threads).anyMatch(name -> !name.equals("cpu-worker"));
            assertThat(callerThread).isNotEqualTo("cpu-worker");
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
            // reach the handler on its own without the task type being changed too. Same reasoning as
            // above for why this asserts "off the pool" rather than naming a specific thread.
            assertThat(threads).isNotEmpty();
            assertThat(threads).anyMatch(name -> !name.equals("flag-worker"));
            assertThat(callerThread).isNotEqualTo("flag-worker");
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}

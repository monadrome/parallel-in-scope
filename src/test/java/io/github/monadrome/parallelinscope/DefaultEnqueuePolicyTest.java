package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Locks the consequence of the default options, and of a task type, on the pool shape this library
 * recommends: a {@link SmartBlockingQueue} paired with a {@code CallerRunsPolicy}.
 *
 * <p>{@code SmartBlockingQueue.offer} refuses only when the unit sets {@code rejectEnqueue}. A
 * refused offer leaves the capacity unused, grows the pool to its maximum, and runs every further
 * task on whichever thread called {@code execute} — a parallel library running its work serially on
 * the caller. These tests pin that by observable effect rather than by option value: default options
 * and a {@link TaskType} declaration alone must enqueue, and only {@code rejectEnqueue} may refuse.
 */
class DefaultEnqueuePolicyTest {

    /** The pool shape the library's own registration diagnostics point callers toward. */
    private static ThreadPoolExecutor recommendedPool(String threadName, int capacity) {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.SECONDS,
                new SmartBlockingQueue<>(capacity),
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
                    .submitBatch(
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

    /**
     * A task type is a diagnostic declaration: declaring {@link TaskType#COMPUTE} must not refuse
     * the queue, so every body still runs on a pool worker.
     */
    @Test
    void declaringComputeAloneStillEnqueuesOntoASmartQueue() throws Exception {
        ThreadPoolExecutor pool = recommendedPool("compute-worker", 100);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("w"), pool, TaskType.COMPUTE)
                .build();
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            inputs.add(i);
        }
        String callerThread = Thread.currentThread().getName();
        try {
            List<String> threads = runtime.par(ParId.of("w"))
                    .submitBatch(
                            inputs,
                            i -> Thread.currentThread().getName(),
                            BatchOptions.timeout("b", Duration.ofSeconds(30)).taskType(TaskType.COMPUTE))
                    .valuesOrThrow();
            assertThat(threads).doesNotContain(callerThread);
            assertThat(threads).allMatch(name -> name.equals("compute-worker"));
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }

    /**
     * The control group: the flag is the opt-in, so it must reach the rejection handler, which with
     * {@code CallerRunsPolicy} means a thread off the pool runs the overflow. Otherwise the defaults
     * would have silently removed the capability rather than stopped imposing it. The assertion is
     * "something ran off the pool", not "the test thread ran it": a batch submits its first window
     * from the calling thread and refills from a library submitter thread, so which of the two
     * absorbs a given overflow element is not this test's claim.
     */
    @Test
    void requestingRejectEnqueueStillRefusesTheQueue() throws Exception {
        ThreadPoolExecutor pool = recommendedPool("flag-worker", 100);
        ParRuntime runtime = ParRuntime.builder().register(ParId.of("w"), pool).build();
        List<Integer> inputs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            inputs.add(i);
        }
        String callerThread = Thread.currentThread().getName();
        try {
            List<String> threads = runtime.par(ParId.of("w"))
                    .submitBatch(
                            inputs,
                            i -> Thread.currentThread().getName(),
                            BatchOptions.timeout("b", Duration.ofSeconds(30)).rejectEnqueue(true))
                    .valuesOrThrow();

            assertThat(threads).isNotEmpty();
            assertThat(threads).anyMatch(name -> !name.equals("flag-worker"));
            assertThat(callerThread).isNotEqualTo("flag-worker");
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}

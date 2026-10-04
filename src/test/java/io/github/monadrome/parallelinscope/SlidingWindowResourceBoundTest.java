package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Resource bound of the sliding window through the public entry point: refill work must not park
 * one framework thread per batch that is waiting for a free slot. The pre-fix implementation
 * submitted a {@code submitRemaining} loop to a cached submitter pool per batch, so N concurrently
 * blocked finite-window batches held N waiting threads; the event-driven refill performs the
 * handoff on the completing thread instead.
 */
class SlidingWindowResourceBoundTest {

    private static final int BATCHES = 24;
    private static final int ELEMENTS = 2;
    private static final String SUBMITTER = "io.github.monadrome.parallelinscope.SlidingWindowSubmitter";

    @Test
    void concurrentBlockedBatchesDoNotParkAFrameworkThreadPerBatch() throws Exception {
        CountDownLatch occupierRelease = new CountDownLatch(1);
        ThreadPoolExecutor worker =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        // Occupy the only business worker so every batch hands off its first element and then waits
        // for a completion that cannot arrive.
        worker.submit(() -> Uninterruptibles.awaitUninterruptibly(occupierRelease));

        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("work"), worker).build();
        Par par = runtime.par(ParId.of("work"));
        List<Thread> callers = new ArrayList<>();
        try {
            for (int i = 0; i < BATCHES; i++) {
                Thread caller = new Thread(() -> par.map(
                        Arrays.asList(1, 2),
                        value -> value,
                        BatchOptions.timeout("batch", Duration.ofSeconds(30)).parallelism(1)));
                caller.setDaemon(true);
                caller.start();
                callers.add(caller);
            }

            awaitCallersRunning(callers);
            // Settling window: a pre-fix implementation spawns its submitter thread shortly after
            // the caller hands off its initial window, so sample after the batches are active.
            Thread.sleep(500);
            assertThat(parkedSubmitterThreads())
                    .as("%s blocked finite-window batches must not park framework submitter threads", BATCHES)
                    .isZero();
        } finally {
            occupierRelease.countDown();
            for (Thread caller : callers) {
                caller.join(TimeUnit.SECONDS.toMillis(5));
            }
            runtime.close();
            worker.shutdownNow();
        }
    }

    /**
     * Waits until every caller thread has entered {@code map}. The callers only park once the batch
     * is active, so this is the point at which a pre-fix implementation is about to spawn its
     * per-batch submitter thread.
     */
    private static void awaitCallersRunning(List<Thread> callers) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean allRunning = true;
            for (Thread caller : callers) {
                if (!caller.isAlive()) {
                    allRunning = false;
                    break;
                }
            }
            if (allRunning) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(callers).allMatch(Thread::isAlive);
    }

    private static long parkedSubmitterThreads() {
        Thread current = Thread.currentThread();
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(entry -> entry.getKey() != current)
                .filter(entry -> {
                    Thread.State state = entry.getKey().getState();
                    return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                })
                .filter(entry -> Arrays.stream(entry.getValue())
                        .anyMatch(frame -> frame.getClassName().equals(SUBMITTER)))
                .count();
    }
}

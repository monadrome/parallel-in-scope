package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * End-to-end purge triggering (issue #34): a batch timeout cancels queued tasks, and each
 * cancellation of an actually submitted future must reach the per-pool purger on its own — no
 * report or timeout-callback path is involved. The running interrupt-ignoring task is left alone:
 * purge removes queue entries, never running work.
 */
class PurgeTriggerIntegrationTest {

    @Test
    void timedOutBatchCancellationsPurgeTheQueueButNotTheRunningTask() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(6));
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("io"), pool)
                .purgePolicy(ParRuntimePurgePolicy.builder().enabled(true).build())
                .build();
        try {
            List<Integer> elements = IntStream.range(0, 6).boxed().collect(Collectors.toList());
            TaskBatchResult<Integer> result = runtime.par(ParId.of("io"))
                    .map(
                            elements,
                            ignored -> {
                                workerStarted.countDown();
                                while (true) {
                                    try {
                                        releaseWorker.await();
                                        return 1;
                                    } catch (InterruptedException swallowed) {
                                        // Deliberately ignore interruption: purge must not stop running work.
                                    }
                                }
                            },
                            // IO_BOUND + rejectEnqueue(false): the library's default rejects at
                            // enqueue, which would defeat the point — this test needs the backlog
                            // to actually reach the queue.
                            BatchOptions.timeout("leak", Duration.ofMillis(200))
                                    .parallelism(6)
                                    .taskType(TaskType.IO_BOUND)
                                    .rejectEnqueue(false));

            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pool.getQueue()).hasSize(5);

            // The 200 ms deadline cancels the five queued tasks; their cancel-before-run signals
            // must drive the purger to empty the queue without any report call being made.
            await().atMost(5, TimeUnit.SECONDS).until(() -> pool.getQueue().isEmpty());

            // The running task survived: it ignored the interrupt and is still blocked.
            assertThat(releaseWorker.getCount()).isEqualTo(1L);

            releaseWorker.countDown();
            assertThat(result.awaitBodyCompletion(Duration.ofSeconds(5))).isTrue();
            result.close();
        } finally {
            releaseWorker.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }
}

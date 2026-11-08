package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Batch close and terminal-read loudness: closing a batch cancels its token so parked executions are
 * interrupted within the grace, {@code valuesOrThrow} prefers a recorded element failure over the
 * cancellation that followed it, an interrupted caller gets the lean translation with the flag
 * restored, and the combined wrapper reports unfinished executions truthfully.
 */
class TaskBatchCleanupTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void closingABatchCancelsItsTokenSoParkedExecutionsExit() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            element -> {
                                started.countDown();
                                return awaitRelease(release, element);
                            },
                            BatchOptions.timeout("batch", TIMEOUT).closeGrace(Duration.ofSeconds(5)));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            batch.close();
            TaskBatchResult<Integer> frozen = batch.finish();

            assertThat(frozen.executionExitConfirmed())
                    .as("the close grace cancels the batch token, interrupting parked executions")
                    .isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void valuesOrThrowPrefersARecordedFailureOverTheFollowingCancellation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        IllegalStateException boom = new IllegalStateException("boom");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            element -> {
                                if (element == 1) {
                                    throw boom;
                                }
                                started.countDown();
                                return awaitRelease(release, element);
                            },
                            BatchOptions.timeout("batch", TIMEOUT));
            // Wait until the failure is recorded and the fail-fast cascade cancelled the sibling,
            // so valuesOrThrow sees a cancelled aggregate with a recorded first failure.
            Awaitility.await()
                    .atMost(5, TimeUnit.SECONDS)
                    .until(() -> batch.report().firstException() != null);

            assertThatThrownBy(batch::valuesOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(boom);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void anInterruptedValuesOrThrowCallerGetsALeanCancellationWithTheFlagRestored() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean flagRestored = new AtomicBoolean(false);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1),
                            element -> {
                                started.countDown();
                                return awaitRelease(release, element);
                            },
                            BatchOptions.timeout("batch", TIMEOUT));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            Thread waiter = new Thread(() -> {
                try {
                    batch.valuesOrThrow();
                } catch (Throwable thrown) {
                    outcome.set(thrown);
                }
                flagRestored.set(Thread.currentThread().isInterrupted());
            });
            waiter.start();
            Thread.sleep(200L);
            waiter.interrupt();
            waiter.join(5000L);

            assertThat(outcome.get()).isInstanceOf(LeanCancellationException.class);
            assertThat(flagRestored.get())
                    .as("the interrupt flag is restored per the interruption convention")
                    .isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void valuesOrThrowSurfacesAPureCancellationAsCancellation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1),
                            element -> {
                                started.countDown();
                                return awaitRelease(release, element);
                            },
                            BatchOptions.timeout("batch", TIMEOUT));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            batch.close();
            assertThatThrownBy(batch::valuesOrThrow)
                    .as("a cancelled batch with no recorded failure surfaces the cancellation itself")
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aCombinedBatchReportsItsUnfinishedExecutionsThroughTheWrapper() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // The deadline cancels the element's future while its execution ignores the interrupt and
            // parks: the synchronous mapAndCombine freezes its results with a provably unfinished
            // execution, and the wrapper must report that state rather than a clean confirmation.
            BatchCombinedResult<Integer, Integer> combined = global.par(ParId.of("worker"))
                    .mapAndCombine(
                            Arrays.asList(1),
                            element -> {
                                started.countDown();
                                return awaitReleaseIgnoringInterrupt(release, element);
                            },
                            BatchOptions.timeout("batch", Duration.ofMillis(300)),
                            global.par(ParId.of("worker")),
                            values -> 42);

            assertThat(started.getCount()).isZero();
            assertThat(combined.executionExitConfirmed())
                    .as("an execution that outlives its cancelled future is not confirmed exited")
                    .isFalse();
            assertThat(combined.unexitedExecutions()).isNotEmpty();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    private static Integer awaitRelease(CountDownLatch release, Integer element) {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return element;
    }

    private static Integer awaitReleaseIgnoringInterrupt(CountDownLatch release, Integer element) {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException parked) {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // test-owned execution; released in the finally below
            }
        }
        return element;
    }
}

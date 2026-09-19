package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ParRuntime#snapshot()}: a diagnostic sample of the drain state. The assertions pin its
 * contents and its settling behavior, not atomicity — the snapshot deliberately claims no
 * linearizable guarantee.
 */
// JUnit-style: 'runtime' is assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class ParRuntimeSnapshotTest {

    private ParRuntime runtime;

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void freshRuntimeReportsAnEmptyOpenSnapshot() {
        runtime = ParRuntime.builder().build();
        ParRuntimeSnapshot snapshot = runtime.snapshot();
        assertThat(snapshot.closed()).isFalse();
        assertThat(snapshot.activeAdmissions()).isZero();
        assertThat(snapshot.undrainedBatches()).isZero();
        assertThat(snapshot.unexitedBodySignals()).isZero();
        assertThat(snapshot.toString()).contains("closed=false");
    }

    @Test
    void snapshotDistinguishesUndrainedFuturesFromUnexitedBodies() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread caller = new Thread(
                    () -> runtime.par(ParId.of("worker"))
                            .map(
                                    Arrays.asList(1),
                                    value -> {
                                        started.countDown();
                                        try {
                                            release.await(30, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return value;
                                    },
                                    BatchOptions.timeout("blocked", Duration.ofSeconds(30))),
                    "snapshot-caller");
            caller.setDaemon(true);
            caller.start();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            await().untilAsserted(() -> {
                ParRuntimeSnapshot snapshot = runtime.snapshot();
                assertThat(snapshot.undrainedBatches()).isEqualTo(1);
                assertThat(snapshot.unexitedBodySignals()).isEqualTo(1);
            });

            runtime.close();
            assertThat(runtime.snapshot().closed()).isTrue();

            release.countDown();
            assertThat(runtime.awaitQuiescence(Duration.ofSeconds(10))).isTrue();
            ParRuntimeSnapshot drained = runtime.snapshot();
            assertThat(drained.undrainedBatches()).isZero();
            assertThat(drained.unexitedBodySignals()).isZero();
            caller.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneBatchWithManyOutstandingBodiesContributesOneBodySignal() throws Exception {
        // The body-signal counter's unit is the admitted run: a batch whose bodies are all still
        // running contributes exactly one signal, not one per body.
        ExecutorService executor = Executors.newFixedThreadPool(3);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            CountDownLatch started = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            Thread caller = new Thread(
                    () -> runtime.par(ParId.of("worker"))
                            .map(
                                    Arrays.asList(1, 2, 3),
                                    value -> {
                                        started.countDown();
                                        try {
                                            release.await(30, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return value;
                                    },
                                    BatchOptions.timeout("multi-body", Duration.ofSeconds(30))),
                    "snapshot-multi-body-caller");
            caller.setDaemon(true);
            caller.start();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            await().untilAsserted(() -> {
                ParRuntimeSnapshot snapshot = runtime.snapshot();
                assertThat(snapshot.undrainedBatches()).isEqualTo(1);
                assertThat(snapshot.unexitedBodySignals()).isEqualTo(1);
            });

            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }
}

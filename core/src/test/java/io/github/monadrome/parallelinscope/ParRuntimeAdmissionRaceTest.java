package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Deterministic cover for the admission/close race (registry claim
 * {@code runtime-admission-vs-close}): the entire startup is one admission, so a close that races
 * it cannot retire the framework services — including the deadline scheduler — while the admission
 * is still setting up. The test holds one admission open across {@code close()} and asserts
 * quiescence stays unreached until the admission exits; dropping the {@code activeAdmissions}
 * check in {@code shutdownServicesWhenAdmissionsComplete} retires the services under the
 * in-flight admission and fails the first assertion.
 */
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ParRuntimeAdmissionRaceTest {

    @Test
    void closeWithAnAdmissionInFlightDoesNotRetireServicesUntilTheAdmissionExits() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ExecutorService admissionThread = Executors.newSingleThreadExecutor();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("io"), pool)
                .defaultPar(ParId.of("io"))
                .build();
        CountDownLatch admissionEntered = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        try {
            Future<?> admission = admissionThread.submit(() -> runtime.whileOpen(() -> {
                admissionEntered.countDown();
                await(releaseAdmission);
                return null;
            }));
            assertThat(admissionEntered.await(5, TimeUnit.SECONDS))
                    .as("the admission is in flight")
                    .isTrue();
            assertThat(runtime.closed()).isFalse();
            assertThat(runtime.inFlight())
                    .as("the open admission counts as in-flight work")
                    .isOne();

            runtime.close();

            assertThat(runtime.awaitQuiescence(Duration.ofMillis(100)))
                    .as("an in-flight admission pins the framework services across close()")
                    .isFalse();

            releaseAdmission.countDown();
            admission.get(5, TimeUnit.SECONDS);

            assertThat(runtime.awaitQuiescence(Duration.ofSeconds(5)))
                    .as("the last admission's exit lets close() retire the services")
                    .isTrue();
            assertThat(runtime.inFlight()).isZero();
        } finally {
            releaseAdmission.countDown();
            admissionThread.shutdownNow();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

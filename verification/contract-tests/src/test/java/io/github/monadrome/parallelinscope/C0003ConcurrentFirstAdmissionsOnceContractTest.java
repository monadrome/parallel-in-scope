package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT-TDD-012 (contract C-3 of assurance run 2026-11-24-remove-tasktype-workload-fit).
 *
 * <p>Contract: adr/0016-remove-tasktype-and-workload-fit-screening.md, "Decision" item 2;
 * design/task-group-api-and-options.md, "3.2.1 Admission diagnostics"; docs/en/user-guide.md,
 * "Execute a batch"; design/task-group-adopted-futures.md, "2. Contract" item A7.
 *
 * <p>Precondition: one entry on a plain executor with several idle workers.
 *
 * <p>Operation sequence: eight threads admit a refusing single-element batch through the same
 * entry simultaneously, released together by a start latch.
 *
 * <p>Observation points: JUL records naming the entry id.
 *
 * <p>Assertions: the entry still causes exactly one {@code WARNING}, even under concurrent first
 * admissions; the count stays one after a 1000 millisecond window.
 *
 * <p>Uncertainty: the once-only guarantee is asserted after all admissions return; a record that
 * arrived late would still be within the stated window.
 */
@Tag("C-0003")
class C0003ConcurrentFirstAdmissionsOnceContractTest {

    @Test
    void concurrentFirstAdmissionsStillWarnOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ParId id = ParId.of("ct012-entry");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            BatchOptions refusing =
                    BatchOptions.timeout("ct012-batch", Duration.ofSeconds(30)).rejectEnqueue(true);
            List<Integer> one = Arrays.asList(1);

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        par.map(one, el -> el, refusing);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                workers[i].start();
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Thread worker : workers) {
                worker.join(TimeUnit.SECONDS.toMillis(20));
            }

            handler.awaitCountContaining("ct012-entry", 1, 10);
            assertThat(handler.countContaining("ct012-entry")).isEqualTo(1);
            assertThat(handler.firstContaining("ct012-entry").getLevel()).isEqualTo(Level.WARNING);
            Thread.sleep(1000);
            assertThat(handler.countContaining("ct012-entry")).isEqualTo(1);
        } finally {
            logger.removeHandler(handler);
            pool.shutdownNow();
        }
    }

    private static final class CaptureHandler extends Handler {
        final List<LogRecord> records = Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() == Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        int countContaining(String fragment) {
            int count = 0;
            for (LogRecord record : records) {
                if (record.getMessage() != null && record.getMessage().contains(fragment)) {
                    count++;
                }
            }
            return count;
        }

        LogRecord firstContaining(String fragment) {
            for (LogRecord record : records) {
                if (record.getMessage() != null && record.getMessage().contains(fragment)) {
                    return record;
                }
            }
            return null;
        }

        void awaitCountContaining(String fragment, int expected, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (countContaining(fragment) < expected && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }
    }
}

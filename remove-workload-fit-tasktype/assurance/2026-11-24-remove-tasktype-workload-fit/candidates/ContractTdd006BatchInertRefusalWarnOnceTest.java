package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-006 (contract C-3).
 *
 * <p>Precondition: one entry on a plain executor ({@code Executors.newFixedThreadPool}). Operation
 * sequence: admit a refusing batch, wait for its warning, admit a second refusing batch, then a
 * non-refusing batch.
 *
 * <p>Observation points: JUL records published under the
 * {@code io.github.monadrome.parallelinscope} logger whose message names this entry id.
 *
 * <p>Assertions: exactly one {@code WARNING} whose message starts with
 * {@code Par 'ct006-entry' requested rejectEnqueue}; the later refusing and non-refusing admissions
 * produce no further record within an observed window of 800 milliseconds.
 *
 * <p>Uncertainty: absence is a bounded-window observation per O-2; the 800 ms window is stated here.
 */
public class ContractTdd006BatchInertRefusalWarnOnceTest {

    @Test
    void batchInertRefusalWarnsOncePerEntry() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParId id = ParId.of("ct006-entry");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler("ct006-entry");
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            BatchOptions refusing = BatchOptions.timeout("ct006-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);
            BatchOptions normal = BatchOptions.timeout("ct006-batch", Duration.ofSeconds(30));
            List<Integer> one = Arrays.asList(1);

            par.map(one, el -> el, refusing);
            handler.awaitCount(1, 10);
            assertThat(handler.count()).isEqualTo(1);
            assertThat(handler.records.get(0).getLevel()).isEqualTo(Level.WARNING);
            assertThat(handler.records.get(0).getMessage())
                    .startsWith("Par 'ct006-entry' requested rejectEnqueue");

            par.map(one, el -> el, refusing);
            sleepWindow(800);
            assertThat(handler.count()).isEqualTo(1);

            par.map(one, el -> el, normal);
            sleepWindow(800);
            assertThat(handler.count()).isEqualTo(1);
        } finally {
            logger.removeHandler(handler);
            pool.shutdownNow();
        }
    }

    private static void sleepWindow(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static final class CaptureHandler extends Handler {
        final String fragment;
        final List<LogRecord> records = Collections.synchronizedList(new java.util.ArrayList<>());

        CaptureHandler(String fragment) {
            this.fragment = fragment;
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() == Level.WARNING.intValue()
                    && record.getMessage() != null
                    && record.getMessage().contains(fragment)) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        int count() {
            return records.size();
        }

        void awaitCount(int expected, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (count() < expected && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }
    }
}

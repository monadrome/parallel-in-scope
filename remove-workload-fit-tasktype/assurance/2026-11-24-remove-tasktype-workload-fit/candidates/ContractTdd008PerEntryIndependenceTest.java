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
 * CONTRACT-TDD-008 (contract C-3).
 *
 * <p>Precondition: two distinct entry ids registered with the same plain executor object
 * ({@code Executors.newFixedThreadPool}).
 *
 * <p>Operation sequence: admit a refusing batch through entry A, then a refusing batch through
 * entry B.
 *
 * <p>Observation points: JUL records naming each entry id.
 *
 * <p>Assertions: each entry causes its own single {@code WARNING} naming that entry; the record for
 * entry A is never suppressed by entry B and vice versa.
 *
 * <p>Uncertainty: none; each once-only assertion is made immediately after its own admission.
 */
public class ContractTdd008PerEntryIndependenceTest {

    @Test
    void recordsAreIndependentPerEntry() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParId idA = ParId.of("ct008-a");
        ParId idB = ParId.of("ct008-b");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder()
                .register(idA, pool)
                .register(idB, pool)
                .build()) {
            Par parA = runtime.par(idA);
            Par parB = runtime.par(idB);
            BatchOptions refusing = BatchOptions.timeout("ct008-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);
            List<Integer> one = Arrays.asList(1);

            parA.map(one, el -> el, refusing);
            handler.awaitCountContaining("ct008-a", 1, 10);
            assertThat(handler.countContaining("ct008-a")).isEqualTo(1);
            assertThat(handler.firstContaining("ct008-a").getLevel()).isEqualTo(Level.WARNING);

            parB.map(one, el -> el, refusing);
            handler.awaitCountContaining("ct008-b", 1, 10);
            assertThat(handler.countContaining("ct008-b")).isEqualTo(1);
            assertThat(handler.firstContaining("ct008-b").getLevel()).isEqualTo(Level.WARNING);
            assertThat(handler.countContaining("ct008-a")).isEqualTo(1);
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
        public void flush() {
        }

        @Override
        public void close() {
        }

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

        void awaitCountContaining(String fragment, int expected, long seconds)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (countContaining(fragment) < expected && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }
    }
}

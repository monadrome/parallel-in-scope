package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-009 (contract C-3).
 *
 * <p>Precondition: one entry on a refusal-capable executor ({@code SubmissionAwareBlockingQueue}).
 *
 * <p>Operation sequence: admit a refusing batch through the entry.
 *
 * <p>Observation points: JUL records naming the entry id over a bounded window.
 *
 * <p>Assertions: no warning record is published for the entry within a 1000 millisecond window,
 * because the refusal is effective rather than inert on this executor.
 *
 * <p>Uncertainty: absence is a bounded-window observation (O-2); the 1000 ms window is stated here.
 */
public class ContractTdd009RefusingOnRefusalCapableNoWarnTest {

    @Test
    void refusingOnRefusalCapableEntryProducesNoWarning() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ParId id = ParId.of("ct009-entry");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            BatchOptions refusing = BatchOptions.timeout("ct009-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);
            par.map(Arrays.asList(1), el -> el, refusing);
            Thread.sleep(1000);
            assertThat(handler.countContaining("ct009-entry")).isZero();
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
    }
}

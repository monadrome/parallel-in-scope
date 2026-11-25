package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Combiner;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-010 (contract C-3).
 *
 * <p>Precondition: four entries on one runtime — a refusal-capable batch entry and a plain combine
 * entry for phase A, plus a plain batch entry and a refusal-capable combine entry for phase B.
 *
 * <p>Operation sequence: phase A runs {@code mapAndCombine} with a refusing batch on the
 * refusal-capable entry and the combine on the plain entry; phase B runs it with a refusing batch
 * on the plain entry and the combine on the refusal-capable entry.
 *
 * <p>Observation points: JUL records naming the batch and combine entries.
 *
 * <p>Assertions: phase A publishes no warning; phase B publishes exactly one {@code WARNING}
 * starting with {@code Par 'ct010-plainBatch' requested rejectEnqueue} and none for the
 * refusal-capable combine entry, because the terminal combine requests no refusal.
 *
 * <p>Uncertainty: absence is a bounded-window observation; phase A's window is 800 ms.
 */
public class ContractTdd010MapAndCombineCombineNoRefusalWarnTest {

    @Test
    void mapAndCombineTerminalCombineRequestsNoRefusalWarning() throws Exception {
        ThreadPoolExecutor rcBatchPool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ThreadPoolExecutor rcCombinePool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ExecutorService plainBatchPool = Executors.newFixedThreadPool(2);
        ExecutorService plainCombinePool = Executors.newFixedThreadPool(2);

        ParId rcBatch = ParId.of("ct010-rcBatch");
        ParId rcCombine = ParId.of("ct010-rcCombine");
        ParId plainBatch = ParId.of("ct010-plainBatch");
        ParId plainCombine = ParId.of("ct010-plainCombine");

        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder()
                .register(rcBatch, rcBatchPool)
                .register(rcCombine, rcCombinePool)
                .register(plainBatch, plainBatchPool)
                .register(plainCombine, plainCombinePool)
                .build()) {
            BatchOptions refusing = BatchOptions.timeout("ct010-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);
            Combiner<List<Integer>, Integer> combiner = values -> values.size();
            List<Integer> elements = Arrays.asList(1, 2);

            Par rcBatchPar = runtime.par(rcBatch);
            Par rcCombinePar = runtime.par(rcCombine);
            Par plainBatchPar = runtime.par(plainBatch);
            Par plainCombinePar = runtime.par(plainCombine);

            assertThat(rcBatchPar.mapAndCombine(elements, el -> el, refusing, plainCombinePar, combiner)
                    .terminalResult().successful()).isTrue();
            Thread.sleep(800);
            assertThat(handler.countContaining("ct010-rcBatch")).isZero();
            assertThat(handler.countContaining("ct010-plainCombine")).isZero();

            assertThat(plainBatchPar.mapAndCombine(elements, el -> el, refusing, rcCombinePar, combiner)
                    .terminalResult().successful()).isTrue();
            handler.awaitCountContaining("ct010-plainBatch", 1, 10);
            assertThat(handler.countContaining("ct010-plainBatch")).isEqualTo(1);
            assertThat(handler.firstContaining("ct010-plainBatch").getLevel()).isEqualTo(Level.WARNING);
            assertThat(handler.firstContaining("ct010-plainBatch").getMessage())
                    .startsWith("Par 'ct010-plainBatch' requested rejectEnqueue");
            assertThat(handler.countContaining("ct010-rcCombine")).isZero();
        } finally {
            logger.removeHandler(handler);
            rcBatchPool.shutdownNow();
            rcCombinePool.shutdownNow();
            plainBatchPool.shutdownNow();
            plainCombinePool.shutdownNow();
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

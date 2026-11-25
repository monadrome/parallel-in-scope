package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-013 (contract C-4).
 *
 * <p>Precondition: one entry on a plain executor; a handler under O-1 that records the publishing
 * thread and blocks on a latch when a matching record is published.
 *
 * <p>Operation sequence: a dedicated thread admits a refusing batch; the test waits for the
 * handler to start, joins the admitting thread while the handler is still blocked, then releases
 * the handler.
 *
 * <p>Observation points: the publishing thread, whether the record is delivered before release,
 * and the admitting thread's return.
 *
 * <p>Assertions: the admitting thread returns while the handler is still blocked (a blocking
 * handler never delays the caller); the publishing thread is not the admitting thread; the record
 * is published only after the handler is released.
 *
 * <p>Uncertainty: the runtime-owned thread's identity is not fixed; only "not the admitting
 * thread" is asserted.
 */
public class ContractTdd013OffThreadBlockingHandlerTest {

    @Test
    void diagnosticIsDeliveredOffThreadAndNeverBlocksCaller() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParId id = ParId.of("ct013-entry");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        BlockingHandler handler = new BlockingHandler("ct013-entry");
        logger.addHandler(handler);

        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            BatchOptions refusing = BatchOptions.timeout("ct013-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);
            List<Integer> one = Arrays.asList(1);

            Thread admitThread = new Thread(() -> {
                try {
                    resultRef.set(par.map(one, el -> el, refusing));
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            });
            admitThread.start();

            assertThat(handler.started.await(10, TimeUnit.SECONDS)).isTrue();
            admitThread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(admitThread.isAlive()).isFalse();
            assertThat(errorRef.get()).isNull();
            assertThat(handler.records).isEmpty();
            assertThat(handler.publishThread.get()).isNotNull();
            assertThat(handler.publishThread.get()).isNotSameAs(admitThread);

            handler.release.countDown();
            assertThat(handler.delivered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.records).hasSize(1);
            assertThat(handler.records.get(0).getLevel()).isEqualTo(Level.WARNING);
            assertThat(handler.records.get(0).getMessage())
                    .startsWith("Par 'ct013-entry' requested rejectEnqueue");
            assertThat(resultRef.get().termination().successful()).isTrue();
        } finally {
            handler.release.countDown();
            logger.removeHandler(handler);
            pool.shutdownNow();
        }
    }

    private static final class BlockingHandler extends Handler {
        final String fragment;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<Thread> publishThread = new AtomicReference<>();
        final List<LogRecord> records = Collections.synchronizedList(new java.util.ArrayList<>());

        BlockingHandler(String fragment) {
            this.fragment = fragment;
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() != null && record.getMessage().contains(fragment)) {
                publishThread.set(Thread.currentThread());
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                records.add(record);
                delivered.countDown();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}

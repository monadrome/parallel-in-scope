package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-014 (contract C-4).
 *
 * <p>Precondition: one entry on a plain executor; a handler under O-1 that throws a
 * {@link RuntimeException} while publishing a matching record.
 *
 * <p>Operation sequence: admit a refusing batch, then an identical non-refusing batch with the
 * same mapping function.
 *
 * <p>Observation points: whether the {@code map} calls return and their frozen values.
 *
 * <p>Assertions: a throwing handler changes no outcome — the refusing batch returns normally with
 * the same values and success termination as the identical non-refusing batch; the exception never
 * reaches the caller; only that record is lost.
 *
 * <p>Uncertainty: "only that record is lost" is asserted indirectly; the record list stays empty
 * because the throwing handler never stores it.
 */
public class ContractTdd014ThrowingHandlerNoOutcomeChangeTest {

    @Test
    void throwingHandlerDoesNotChangeOutcome() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParId id = ParId.of("ct014-entry");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        ThrowingHandler handler = new ThrowingHandler("ct014-entry");
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            BatchOptions refusing = BatchOptions.timeout("ct014-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);
            BatchOptions normal = BatchOptions.timeout("ct014-batch", Duration.ofSeconds(30));
            List<Integer> input = Arrays.asList(1, 2, 3);

            TaskBatchResult<Integer> refusingResult = par.map(input, el -> el * 2, refusing);
            handler.awaitInvoked(1, 10);
            assertThat(refusingResult.termination().successful()).isTrue();
            assertThat(refusingResult.valuesOrThrow()).containsExactly(2, 4, 6);

            TaskBatchResult<Integer> normalResult = par.map(input, el -> el * 2, normal);
            assertThat(normalResult.termination().successful()).isTrue();
            assertThat(normalResult.valuesOrThrow()).containsExactly(2, 4, 6);
            assertThat(refusingResult.valuesOrThrow()).isEqualTo(normalResult.valuesOrThrow());
        } finally {
            logger.removeHandler(handler);
            pool.shutdownNow();
        }
    }

    private static final class ThrowingHandler extends Handler {
        final String fragment;
        final CountDownLatch invoked = new CountDownLatch(1);

        ThrowingHandler(String fragment) {
            this.fragment = fragment;
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() != null && record.getMessage().contains(fragment)) {
                invoked.countDown();
                throw new RuntimeException("logging failure");
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        void awaitInvoked(int expected, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (invoked.getCount() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }
    }
}

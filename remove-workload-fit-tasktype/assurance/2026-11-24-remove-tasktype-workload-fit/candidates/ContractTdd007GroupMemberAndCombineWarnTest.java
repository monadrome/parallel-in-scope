package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.Combiner;
import io.github.monadrome.parallelinscope.GroupStart;
import io.github.monadrome.parallelinscope.GroupStep;
import io.github.monadrome.parallelinscope.GroupValues;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskGroupResult;
import io.github.monadrome.parallelinscope.TaskKey;
import io.github.monadrome.parallelinscope.TaskOptions;
import java.time.Duration;
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
 * CONTRACT-TDD-007 (contract C-3).
 *
 * <p>Precondition: two entries on one plain executor ({@code Executors.newFixedThreadPool}). The
 * operation sequence admits a refusing group member through the member entry, then a refusing group
 * combine through the combine entry (with a non-refusing member on the member entry).
 *
 * <p>Observation points: JUL records under {@code io.github.monadrome.parallelinscope} naming each
 * entry id.
 *
 * <p>Assertions: the member admission causes exactly one {@code WARNING} starting with
 * {@code Par 'ct007-member' requested rejectEnqueue}; the combine admission causes exactly one
 * {@code WARNING} starting with {@code Par 'ct007-combine' requested rejectEnqueue}; the
 * non-refusing member causes none.
 *
 * <p>Uncertainty: absence of a further record is a bounded-window observation; each once-only
 * assertion is made immediately after its own admission with an 800 ms window.
 */
public class ContractTdd007GroupMemberAndCombineWarnTest {

    @Test
    void groupMemberAndCombineEachWarnOnceForTheirEntry() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParId memberId = ParId.of("ct007-member");
        ParId combineId = ParId.of("ct007-combine");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder()
                .register(memberId, pool)
                .register(combineId, pool)
                .build()) {
            Par memberPar = runtime.par(memberId);
            Par combinePar = runtime.par(combineId);

            TaskKey<Integer> memberKey = TaskKey.named("ct007-member-key");
            GroupStart start = runtime.group("ct007-group-member", Duration.ofSeconds(30));
            GroupStep step = start.par(memberKey, memberPar,
                    TaskOptions.timeout(Duration.ofSeconds(10)).rejectEnqueue(true),
                    () -> 7);
            TaskGroupResult<Void> memberResult = step.runAll();
            assertThat(memberResult.termination().successful()).isTrue();
            handler.awaitCountContaining("ct007-member", 1, 10);
            assertThat(handler.countContaining("ct007-member")).isEqualTo(1);
            assertThat(handler.firstContaining("ct007-member").getLevel()).isEqualTo(Level.WARNING);
            assertThat(handler.firstContaining("ct007-member").getMessage())
                    .startsWith("Par 'ct007-member' requested rejectEnqueue");
            sleepWindow(800);
            assertThat(handler.countContaining("ct007-member")).isEqualTo(1);

            TaskKey<String> combineKey = TaskKey.named("ct007-combine-key");
            TaskKey<Integer> plainKey = TaskKey.named("ct007-plain-key");
            GroupStart start2 = runtime.group("ct007-group-combine", Duration.ofSeconds(30));
            GroupStep step2 = start2.par(plainKey, memberPar,
                    TaskOptions.timeout(Duration.ofSeconds(10)),
                    () -> 1);
            TaskGroupResult<String> combineResult = step2.combine(combineKey, combinePar,
                    TaskOptions.timeout(Duration.ofSeconds(10)).rejectEnqueue(true),
                    (Combiner<GroupValues, String>) values -> "size=" + values.size()).runAll();
            assertThat(combineResult.termination().successful()).isTrue();
            assertThat(combineResult.terminalValueOrThrow()).isEqualTo("size=1");
            handler.awaitCountContaining("ct007-combine", 1, 10);
            assertThat(handler.countContaining("ct007-combine")).isEqualTo(1);
            assertThat(handler.firstContaining("ct007-combine").getLevel()).isEqualTo(Level.WARNING);
            assertThat(handler.firstContaining("ct007-combine").getMessage())
                    .startsWith("Par 'ct007-combine' requested rejectEnqueue");
            assertThat(handler.countContaining("ct007-member")).isEqualTo(1);
        } finally {
            logger.removeHandler(handler);
            pool.shutdownNow();
        }
    }

    private static void sleepWindow(long millis) throws InterruptedException {
        Thread.sleep(millis);
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

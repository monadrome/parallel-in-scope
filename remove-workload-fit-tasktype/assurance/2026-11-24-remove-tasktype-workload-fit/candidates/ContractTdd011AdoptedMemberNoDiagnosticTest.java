package io.github.monadrome.parallelinscope.assurance;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.monadrome.parallelinscope.GroupStart;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskGroupResult;
import io.github.monadrome.parallelinscope.TaskKey;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-011 (contract C-3).
 *
 * <p>Precondition: one entry on a plain executor and a caller-owned, already-completed
 * {@link ListenableFuture} adopted as the group's only member.
 *
 * <p>Operation sequence: admit the group with the adopted member via {@code runAll()}.
 *
 * <p>Observation points: JUL records at {@code WARNING} or above naming this candidate's id.
 *
 * <p>Assertions: the group succeeds and no admission diagnostic is published for the adopted
 * member within a 1000 millisecond window.
 *
 * <p>Uncertainty: absence is a bounded-window observation (O-2); the 1000 ms window is stated here.
 */
public class ContractTdd011AdoptedMemberNoDiagnosticTest {

    @Test
    void adoptedMemberUndergoesNoAdmissionDiagnostic() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParId id = ParId.of("ct011-entry");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            TaskKey<String> key = TaskKey.named("ct011-adopted-key");
            ListenableFuture<String> source = Futures.immediateFuture("value");
            GroupStart start = runtime.group("ct011-group", Duration.ofSeconds(30));
            TaskGroupResult<Void> result = start.adopt(key, source).runAll();
            assertThat(result.termination().successful()).isTrue();
            Thread.sleep(1000);
            assertThat(handler.countWarningContaining("ct011-")).isZero();
        } finally {
            logger.removeHandler(handler);
            pool.shutdownNow();
        }
    }

    private static final class CaptureHandler extends Handler {
        final List<LogRecord> records = Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        int countWarningContaining(String fragment) {
            int count = 0;
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                        && record.getMessage() != null
                        && record.getMessage().contains(fragment)) {
                    count++;
                }
            }
            return count;
        }
    }
}

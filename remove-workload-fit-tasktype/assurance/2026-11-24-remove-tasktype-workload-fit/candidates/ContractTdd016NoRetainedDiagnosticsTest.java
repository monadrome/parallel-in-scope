package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Combiner;
import io.github.monadrome.parallelinscope.FailureCause;
import io.github.monadrome.parallelinscope.GroupStart;
import io.github.monadrome.parallelinscope.GroupStep;
import io.github.monadrome.parallelinscope.GroupValues;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskGroupResult;
import io.github.monadrome.parallelinscope.TaskKey;
import io.github.monadrome.parallelinscope.TaskOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-016 (contract C-5).
 *
 * <p>Precondition: entries on refusal-capable {@code ThreadPoolExecutor}s, default options, no
 * nested waits, no open starvation observation.
 *
 * <p>Operation sequence: run a batch whose elements block (sleep) and where one throws, then a
 * group whose members block and whose combine succeeds.
 *
 * <p>Observation points: element and member values, the batch termination, the failed element's
 * cause, the group terminal value, and JUL records at {@code INFO} or above naming this candidate.
 *
 * <p>Assertions: blocking work completes with its values and a {@code USER_CODE_FAILURE} failure
 * attribution; no workload-fit, task-type, or CPU-utilization record — no library record at
 * {@code INFO} or above — is published within a 1500 millisecond window.
 *
 * <p>Uncertainty: absence is a bounded-window observation (O-2); the 1500 ms window is stated
 * here.
 */
public class ContractTdd016NoRetainedDiagnosticsTest {

    @Test
    void blockingWorkKeepsOutcomesAndPublishesNoRetainedDiagnostics() throws Exception {
        ThreadPoolExecutor batchPool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ThreadPoolExecutor groupPool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ParId batchId = ParId.of("ct016-batch");
        ParId groupId = ParId.of("ct016-group");
        Logger logger = Logger.getLogger("io.github.monadrome.parallelinscope");
        CaptureHandler handler = new CaptureHandler();
        logger.addHandler(handler);

        try (ParRuntime runtime = ParRuntime.builder()
                .register(batchId, batchPool)
                .register(groupId, groupPool)
                .build()) {
            Par batchPar = runtime.par(batchId);
            Par groupPar = runtime.par(groupId);

            BatchOptions batchOptions = BatchOptions.timeout("ct016-batch", Duration.ofSeconds(30));
            TaskBatchResult<String> batch = batchPar.map(Arrays.asList(0, 1),
                    el -> {
                        if (el == 0) {
                            Thread.sleep(50);
                            return "a";
                        }
                        Thread.sleep(200);
                        throw new IllegalStateException("boom");
                    },
                    batchOptions);

            assertThat(batch.results()).hasSize(2);
            assertThat(batch.results().get(0).successful()).isTrue();
            assertThat(batch.results().get(0).valueOrThrow()).isEqualTo("a");
            assertThat(batch.results().get(1).successful()).isFalse();
            assertThat(batch.results().get(1).failureCause()).isNotNull();
            assertThat(batch.results().get(1).failureCause().kind())
                    .isEqualTo(FailureCause.Kind.USER_CODE_FAILURE);
            assertThat(batch.termination().successful()).isFalse();

            TaskKey<Integer> leftKey = TaskKey.named("ct016-left");
            TaskKey<Integer> rightKey = TaskKey.named("ct016-right");
            TaskKey<Integer> combineKey = TaskKey.named("ct016-combine");
            GroupStart start = runtime.group("ct016-group", Duration.ofSeconds(30));
            GroupStep step = start.par(leftKey, groupPar,
                    TaskOptions.timeout(Duration.ofSeconds(20)), () -> {
                        Thread.sleep(100);
                        return 5;
                    });
            step = step.par(rightKey, groupPar,
                    TaskOptions.timeout(Duration.ofSeconds(20)), () -> {
                        Thread.sleep(100);
                        return 6;
                    });
            TaskGroupResult<Integer> group = step.combine(combineKey, groupPar,
                    TaskOptions.timeout(Duration.ofSeconds(20)),
                    (Combiner<GroupValues, Integer>) values ->
                            values.valueOf(leftKey) + values.valueOf(rightKey)).runAll();

            assertThat(group.termination().successful()).isTrue();
            assertThat(group.terminalValueOrThrow()).isEqualTo(11);

            Thread.sleep(1500);
            assertThat(handler.countInfoOrAboveContaining("ct016-")).isZero();
        } finally {
            logger.removeHandler(handler);
            batchPool.shutdownNow();
            groupPool.shutdownNow();
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

        int countInfoOrAboveContaining(String fragment) {
            int count = 0;
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.INFO.intValue()
                        && record.getMessage() != null
                        && record.getMessage().contains(fragment)) {
                    count++;
                }
            }
            return count;
        }
    }
}

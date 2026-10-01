package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code rejectEnqueue} and {@link TaskType#CPU_BOUND} are the two ways a unit asks to be refused
 * enqueue, and only {@link SmartBlockingQueue} reads either. On any other queue both are inert, so
 * both must reach the same once-per-Par warning: before, a {@code CPU_BOUND} batch on a plain pool
 * was silently buffered while the inert {@code rejectEnqueue} was reported.
 */
@SuppressWarnings("NullAway.Init")
class InertEnqueueRefusalWarningTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private final Logger parLogger = Logger.getLogger(Par.class.getName());

    private ExecutorService plain;
    private ExecutorService smart;
    private ParRuntime runtime;

    @BeforeEach
    void captureParWarnings() {
        parLogger.addHandler(capture);
        plain = Executors.newFixedThreadPool(2);
        smart = new ThreadPoolExecutor(1, 2, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(4));
        runtime = ParRuntime.builder()
                .register(ParId.of("plain"), plain)
                .register(ParId.of("smart"), smart)
                .build();
    }

    @AfterEach
    void release() {
        parLogger.removeHandler(capture);
        runtime.close();
        plain.shutdownNow();
        smart.shutdownNow();
    }

    @Test
    void aCpuBoundBatchOnAPlainQueueIsReportedLikeAnInertRejectEnqueue() {
        Par par = runtime.par(ParId.of("plain"));

        par.map(Collections.singletonList(1), x -> x, BatchOptions.timeout("default", TIMEOUT));
        assertThat(records).isEmpty();

        par.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("cpu", TIMEOUT).taskType(TaskType.CPU_BOUND));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(records.get(0).getMessage())
                .startsWith("Par 'plain' requested TaskType.CPU_BOUND, but its executor")
                .contains("inert", "SmartBlockingQueue");

        par.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("flag", TIMEOUT).rejectEnqueue(true));
        par.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("cpu-again", TIMEOUT).taskType(TaskType.CPU_BOUND));
        assertThat(records).hasSize(1);
    }

    @Test
    void theWarningNamesRejectEnqueueWhenTheUnitRequestedIt() {
        runtime.par(ParId.of("plain"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("flag", TIMEOUT).rejectEnqueue(true));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMessage()).startsWith("Par 'plain' requested rejectEnqueue, but its executor");
    }

    @Test
    void aCpuBoundBatchOnASmartQueueIsNotReported() {
        runtime.par(ParId.of("smart"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("cpu", TIMEOUT).taskType(TaskType.CPU_BOUND));

        assertThat(records).isEmpty();
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Only {@link SubmissionAwareBlockingQueue} reads {@code rejectEnqueue}, so on any other queue a requested
 * refusal is inert and reaches a once-per-Par warning. A unit that requests no refusal never reaches
 * this warning, on any queue.
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
        smart = new ThreadPoolExecutor(1, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<>(4));
        runtime = ParRuntime.builder()
                .register(ParId.of("plain"), plain)
                .register(ParId.of("smart"), smart)
                .announcements(MoreExecutors.directExecutor())
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
    void aDefaultBatchOnAPlainQueueRequestsNoRefusalAndIsNotReported() {
        Par par = runtime.par(ParId.of("plain"));
        par.map(Collections.singletonList(1), x -> x, BatchOptions.timeout("default", TIMEOUT));

        assertThat(records).isEmpty();
    }

    @Test
    void anInertRejectEnqueueIsReportedOncePerPar() {
        Par par = runtime.par(ParId.of("plain"));
        par.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("flag", TIMEOUT).rejectEnqueue(true));
        par.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("flag-again", TIMEOUT).rejectEnqueue(true));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(records.get(0).getMessage())
                .startsWith("Par 'plain' requested rejectEnqueue, but its executor")
                .contains("inert", "SubmissionAwareBlockingQueue");
    }

    /**
     * The terminal combine of {@code mapAndCombine} declares no refusal of its own, so an inert
     * {@code rejectEnqueue} is reported for the batch's Par only, whichever pool runs the combine.
     */
    @Test
    void aRefusingBatchOnAPlainQueueCombinedOntoASmartQueueWarnsForTheBatchPar() {
        runtime.par(ParId.of("plain"))
                .mapAndCombine(
                        Arrays.asList(1, 2),
                        x -> x,
                        BatchOptions.timeout("flag-combine", TIMEOUT).rejectEnqueue(true),
                        runtime.par(ParId.of("smart")),
                        values -> 0);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMessage()).startsWith("Par 'plain' requested rejectEnqueue, but its executor");
    }

    /**
     * The batch's own queue honors its refusal and the combine requests none, so a plain queue
     * running the combine is not reported.
     */
    @Test
    void aRefusingBatchOnASmartQueueCombinedOntoAPlainQueueIsNotReported() {
        runtime.par(ParId.of("smart"))
                .mapAndCombine(
                        Arrays.asList(1, 2),
                        x -> x,
                        BatchOptions.timeout("smart-flag-combine", TIMEOUT).rejectEnqueue(true),
                        runtime.par(ParId.of("plain")),
                        values -> 0);

        assertThat(records).isEmpty();
    }

    /**
     * The warning is delivered on a runtime-owned thread, so a logging handler that throws never
     * reaches the submitting caller or changes the batch's outcome.
     */
    @Test
    void aFailingLogHandlerNeverChangesTheOutcomeOfAnInertRefusal() throws Exception {
        Set<Thread> publishers = ConcurrentHashMap.newKeySet();
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                publishers.add(Thread.currentThread());
                throw new IllegalStateException("logging backend unavailable");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        parLogger.addHandler(failing);
        try (ParRuntime asynchronous =
                ParRuntime.builder().register(ParId.of("plain"), plain).build()) {
            TaskBatchResult<Integer> result = asynchronous
                    .par(ParId.of("plain"))
                    .map(
                            Arrays.asList(1, 2),
                            x -> x,
                            BatchOptions.timeout("flag", TIMEOUT).rejectEnqueue(true));

            assertThat(result.valuesOrThrow()).containsExactly(1, 2);
            Thread admitting = Thread.currentThread();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(records).hasSize(1);
                assertThat(publishers).isNotEmpty();
            });
            assertThat(publishers).doesNotContain(admitting);
        } finally {
            parLogger.removeHandler(failing);
        }
    }

    @Test
    void aRefusingBatchOnASmartQueueIsNotReported() {
        runtime.par(ParId.of("smart"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("flag", TIMEOUT).rejectEnqueue(true));

        assertThat(records).isEmpty();
    }
}

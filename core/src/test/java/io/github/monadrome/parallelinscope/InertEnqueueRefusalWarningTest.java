package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * Only {@link SubmissionAwareBlockingQueue} reads {@code rejectEnqueue}, so on any other queue a requested
 * refusal is inert and reaches a once-per-Par warning. A {@link TaskType} declaration never requests
 * refusal, so it never reaches this warning, on any queue.
 */
@SuppressWarnings("NullAway.Init")
class InertEnqueueRefusalWarningTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private LogCapture capture;

    private ExecutorService plain;
    private ExecutorService smart;
    private ParRuntime runtime;

    @BeforeEach
    void captureParWarnings() {
        capture = LogCapture.attach(Par.class);
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
        capture.close();
        runtime.close();
        plain.shutdownNow();
        smart.shutdownNow();
    }

    @Test
    void aComputeBatchOnAPlainQueueRequestsNoRefusalAndIsNotReported() {
        Par par = runtime.par(ParId.of("plain"));
        par.map(Collections.singletonList(1), x -> x, BatchOptions.timeout("default", TIMEOUT));
        par.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("compute", TIMEOUT).taskType(TaskType.COMPUTE));

        assertThat(capture.events()).isEmpty();
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

        assertThat(capture.events()).hasSize(1);
        assertThat(capture.events().get(0).level()).isEqualTo(Level.WARN);
        assertThat(capture.events().get(0).message())
                .startsWith("Par 'plain' requested rejectEnqueue, but its executor")
                .contains("inert", "SubmissionAwareBlockingQueue")
                .doesNotContain("TaskType");
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

        assertThat(capture.events()).hasSize(1);
        assertThat(capture.events().get(0).message())
                .startsWith("Par 'plain' requested rejectEnqueue, but its executor");
    }

    @Test
    void aComputeBatchCombinedOntoAPlainQueueIsNotReported() {
        runtime.par(ParId.of("smart"))
                .mapAndCombine(
                        Arrays.asList(1, 2),
                        x -> x,
                        BatchOptions.timeout("compute-combine", TIMEOUT).taskType(TaskType.COMPUTE),
                        runtime.par(ParId.of("plain")),
                        values -> 0);

        assertThat(capture.events()).isEmpty();
    }

    /**
     * The warning is delivered on a runtime-owned thread, so a logging backend that throws never
     * reaches the submitting caller or changes the batch's outcome.
     */
    @Test
    void aFailingLogHandlerNeverChangesTheOutcomeOfAnInertRefusal() throws Exception {
        capture.failOn("");
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
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(capture.events()).hasSize(1));
            assertThat(capture.events().get(0).thread()).isNotSameAs(admitting);
        }
    }

    @Test
    void aRefusingBatchOnASmartQueueIsNotReported() {
        runtime.par(ParId.of("smart"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("flag", TIMEOUT).rejectEnqueue(true));

        assertThat(capture.events()).isEmpty();
    }
}

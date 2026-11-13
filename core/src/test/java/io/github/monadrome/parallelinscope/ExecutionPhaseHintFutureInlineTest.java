package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Inline execution and rejection state of the engine future: the phase hint reaches {@code
 * TERMINAL} on failing runs too, a rejected submission publishes the skipped observation with zero
 * timings, and a broken observation listener leaves a severe diagnostic without stranding the sink.
 */
class ExecutionPhaseHintFutureInlineTest {

    @Test
    void thePhaseHintReachesTerminalAfterAFailingInlineRun() throws Exception {
        ExecutionPhaseHintFuture<String> failing = ExecutionPhaseHintFuture.create(() -> {
            throw new IllegalStateException("boom");
        });
        failing.run();

        assertThat(failing.isDone()).isTrue();
        assertThat(failing.phase())
                .as("the exception-path finally publishes the terminal phase like the success path")
                .isEqualTo(ExecutionPhase.TERMINAL);
        assertThatThrownBy(() -> failing.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void thePhaseHintReachesTerminalAfterAFailingPoolRun() throws Exception {
        ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            ExecutionPhaseHintFuture<String> failing = ExecutionPhaseHintFuture.create(() -> {
                throw new IllegalStateException("boom");
            });
            executor.execute(failing);
            assertThatThrownBy(() -> failing.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);

            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(failing.phase()).isEqualTo(ExecutionPhase.TERMINAL));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void thePhaseHintReachesTerminalAfterAFailureThatLosesToCancellation() throws Exception {
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        ExecutionPhaseHintFuture<String> losing = ExecutionPhaseHintFuture.create(() -> {
            executionStarted.countDown();
            cancelled.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("too late");
        });
        Thread runner = new Thread(losing::run);
        runner.start();
        assertThat(executionStarted.await(2, TimeUnit.SECONDS)).isTrue();
        losing.cancel(true);
        cancelled.countDown();
        runner.join(5000L);

        assertThat(losing.isCancelled()).isTrue();
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(losing.phase()).isEqualTo(ExecutionPhase.TERMINAL));
    }

    @Test
    void thePhaseHintReachesTerminalAfterAnInlineViolationRejection() {
        ExecutionPhaseHintFuture<String> forbidden = ExecutionPhaseHintFuture.create(() -> "value");
        forbidden.forbidInlineExecution();
        // A saturated pool's RejectedExecutionHandler ran the task on the submitting thread: the
        // future must reject it and still publish the terminal phase on that exception path.
        forbidden.submitPrepared(com.google.common.util.concurrent.MoreExecutors.directExecutor());

        assertThat(forbidden.isDone()).isTrue();
        assertThat(forbidden.phase()).isEqualTo(ExecutionPhase.TERMINAL);
        assertThatThrownBy(() -> forbidden.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SubmissionException.class);
    }

    @Test
    void aRejectedSubmissionPublishesASkippedObservationWithZeroTimings() throws Exception {
        ExecutorService rejected = java.util.concurrent.Executors.newSingleThreadExecutor();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("rejected"), rejected).build();
        rejected.shutdown();
        try {
            TaskFuture<String> task = runtime.par(ParId.of("rejected"))
                    .submit("never-starts", () -> "value", TaskOptions.timeout(Duration.ofSeconds(30)));

            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class)
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            TaskExitObservation<String> snapshot = task.completionFuture().get(5, TimeUnit.SECONDS);
            assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(snapshot.startTimeNanos())
                    .as("a task prevented from starting has no start or end reading")
                    .isZero();
            assertThat(snapshot.endTimeNanos()).isZero();
        } finally {
            runtime.close();
        }
    }

    @Test
    void aBrokenObservationListenerLeavesASevereDiagnosticWithoutStrandingTheSink() throws Exception {
        Logger kernelLogger = Logger.getLogger(TaskObservation.class.getName());
        List<LogRecord> severe = Collections.synchronizedList(new java.util.ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE) {
                    severe.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        kernelLogger.addHandler(capture);
        try {
            MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                    BatchOptions.timeout("task", Duration.ofSeconds(30)).spec(), 1));
            ExecutionPhaseHintFuture<String> future =
                    TaskSubmissions.prepare(new TaskExecutionContext(unit, 0, System.nanoTime()), () -> "value");
            TaskObservation<String> observation = com.google.common.base.Verify.verifyNotNull(future.observation());
            observation
                    .view()
                    .addListener(
                            () -> {
                                throw new AssertionError("listener boom");
                            },
                            directExecutor());
            future.run();

            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            assertThat(observation.view().isDone()).isTrue();
            assertThat(severe)
                    .as("the publication failure is reported instead of vanishing")
                    .anySatisfy(record -> assertThat(record.getThrown()).isInstanceOf(AssertionError.class));
        } finally {
            kernelLogger.removeHandler(capture);
        }
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.event.Level;

/**
 * Late-failure logging after cancellation: a cancellation acknowledgement is logged at debug
 * level by provenance, every other late failure stays a warn-level report
 * (design/synchronous-scope-exit.md section 4; design/interruption-contract.md sections 2.4 and 3.1).
 *
 * <p>Each execution waits for a fact that happens only after its future settled as cancelled: the
 * kernel's cancellation interrupt ({@code interruptTask()} runs after the cancelled state is
 * published), or an explicit {@code cancel(false)} the test performs before releasing the execution.
 * The late-failure branch is therefore reached deterministically.
 */
@Timeout(10)
class LateFailureLoggingTest {
    private static final String LATE = "failed after cancellation";

    private LogCapture capture;
    private CountDownLatch logged;
    private ExecutorService executor;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void setup() {
        capture = LogCapture.attach(ExecutionPhaseHintFuture.class);
        logged = capture.latchOn(LATE);
        executor = Executors.newFixedThreadPool(2);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        par = runtime.par(ParId.of("worker"));
    }

    @AfterEach
    void teardown() throws Exception {
        try {
            runtime.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            capture.close();
            Thread.interrupted();
        }
    }

    @Test
    void checkpointAcknowledgementAfterCancellationIsNotAWarning() throws Exception {
        TaskBatchResult<Integer> batch = par.map(
                Collections.singletonList(1),
                i -> {
                    // The kernel interrupts only after the future settled as cancelled, and the
                    // token committed TIMEOUT before cascading to the future.
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.onSpinWait();
                    }
                    Checkpoints.checkpoint();
                    return i;
                },
                BatchOptions.timeout("loop", Duration.ofMillis(100)).closeGrace(Duration.ofSeconds(2)));

        TerminationAssertions.assertCancelled(
                batch.results().get(0).termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
        LogCapture.Event record = singleRecord();
        assertThat(record.level()).isEqualTo(Level.DEBUG);
        assertThat(record.thrown()).isInstanceOf(CheckpointCancellation.class);
    }

    @Test
    void blockingAdapterAcknowledgementAfterKernelInterruptIsNotAWarning() throws Exception {
        ArrayBlockingQueue<Integer> empty = new ArrayBlockingQueue<>(1);
        TaskBatchResult<Integer> batch = par.map(
                Collections.singletonList(1),
                i -> Checkpoints.checkTake(empty),
                BatchOptions.timeout("take", Duration.ofMillis(100)).closeGrace(Duration.ofSeconds(2)));

        TerminationAssertions.assertCancelled(
                batch.results().get(0).termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
        LogCapture.Event record = singleRecord();
        assertThat(record.level()).isEqualTo(Level.DEBUG);
        assertThat(record.thrown())
                .isInstanceOf(CheckpointCancellation.class)
                .hasCauseInstanceOf(InterruptedException.class);
    }

    @Test
    void acknowledgementCarryingACloseFailureStaysAWarning() throws Exception {
        Exception closeFailure = new Exception("late close failure");
        TaskGroupResult<Void> result = runtime.group("ack-close", Duration.ofMillis(100))
                .closeGrace(Duration.ofSeconds(2))
                .par(TaskKey.<Integer>named("resource"), par, () -> {
                    try (AutoCloseable resource = () -> {
                        throw closeFailure;
                    }) {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.onSpinWait();
                        }
                        Checkpoints.checkpoint();
                        return 1;
                    }
                })
                .runAll();

        TerminationAssertions.assertCancelled(
                result.resultAt(0).termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
        LogCapture.Event record = singleRecord();
        assertThat(record.level()).isEqualTo(Level.WARN);
        assertThat(record.thrown()).isInstanceOf(CheckpointCancellation.class);
        assertThat(Verify.verifyNotNull(record.thrown()).getSuppressed()).containsExactly(closeFailure);
    }

    @Test
    void rawBlockingCallInterruptedByKernelCancellationIsNotAWarning() throws Exception {
        TaskGroupResult<Void> result = runtime.group("raw-group", Duration.ofMillis(100))
                .closeGrace(Duration.ofSeconds(2))
                .par(TaskKey.<Integer>named("sleeper"), par, () -> {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                    return 1;
                })
                .runAll();

        TerminationAssertions.assertCancelled(
                result.resultAt(0).termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
        LogCapture.Event record = singleRecord();
        assertThat(record.level()).isEqualTo(Level.DEBUG);
        assertThat(record.thrown()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void blockingAdapterInterruptedUnderAnUncommittedTokenIsAUserFailure() throws Exception {
        ArrayBlockingQueue<Integer> empty = new ArrayBlockingQueue<>(1);
        AtomicReference<Boolean> flagRestored = new AtomicReference<>();
        TaskBatchResult<Integer> batch = par.map(
                Collections.singletonList(1),
                i -> {
                    Thread.currentThread().interrupt();
                    try {
                        return Checkpoints.checkTake(empty);
                    } finally {
                        flagRestored.set(Thread.currentThread().isInterrupted());
                    }
                },
                BatchOptions.timeout("foreign-interrupt", Duration.ofSeconds(5)));

        ImmediateResult<Integer> element = batch.results().get(0);
        TerminationAssertions.assertUserFailure(element.termination());
        Throwable failure = Verify.verifyNotNull(element.failure());
        assertThat(failure.getClass()).isEqualTo(LeanCancellationException.class);
        assertThat(failure).hasCauseInstanceOf(InterruptedException.class);
        assertThat(flagRestored.get()).isTrue();
        assertThat(capture.events(LATE)).isEmpty();
    }

    /** Late failures thrown by an execution released only after an explicit {@code cancel(false)}. */
    enum LateFailure {
        ACKNOWLEDGEMENT(false, () -> CheckpointCancellation.acknowledge("cancel during running", null)),
        ACKNOWLEDGEMENT_WITH_SUPPRESSED(true, () -> {
            CheckpointCancellation acknowledgement = CheckpointCancellation.acknowledge("cancel during running", null);
            acknowledgement.addSuppressed(new IllegalStateException("close"));
            return acknowledgement;
        }),
        USER_LEAN_CANCELLATION(true, () -> new LeanCancellationException("user")),
        USER_CANCELLATION(true, () -> new java.util.concurrent.CancellationException("user")),
        WRAPPED_ACKNOWLEDGEMENT(
                true,
                () -> new IllegalStateException(
                        "wrapped", CheckpointCancellation.acknowledge("cancel during running", null))),
        INTERRUPTED_WITHOUT_DELIVERY(true, () -> new InterruptedException("independent"));

        final boolean warns;
        final java.util.function.Supplier<Exception> failure;

        LateFailure(boolean warns, java.util.function.Supplier<Exception> failure) {
            this.warns = warns;
            this.failure = failure;
        }
    }

    @ParameterizedTest
    @EnumSource(LateFailure.class)
    void lateFailureLevelFollowsProvenanceNotType(LateFailure shape) throws Exception {
        Exception thrown = shape.failure.get();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutionPhaseHintFuture<Void> future = ExecutionPhaseHintFuture.create(() -> {
            entered.countDown();
            Uninterruptibles.awaitUninterruptibly(release);
            throw thrown;
        });
        Thread worker = new Thread(future);
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(future.cancel(false)).isTrue();
        release.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        LogCapture.Event record = singleRecord();
        assertThat(record.level()).isEqualTo(shape.warns ? Level.WARN : Level.DEBUG);
        assertThat(record.thrown()).isSameAs(thrown);
    }

    @Test
    void interruptedExceptionAfterTheExecutionRestoredTheKernelInterruptStaysAWarning() throws Exception {
        InterruptedException independent = new InterruptedException("close did not finish");
        Throwable logged = runDeliveredThenThrow(() -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException kernel) {
                Thread.currentThread().interrupt();
            }
            throw independent;
        });
        assertThat(logged).isSameAs(independent);
        assertThat(singleRecord().level()).isEqualTo(Level.WARN);
    }

    @Test
    void kernelDeliveryDoesNotLeakToTheNextFutureOnTheSameThread() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch firstEntered = new CountDownLatch(1);
            ExecutionPhaseHintFuture<Void> first = ExecutionPhaseHintFuture.create(() -> {
                firstEntered.countDown();
                new CountDownLatch(1).await();
                return null;
            });
            single.execute(first);
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.cancel(true)).isTrue();
            assertThat(logged.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(singleRecord().level()).isEqualTo(Level.DEBUG);
            capture.clear();

            CountDownLatch secondLogged = capture.latchOn(LATE);
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            single.submit(() -> firstThread.set(Thread.currentThread())).get(5, TimeUnit.SECONDS);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch secondRelease = new CountDownLatch(1);
            InterruptedException independent = new InterruptedException("no delivery on this run");
            ExecutionPhaseHintFuture<Void> second = ExecutionPhaseHintFuture.create(() -> {
                secondThread.set(Thread.currentThread());
                secondEntered.countDown();
                Uninterruptibles.awaitUninterruptibly(secondRelease);
                throw independent;
            });
            single.execute(second);
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.cancel(false)).isTrue();
            secondRelease.countDown();
            assertThat(secondLogged.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondThread.get()).isSameAs(firstThread.get());
            LogCapture.Event record = singleRecord();
            assertThat(record.thrown()).isSameAs(independent);
            assertThat(record.level()).isEqualTo(Level.WARN);
        } finally {
            single.shutdownNow();
            assertThat(single.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Runs {@code taskCode} on a worker, cancels it with interrupt once entered, returns the logged failure. */
    private Throwable runDeliveredThenThrow(java.util.concurrent.Callable<Void> taskCode) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        ExecutionPhaseHintFuture<Void> future = ExecutionPhaseHintFuture.create(() -> {
            entered.countDown();
            return taskCode.call();
        });
        Thread worker = new Thread(future);
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(future.cancel(true)).isTrue();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        return Verify.verifyNotNull(singleRecord().thrown());
    }

    private LogCapture.Event singleRecord() throws InterruptedException {
        assertThat(logged.await(5, TimeUnit.SECONDS)).isTrue();
        List<LogCapture.Event> records = capture.events(LATE);
        assertThat(records).hasSize(1);
        return records.get(0);
    }
}

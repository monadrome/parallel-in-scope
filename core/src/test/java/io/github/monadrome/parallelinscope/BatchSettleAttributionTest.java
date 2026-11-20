package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A batch element whose execution raises its own {@link CancellationException} or {@link
 * InterruptedException}, with no framework cancellation committed, ends as {@link
 * FailureCause.Kind#USER_CODE_FAILURE} carrying that throwable, exactly as a group member does.
 *
 * <p>The element's own failure commits the batch token's fail-fast, so a freeze that reads
 * the token after the fact reattributes the element that caused the cascade. The freeze must use the
 * termination pinned when the element settled. On a direct executor every element settles inside
 * submission, so the cascade has always committed before the freeze: the old freeze-time read failed
 * those tests deterministically. The pooled tests lock the cross-thread race, where the caller can
 * wake before the cascade commits.
 */
@SuppressWarnings("NullAway.Init")
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BatchSettleAttributionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ParRuntime runtime;
    private ExecutorService pool;

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void anElementThatThrowsItsOwnCancellationIsAUserFailure() {
        Par par = directPar();
        CancellationException own = new CancellationException("the execution's own");

        TaskBatchResult<String> result = par.map(
                Collections.singletonList("x"),
                item -> {
                    throw own;
                },
                BatchOptions.timeout("own-cancellation", TIMEOUT));

        TerminationAssertions.assertUserFailure(result.results().get(0).termination());
        assertThat(result.results().get(0).failure()).isSameAs(own);
        TaskExitObservation<String> completion = result.completions().get(0);
        assertThat(completion).isNotNull();
        TerminationAssertions.assertUserFailure(completion.termination());
        assertThat(completion.failure()).isSameAs(own);
        assertThat(result.failedTaskResult().map(ImmediateResult::failure)).containsSame(own);
        assertThatThrownBy(result::valuesOrThrow).isSameAs(own);
    }

    @Test
    void anElementThatThrowsItsOwnInterruptionIsAUserFailure() {
        Par par = directPar();
        InterruptedException own = new InterruptedException("the execution's own");

        TaskBatchResult<String> result = par.map(
                Collections.singletonList("x"),
                item -> {
                    throw SneakyThrow.<RuntimeException>sneak(own);
                },
                BatchOptions.timeout("own-interruption", TIMEOUT));

        TerminationAssertions.assertUserFailure(result.results().get(0).termination());
        assertThat(result.results().get(0).failure()).isSameAs(own);
        assertThatThrownBy(result::valuesOrThrow)
                .isInstanceOf(CompletionException.class)
                .hasCauseReference(own);
    }

    @Test
    void theElementWhoseOwnCancellationTriggersTheCascadeStaysAUserFailure() {
        Par par = directPar();

        TaskBatchResult<String> result = par.map(
                Arrays.asList("a", "b", "c", "d"),
                item -> {
                    throw new CancellationException("the execution's own " + item);
                },
                BatchOptions.timeout("own-cancellations", TIMEOUT));

        List<ImmediateResult<String>> elements = result.results();
        TerminationAssertions.assertUserFailure(elements.get(0).termination());
        assertThat(elements.get(0).failure()).hasMessage("the execution's own a");
        for (ImmediateResult<String> sibling : elements.subList(1, elements.size())) {
            TerminationAssertions.assertCancelled(sibling.termination(), CancellationReason.Kind.ABORTED_BY_FAIL_FAST);
        }
        assertThatThrownBy(result::valuesOrThrow)
                .isInstanceOf(CancellationException.class)
                .hasMessage("the execution's own a");
    }

    @Test
    void aCombineThatThrowsItsOwnCancellationIsAUserFailure() {
        Par par = directPar();
        CancellationException own = new CancellationException("the combine's own");

        BatchCombinedResult<Integer, Integer> result = par.mapAndCombine(
                Arrays.asList(1, 2),
                item -> item,
                BatchOptions.timeout("own-combine-cancellation", TIMEOUT),
                par,
                values -> {
                    throw own;
                });

        assertThat(result.batchResult().results())
                .extracting(result0 -> result0.termination().kind())
                .containsExactly(TaskTermination.Kind.SUCCESS, TaskTermination.Kind.SUCCESS);
        TerminationAssertions.assertUserFailure(result.terminalResult().termination());
        assertThat(result.terminalResult().failure()).isSameAs(own);
        TaskExitObservation<Integer> terminal = result.terminal();
        assertThat(terminal).isNotNull();
        TerminationAssertions.assertUserFailure(terminal.termination());
        assertThatThrownBy(result::terminalValueOrThrow).isSameAs(own);
    }

    /**
     * The freeze waits for the combine's own settle attribution, not for the cleanup budget: with a
     * zero close grace the observation barriers make a single check, and the combine is still
     * running when the elements are done.
     */
    @Test
    void theCombineIsFrozenAfterItSettlesEvenWithAZeroCloseGrace() throws Exception {
        Par par = pooledPar(2);

        BatchCombinedResult<Integer, Integer> result = par.mapAndCombine(
                Arrays.asList(1, 2),
                item -> item,
                BatchOptions.timeout("slow-combine", TIMEOUT).closeGrace(Duration.ZERO),
                par,
                values -> {
                    Thread.sleep(50);
                    return Objects.requireNonNull(values).size();
                });

        TerminationAssertions.assertSuccess(result.terminalResult().termination());
        assertThat(result.terminalValueOrThrow()).isEqualTo(2);
    }

    @Test
    void aPooledElementThatThrowsItsOwnCancellationIsAlwaysAUserFailure() {
        Par par = pooledPar(4);

        for (int run = 0; run < 200; run++) {
            TaskBatchResult<String> result = par.map(
                    Collections.singletonList("x"),
                    item -> {
                        throw new CancellationException("the execution's own");
                    },
                    BatchOptions.timeout("own-cancellation", TIMEOUT).closeGrace(Duration.ZERO));

            assertThat(result.results().get(0).termination())
                    .as("run %s", run)
                    .satisfies(TerminationAssertions::assertUserFailure);
            assertThatThrownBy(result::valuesOrThrow)
                    .as("run %s", run)
                    .isInstanceOf(CancellationException.class)
                    .hasMessage("the execution's own");
        }
    }

    /**
     * A cancellation-shaped user failure is pinned before the token commits the fail-fast it
     * triggers, so the live report's counts and firstException must agree about the element: the
     * count says FAILED and firstException names the thrown object. A failure() that re-reads the
     * committed token at report time would hide it behind a null firstException.
     */
    @Test
    void aCancellationShapedUserFailureStaysTheFirstExceptionOfTheLiveReport() {
        Par par = pooledPar(1);
        TaskBatch<String> batch = par.submitBatch(
                Collections.singletonList("only"),
                item -> {
                    throw new CancellationException("spontaneous");
                },
                BatchOptions.timeout("cancellation-shaped-report", TIMEOUT).parallelism(1));

        TaskBatchResult<String> frozen = batch.finish();
        TerminationAssertions.assertUserFailure(frozen.results().get(0).termination());
        TaskBatch.BatchReport report = batch.report();
        assertThat(report.terminationCounts()).containsEntry(TaskTermination.Kind.FAILED, 1);
        assertThat(report.firstException()).isInstanceOf(CancellationException.class);
        assertThat(report.firstException()).hasMessage("spontaneous");
    }

    @Test
    void aPooledBatchOfOwnCancellationsAlwaysKeepsItsTriggerAUserFailure() {
        Par par = pooledPar(4);

        for (int run = 0; run < 50; run++) {
            TaskBatchResult<String> result = par.map(
                    Arrays.asList("a", "b", "c", "d"),
                    item -> {
                        throw new CancellationException("the execution's own");
                    },
                    BatchOptions.timeout("own-cancellations", TIMEOUT));

            assertThat(result.results())
                    .as("run %s", run)
                    .anySatisfy(element -> TerminationAssertions.assertUserFailure(element.termination()));
            assertThatThrownBy(result::valuesOrThrow)
                    .as("run %s", run)
                    .isInstanceOf(CancellationException.class)
                    .hasMessage("the execution's own");
        }
    }

    /**
     * An element cancelled directly while the token still runs freezes as {@link
     * CancellationReason.Kind#CANCELLED_BY_CALLER}, like a directly cancelled group member, and the
     * pinned termination keeps that attribution after the element's own cancellation committed
     * fail-fast on the batch token. The kernel view is the handle here; {@link
     * #anElementItsExecutorCancelsFreezesAsMemberCancelled} reaches the same shape through a
     * registered executor.
     */
    @Test
    void aDirectlyCancelledElementFreezesAsMemberCancelledWhileItsSiblingReadsFailFast() throws Exception {
        Par par = pooledPar(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch never = new CountDownLatch(1);

        TaskBatch<String> batch = par.submitBatch(
                Arrays.asList("held", "sibling"),
                item -> {
                    started.countDown();
                    try {
                        never.await();
                    } catch (InterruptedException observed) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(observed);
                    }
                    return item;
                },
                BatchOptions.timeout("direct-cancel", TIMEOUT).parallelism(2));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(batch.results().get(0).cancel(true)).isTrue();
        TaskBatchResult<String> frozen = batch.finish();

        TerminationAssertions.assertCancelled(
                frozen.results().get(0).termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
        TerminationAssertions.assertCancelled(
                frozen.results().get(1).termination(), CancellationReason.Kind.ABORTED_BY_FAIL_FAST);
        TerminationAssertions.assertCancelled(
                batch.results().get(0).termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
    }

    /**
     * The Runnable a registered executor receives is the element's own future, so a user executor
     * can cancel it — a {@code shutdownNow()} caller cancelling the returned tasks is the common
     * case. The element it cancelled freezes as {@link CancellationReason.Kind#CANCELLED_BY_CALLER};
     * the sibling its cancellation took down reads {@code ABORTED_BY_FAIL_FAST}. A freeze-time read
     * would give both {@code ABORTED_BY_FAIL_FAST}.
     */
    @Test
    void anElementItsExecutorCancelsFreezesAsMemberCancelled() {
        pool = Executors.newFixedThreadPool(2);
        ExecutorService cancelsFirstTask = new CancelsFirstTask(pool);
        runtime = ParRuntime.builder()
                .register(ParId.of("cancelling"), cancelsFirstTask)
                .build();
        CountDownLatch never = new CountDownLatch(1);

        TaskBatchResult<String> result = runtime.par(ParId.of("cancelling"))
                .map(
                        Arrays.asList("cancelled", "sibling"),
                        item -> {
                            try {
                                never.await();
                            } catch (InterruptedException observed) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(observed);
                            }
                            return item;
                        },
                        BatchOptions.timeout("executor-cancel", TIMEOUT).parallelism(2));

        TerminationAssertions.assertCancelled(
                result.results().get(0).termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
        TerminationAssertions.assertCancelled(
                result.results().get(1).termination(), CancellationReason.Kind.ABORTED_BY_FAIL_FAST);
    }

    /**
     * Building an observation snapshot can only fail on a broken kernel invariant or an exhausted
     * stack, but it runs inside the settling future's listener fan-out, which Guava stops at the
     * first escaping {@code Error}. The observation must end exceptionally instead, so every
     * listener registered after it — a batch element's settle-termination pin among them — still
     * runs and the freeze cannot wait on it forever.
     */
    @Test
    void aSnapshotThatCannotBeBuiltFailsItsObservationWithoutStoppingLaterListeners() {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger observationLogger = Logger.getLogger(TaskObservation.class.getName());
        observationLogger.addHandler(capture);
        try {
            SettableFuture<String> settle = SettableFuture.create();
            TaskObservation<String> observation = TaskObservation.of(
                    () -> {
                        throw new StackOverflowError("snapshot");
                    },
                    settle);
            observation
                    .view()
                    .addListener(
                            () -> {
                                throw new AssertionError("observation listener");
                            },
                            MoreExecutors.directExecutor());
            AtomicBoolean laterListenerRan = new AtomicBoolean();
            settle.addListener(() -> laterListenerRan.set(true), MoreExecutors.directExecutor());

            settle.set("value");

            assertThat(laterListenerRan).isTrue();
            assertThat(observation.view().isDone()).isTrue();
            assertThatThrownBy(() -> Futures.getDone(observation.view()))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(StackOverflowError.class);
            assertThat(logged)
                    .extracting(LogRecord::getMessage)
                    .containsExactly(
                            "an observation listener failed", "a task observation snapshot could not be built");
            assertThat(logged)
                    .allSatisfy(record -> assertThat(record.getLevel()).isEqualTo(Level.SEVERE));
        } finally {
            observationLogger.removeHandler(capture);
        }
    }

    @Test
    void thePinKeepsTheAttributionReadWhenTheElementSettled() throws Exception {
        CancellationToken token = new CancellationToken();
        SettableFuture<String> delegate = SettableFuture.create();
        Task<String> view = Task.of("batch", token, delegate);
        ListenableFuture<TaskTermination<String>> pin = view.terminationFuture();
        assertThat(pin.isDone()).isFalse();

        delegate.setException(new CancellationException("the execution's own"));
        token.failFastCancel();

        TerminationAssertions.assertUserFailure(Futures.getDone(pin));
        TerminationAssertions.assertUserFailure(view.termination());
    }

    @Test
    void aCancellationSignalUnderAnAlreadyCommittedTokenIsStillAttributedThroughTheToken() throws Exception {
        CancellationToken token = new CancellationToken();
        token.timeoutCancel();
        SettableFuture<String> delegate = SettableFuture.create();
        ListenableFuture<TaskTermination<String>> pin =
                Task.of("batch", token, delegate).terminationFuture();

        delegate.setException(new CancellationException("checkpoint observed the deadline"));

        TerminationAssertions.assertCancelled(Futures.getDone(pin), CancellationReason.Kind.DEADLINE_EXCEEDED);
    }

    @Test
    void aDerivedViewPublishesItsSettledTerminationImmediately() throws Exception {
        CancellationToken token = new CancellationToken();
        Task<String> derived = Task.of("base", token, Futures.immediateFuture("value"))
                .transform(value -> value + "!", MoreExecutors.directExecutor());

        // A derived view carries no settle-time pin; its termination signal is an immediate
        // future recomputed from the settled delegate, not a pending or absent view.
        ListenableFuture<TaskTermination<String>> signal = derived.terminationFuture();

        assertThat(signal.isDone()).isTrue();
        TerminationAssertions.assertSuccess(Futures.getDone(signal));
        assertThat(verifyNotNull(Futures.getDone(signal)).value()).isEqualTo("value!");
    }

    private Par directPar() {
        runtime = ParRuntime.builder()
                .register(ParId.of("direct"), MoreExecutors.newDirectExecutorService())
                .build();
        return runtime.par(ParId.of("direct"));
    }

    private Par pooledPar(int threads) {
        pool = Executors.newFixedThreadPool(threads);
        runtime = ParRuntime.builder().register(ParId.of("pool"), pool).build();
        return runtime.par(ParId.of("pool"));
    }

    /** An executor that cancels the first task it is handed and forwards every other one. */
    private static final class CancelsFirstTask extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicBoolean first = new AtomicBoolean(true);

        CancelsFirstTask(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable task) {
            if (first.compareAndSet(true, false)) {
                ((Future<?>) task).cancel(true);
                return;
            }
            delegate.execute(task);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    /** Converts any throwable into an unchecked throw site for lambda executions. */
    private static final class SneakyThrow {

        private SneakyThrow() {}

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> RuntimeException sneak(Throwable throwable) throws T {
            throw (T) throwable;
        }
    }
}

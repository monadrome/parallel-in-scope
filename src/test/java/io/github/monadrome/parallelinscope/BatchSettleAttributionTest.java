package io.github.monadrome.parallelinscope;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A batch element whose body raises its own {@link CancellationException} or {@link
 * InterruptedException}, with no framework cancellation committed, ends as {@link
 * TaskOutcome#USER_FAILURE} carrying that throwable, exactly as a group member does.
 *
 * <p>The element's own failure commits the batch token's {@code FAIL_FAST}, so a freeze that reads
 * the token after the fact reattributes the element that caused the cascade. The freeze must use the
 * attribution pinned when the element settled. On a direct executor every element settles inside
 * submission, so the cascade has always committed before the freeze: the old freeze-time read failed
 * those tests deterministically. The pooled tests lock the cross-thread race, where the caller can
 * wake before the cascade commits.
 */
@SuppressWarnings("NullAway.Init")
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
        CancellationException own = new CancellationException("the body's own");

        TaskBatchResult<String> result = par.map(
                Collections.singletonList("x"),
                item -> {
                    throw own;
                },
                BatchOptions.timeout("own-cancellation", TIMEOUT));

        assertThat(result.results().get(0).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.results().get(0).failure()).isSameAs(own);
        TaskCompletion<String> completion = result.completions().get(0);
        assertThat(completion).isNotNull();
        assertThat(completion.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(completion.failure()).isSameAs(own);
        assertThat(result.report().firstException()).isSameAs(own);
        assertThatThrownBy(result::valuesOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCause(own);
    }

    @Test
    void anElementThatThrowsItsOwnInterruptionIsAUserFailure() {
        Par par = directPar();
        InterruptedException own = new InterruptedException("the body's own");

        TaskBatchResult<String> result = par.map(
                Collections.singletonList("x"),
                item -> {
                    throw SneakyThrow.<RuntimeException>sneak(own);
                },
                BatchOptions.timeout("own-interruption", TIMEOUT));

        assertThat(result.results().get(0).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.results().get(0).failure()).isSameAs(own);
        assertThatThrownBy(result::valuesOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCause(own);
    }

    @Test
    void theElementWhoseOwnCancellationTriggersTheCascadeStaysAUserFailure() {
        Par par = directPar();

        TaskBatchResult<String> result = par.map(
                Arrays.asList("a", "b", "c", "d"),
                item -> {
                    throw new CancellationException("the body's own " + item);
                },
                BatchOptions.timeout("own-cancellations", TIMEOUT));

        List<ImmediateResult<String>> elements = result.results();
        assertThat(elements.get(0).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(elements.get(0).failure()).hasMessage("the body's own a");
        for (ImmediateResult<String> sibling : elements.subList(1, elements.size())) {
            assertThat(sibling.outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        }
        assertThatThrownBy(result::valuesOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("the body's own a");
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
                .extracting(ImmediateResult::outcome)
                .containsExactly(TaskOutcome.SUCCESS, TaskOutcome.SUCCESS);
        assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.terminalResult().failure()).isSameAs(own);
        TaskCompletion<Integer> terminal = result.terminal();
        assertThat(terminal).isNotNull();
        assertThat(terminal.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThatThrownBy(result::terminalValueOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCause(own);
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

        assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.terminalValueOrThrow()).isEqualTo(2);
    }

    @Test
    void aPooledElementThatThrowsItsOwnCancellationIsAlwaysAUserFailure() {
        Par par = pooledPar(4);

        for (int run = 0; run < 200; run++) {
            TaskBatchResult<String> result = par.map(
                    Collections.singletonList("x"),
                    item -> {
                        throw new CancellationException("the body's own");
                    },
                    BatchOptions.timeout("own-cancellation", TIMEOUT).closeGrace(Duration.ZERO));

            assertThat(result.results().get(0).outcome()).as("run %s", run).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThatThrownBy(result::valuesOrThrow).as("run %s", run).isInstanceOf(ExecutionException.class);
        }
    }

    @Test
    void aPooledBatchOfOwnCancellationsAlwaysKeepsItsTriggerAUserFailure() {
        Par par = pooledPar(4);

        for (int run = 0; run < 50; run++) {
            TaskBatchResult<String> result = par.map(
                    Arrays.asList("a", "b", "c", "d"),
                    item -> {
                        throw new CancellationException("the body's own");
                    },
                    BatchOptions.timeout("own-cancellations", TIMEOUT));

            assertThat(result.results())
                    .as("run %s", run)
                    .extracting(ImmediateResult::outcome)
                    .contains(TaskOutcome.USER_FAILURE);
            assertThatThrownBy(result::valuesOrThrow)
                    .as("run %s", run)
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("the body's own");
        }
    }

    /**
     * Only kernel code holds a live element view, so this shape is not reachable through the public
     * API: an element cancelled directly while the token still runs freezes as {@link
     * TaskOutcome#MEMBER_CANCELLED}, like a directly cancelled group member, while the live read
     * already sees the {@code FAIL_FAST} its own cancellation committed.
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

        assertThat(frozen.results())
                .extracting(ImmediateResult::outcome)
                .containsExactly(TaskOutcome.MEMBER_CANCELLED, TaskOutcome.FAIL_FAST);
        assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
    }

    @Test
    void thePinKeepsTheAttributionReadWhenTheElementSettled() throws Exception {
        CancellationToken token = new CancellationToken();
        SettableFuture<String> delegate = SettableFuture.create();
        Task<String> view = Task.attributedOnSettle("batch", token, delegate);
        ListenableFuture<TaskOutcome> pin = view.settledAttribution();
        assertThat(pin).isNotNull();
        assertThat(pin.isDone()).isFalse();

        delegate.setException(new CancellationException("the body's own"));
        token.failFastCancel();

        assertThat(Futures.getDone(pin)).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(view.outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
    }

    @Test
    void aCancellationSignalUnderAnAlreadyCommittedTokenIsStillAttributedThroughTheToken() throws Exception {
        CancellationToken token = new CancellationToken();
        token.timeoutCancel();
        SettableFuture<String> delegate = SettableFuture.create();
        ListenableFuture<TaskOutcome> pin =
                Task.attributedOnSettle("batch", token, delegate).settledAttribution();
        assertThat(pin).isNotNull();

        delegate.setException(new CancellationException("checkpoint observed the deadline"));

        assertThat(Futures.getDone(pin)).isEqualTo(TaskOutcome.TIMEOUT);
    }

    @Test
    void viewsOutsideTheBatchPipelineCarryNoPin() {
        CancellationToken token = new CancellationToken();

        assertThat(Task.of("plain", token, SettableFuture.<String>create()).settledAttribution())
                .isNull();
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

    /** Converts any throwable into an unchecked throw site for lambda bodies. */
    private static final class SneakyThrow {

        private SneakyThrow() {}

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> RuntimeException sneak(Throwable throwable) throws T {
            throw (T) throwable;
        }
    }
}

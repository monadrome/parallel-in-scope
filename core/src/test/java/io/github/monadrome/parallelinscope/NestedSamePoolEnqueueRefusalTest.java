package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Records what {@code rejectEnqueue} does for the case it exists for: a nested batch on the pool whose
 * only core worker is running the outer element.
 *
 * <p>Every pool here uses a {@link SubmissionAwareBlockingQueue} with spare capacity, so the only
 * variable is the flag on the inner batch and the pool's maximum size and rejection handler. Without
 * the flag the inner element queues behind the blocked worker until the inherited deadline expires.
 * With it the refused offer makes {@code ThreadPoolExecutor.execute} grow a worker below the maximum,
 * and at the maximum invoke the rejection handler: an abort ends the inner element as a submission
 * failure before the deadline, and {@code CallerRunsPolicy} runs it on the blocked outer worker.
 *
 * <p>The executor wait graph classifies a pool by its queue capacity alone, so every case reports the
 * same self-loop whether or not the flag removes the starvation.
 */
class NestedSamePoolEnqueueRefusalTest {

    private static final Duration DEADLINE = Duration.ofSeconds(2);
    private static final Duration EXIT_WAIT = Duration.ofSeconds(10);

    private final List<ExecutorService> pools = new ArrayList<>();
    private final List<ParRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void shutDown() {
        for (ParRuntime runtime : runtimes) {
            runtime.close();
        }
        for (ExecutorService pool : pools) {
            pool.shutdownNow();
        }
    }

    private Par oneCoreWorker(int maximumPoolSize, RejectedExecutionHandler handler) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, maximumPoolSize, 1L, TimeUnit.MINUTES, new SubmissionAwareBlockingQueue<>(4), handler);
        pools.add(pool);
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("shared"), pool).build();
        runtimes.add(runtime);
        return runtime.par(ParId.of("shared"));
    }

    /**
     * Runs one outer element that runs one inner element on the same pool, records the outer worker's
     * thread name, and returns the inner result, whose value is the thread that ran the inner element.
     */
    private static ImmediateResult<String> nestedOnSamePool(
            Par par, boolean rejectEnqueue, AtomicReference<@Nullable String> outerThread) {
        AtomicReference<@Nullable ImmediateResult<String>> inner = new AtomicReference<>();
        par.map(
                Collections.singletonList("outer"),
                outer -> {
                    outerThread.set(Thread.currentThread().getName());
                    ImmediateResult<String> result = par.map(
                                    Collections.singletonList("inner"),
                                    value -> Thread.currentThread().getName(),
                                    BatchOptions.inheritTimeout("inner").rejectEnqueue(rejectEnqueue))
                            .results()
                            .get(0);
                    inner.set(result);
                    return outer;
                },
                BatchOptions.timeout("outer", DEADLINE));
        // A deadline ends the outer element by its token before its execution returns from the nested
        // map, so the inner result is published after the outer map may already have returned.
        await().atMost(EXIT_WAIT).until(() -> inner.get() != null);
        return Objects.requireNonNull(inner.get());
    }

    private void assertReportsSelfLoop() {
        assertThat(runtimes.get(0).executorWaitReport().cyclicComponents()).hasSize(1);
    }

    @Test
    void withoutRefusalTheInnerElementQueuesBehindTheBlockedWorkerUntilTheDeadline() {
        Par par = oneCoreWorker(2, new ThreadPoolExecutor.AbortPolicy());

        ImmediateResult<String> inner = nestedOnSamePool(par, false, new AtomicReference<>());

        assertThat(inner.termination().kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
        CancellationReason reason = inner.termination().cancellationReason();
        assertThat(reason).isNotNull();
        assertThat(reason.kind()).isEqualTo(CancellationReason.Kind.DEADLINE_EXCEEDED);
        assertReportsSelfLoop();
    }

    @Test
    void refusalBelowTheMaximumGrowsAWorkerAndTheInnerElementSucceeds() {
        Par par = oneCoreWorker(2, new ThreadPoolExecutor.AbortPolicy());

        AtomicReference<@Nullable String> outerThread = new AtomicReference<>();

        ImmediateResult<String> inner = nestedOnSamePool(par, true, outerThread);

        assertThat(inner.termination().kind()).isEqualTo(TaskTermination.Kind.SUCCESS);
        assertThat(inner.termination().value()).isNotEqualTo(outerThread.get());
        assertReportsSelfLoop();
    }

    @Test
    void refusalAtTheMaximumWithAbortEndsTheInnerElementAsASubmissionFailure() {
        Par par = oneCoreWorker(1, new ThreadPoolExecutor.AbortPolicy());

        ImmediateResult<String> inner = nestedOnSamePool(par, true, new AtomicReference<>());

        assertThat(inner.termination().kind()).isEqualTo(TaskTermination.Kind.FAILED);
        FailureCause cause = inner.termination().failureCause();
        assertThat(cause).isNotNull();
        assertThat(cause.kind()).isEqualTo(FailureCause.Kind.SUBMISSION_REJECTED);
        assertReportsSelfLoop();
    }

    @Test
    void refusalAtTheMaximumWithCallerRunsRunsTheInnerElementOnTheBlockedOuterWorker() {
        Par par = oneCoreWorker(1, new ThreadPoolExecutor.CallerRunsPolicy());
        AtomicReference<@Nullable String> outerThread = new AtomicReference<>();

        ImmediateResult<String> inner = nestedOnSamePool(par, true, outerThread);

        assertThat(inner.termination().kind()).isEqualTo(TaskTermination.Kind.SUCCESS);
        assertThat(outerThread.get()).isNotNull();
        assertThat(inner.termination().value()).isEqualTo(outerThread.get());
        assertReportsSelfLoop();
    }
}

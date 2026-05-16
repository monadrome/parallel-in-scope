package io.github.monadrome.parallelinscope;

import static com.google.common.collect.Maps.toImmutableEnumMap;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Immutable result wrapper for a batch of parallel tasks.
 *
 * <p>Bundles the list of individual {@link TaskFuture} results together with a {@code
 * submitCanceller} future used to cancel ongoing submission. Each element carries its own task
 * name, deadline, and cancellation attribution; the canceller is a control handle, not a task, and
 * stays a plain future.
 *
 * @param <T> the result type of individual tasks
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class TaskBatchResult<T> implements AutoCloseable {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(TaskBatchResult.class.getName());

    private final ListenableFuture<?> submitCanceller;
    private final List<TaskFuture<T>> results;
    private final BodyCompletionTracker bodyCompletion;
    private final @Nullable CancellationToken token;
    private final @Nullable Duration closeGrace;

    private TaskBatchResult(
            ListenableFuture<?> submitCanceller,
            List<? extends TaskFuture<T>> results,
            BodyCompletionTracker bodyCompletion,
            @Nullable CancellationToken token,
            @Nullable Duration closeGrace) {
        this.submitCanceller = submitCanceller != null ? submitCanceller : Futures.immediateVoidFuture();
        this.results = ImmutableList.copyOf(results);
        this.bodyCompletion = Objects.requireNonNull(bodyCompletion, "bodyCompletion cannot be null");
        this.token = token;
        this.closeGrace = closeGrace;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Provides the future running the sliding-window submission loop. Cancelling it stops further
     * submissions and interrupts the submitter. Any unsubmitted placeholders then fail with the
     * interruption cause; canceling a placeholder directly completes it as {@code CANCELLED}.
     *
     * @return the submission-loop future
     */
    public ListenableFuture<?> submitCanceller() {
        return submitCanceller;
    }

    /**
     * Returns the futures for individual batch elements in input order.
     *
     * <p>An element that never reached the executor is still delivered here: it answers with the
     * batch name and attribute its abandonment instead of staying unidentified.
     *
     * @return the individual result futures
     */
    public List<TaskFuture<T>> results() {
        return results;
    }

    /**
     * Waits for every element and returns its values in input order — the common "run these, give
     * me the results, fail if any failed" path in one call.
     *
     * <p>Unlike {@link #results()}, forgetting to handle failure is not possible here: the first
     * element failure propagates, a cancelled element surfaces as {@link CancellationException},
     * and an interrupted wait restores the interrupt flag and throws {@link
     * LeanCancellationException}.
     *
     * @return the element values in input order
     * @throws ExecutionException if any element failed
     * @throws CancellationException if any element was cancelled
     * @throws LeanCancellationException if the calling thread is interrupted while waiting
     */
    public List<T> valuesOrThrow() throws ExecutionException {
        try {
            return Futures.allAsList(results).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LeanCancellationException cancellation =
                    new LeanCancellationException("Interrupted while awaiting batch values");
            cancellation.initCause(e);
            throw cancellation;
        }
    }

    /**
     * Creates a result for a fully submitted batch.
     *
     * @param <T> the element result type
     * @param results the individual result futures
     * @return a new batch result
     */
    static <T> TaskBatchResult<T> of(List<? extends TaskFuture<T>> results) {
        return new TaskBatchResult<>(Futures.immediateVoidFuture(), results, BodyCompletionTracker.empty(), null, null);
    }

    /**
     * Creates a result for a fully submitted batch carrying its body-completion signal.
     *
     * @param <T> the element result type
     * @param bodyCompletion shared task-body completion signal of this submission
     * @param results the individual result futures
     * @return a new batch result
     */
    static <T> TaskBatchResult<T> of(BodyCompletionTracker bodyCompletion, List<? extends TaskFuture<T>> results) {
        return new TaskBatchResult<>(Futures.immediateVoidFuture(), results, bodyCompletion, null, null);
    }

    /**
     * Creates a result for a batch whose submissions may still be running.
     *
     * @param <T> the element result type
     * @param submitCanceller the future running the remaining submissions
     * @param results the individual result futures
     * @return a new batch result
     */
    static <T> TaskBatchResult<T> of(ListenableFuture<?> submitCanceller, List<? extends TaskFuture<T>> results) {
        return new TaskBatchResult<>(submitCanceller, results, BodyCompletionTracker.empty(), null, null);
    }

    /**
     * Creates a result for a batch whose submissions may still be running, carrying its
     * body-completion signal, its cancellation token, and its close grace.
     *
     * @param <T> the element result type
     * @param bodyCompletion shared task-body completion signal of this submission
     * @param submitCanceller the future running the remaining submissions
     * @param results the individual result futures
     * @param token the batch cancellation token used by {@link #close()}
     * @param closeGrace the close grace used by {@link #close()}
     * @return a new batch result
     */
    static <T> TaskBatchResult<T> of(
            BodyCompletionTracker bodyCompletion,
            ListenableFuture<?> submitCanceller,
            List<? extends TaskFuture<T>> results,
            CancellationToken token,
            @Nullable Duration closeGrace) {
        return new TaskBatchResult<>(submitCanceller, results, bodyCompletion, token, closeGrace);
    }

    /**
     * Cancels every unfinished element, then waits for task bodies to exit within the batch's close
     * grace, and returns.
     *
     * <p>This is the batch's structured-close entry, symmetric with {@link TaskGroup#close()}:
     * cancellation goes through the batch token, so every element and the submission loop are
     * cancelled with the usual attribution. The close grace is a cleanup budget configured on
     * {@link BatchOptions#closeGrace(Duration)}, independent of the batch's execution timeout.
     * When never configured, the wait budget is derived from the batch's remaining deadline at
     * close time; {@link Duration#ZERO} makes this method cancel-only. When the grace elapses with
     * bodies still running, the outstanding task names are logged at WARN level. The executor is
     * never shut down, and user code that ignores interruption may keep running after this method
     * returns.
     *
     * <p>If the calling thread is interrupted on entry, the cancellation still runs and the wait
     * is skipped with the interrupt flag preserved. A normal return does not by itself make
     * resources used by task bodies safe to release; confirm body exit with {@link
     * #awaitBodyCompletion(Duration)} first.
     *
     * @throws IllegalStateException if called from within a task body of this batch, including a
     *     nested inline call on the same thread
     */
    @Override
    public void close() {
        CancellationToken batchToken = token;
        BodyCompletionTracker.cancelAndAwaitBodyExit(
                () -> {
                    if (batchToken != null) {
                        batchToken.cancel();
                    } else {
                        submitCanceller.cancel(true);
                    }
                },
                bodyCompletion,
                closeGraceBudgetNanos(),
                "batch '" + (results.isEmpty() ? "?" : results.get(0).taskName()) + "'",
                LOGGER);
    }

    /**
     * The close wait budget: the configured close grace when present, otherwise the remaining
     * execution deadline carried by the batch token. A non-positive result — or no derivable
     * budget — means cancel-only.
     */
    private long closeGraceBudgetNanos() {
        Duration configured = closeGrace;
        if (configured != null) {
            return saturatedNanos(configured);
        }
        CancellationToken batchToken = token;
        if (batchToken == null || batchToken.deadlineNanos() == Long.MAX_VALUE) {
            return 0;
        }
        return batchToken.deadlineNanos() - System.nanoTime();
    }

    /**
     * Waits until every task body of this batch has exited, or the budget elapses.
     *
     * <p>Body exit means the user function returned or threw and its {@code finally} completed;
     * listener callbacks are not covered. A {@code true} result also covers tasks that will never
     * be entered (cancelled, rejected, or abandoned before execution) and establishes a
     * happens-before edge from every task body's writes to this thread; once {@code true}, the
     * result cannot be invalidated by a task starting late. {@code false} means the budget elapsed
     * while at least one body had not exited, which may include tasks that have not started yet.
     *
     * <p>This method never cancels tasks and does not require prior cancellation: cancel through
     * {@link #submitCanceller()} or the element futures first when shutdown is intended, then wait
     * here. A zero timeout performs a single check.
     *
     * @param timeout the cleanup wait budget; independent of the batch's execution deadline
     * @return {@code true} if all task bodies exited within the budget
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws IllegalStateException if called from within a task body of this batch
     * @throws InterruptedException if the calling thread is interrupted before or during the wait
     */
    public boolean awaitBodyCompletion(Duration timeout) throws InterruptedException {
        return bodyCompletion.awaitBodyCompletion(timeout);
    }

    /**
     * Generates execution report: counts tasks by outcome and extracts first failure exception.
     *
     * <p>Each element is classified by {@link TaskFuture#outcome()} from its own token, which is
     * the batch's single token: {@code TIMEOUT} for deadline expiry, {@code FAIL_FAST} for the
     * cascade after a sibling failure, {@code GROUP_CANCELED} for batch-level or propagated
     * cancellation, and {@code MEMBER_CANCELED} when no framework path committed (a direct
     * cancellation). A failure that merely signals observed cancellation (a cooperative checkpoint
     * or an interrupt racing the cascade cancel) is attributed the same way instead of reading
     * {@code USER_FAILURE}. The batch shares one token across all elements, so an element whose
     * direct cancellation <em>triggered</em> the fail-fast cascade also reads {@code FAIL_FAST};
     * distinguishing the initiator per element requires a {@code TaskGroup}.
     *
     * @return a BatchReport containing outcome counts and the first exception (if any)
     */
    public BatchReport report() {
        Map<TaskOutcome, Integer> outcomeMap =
                results.stream().collect(toImmutableEnumMap(FutureInspector::outcome, x -> 1, Integer::sum));
        Throwable firstException = results.stream()
                .map(TaskFuture::failure)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new BatchReport(outcomeMap, firstException);
    }

    /**
     * Returns a human-readable summary string of the execution report.
     *
     * <p>Format: {@code STATE1:count,STATE2:count | firstException=message}
     *
     * @return formatted report string
     */
    public String reportString() {
        BatchReport r = report();
        StringBuilder sb =
                new StringBuilder(Joiner.on(',').withKeyValueSeparator(':').join(r.stateCounts()));
        if (r.firstException() != null) {
            sb.append(" | firstException=").append(r.firstException().getMessage());
        }
        return sb.toString();
    }

    /** Immutable report of batch task execution state. */
    public static final class BatchReport {
        private final Map<TaskOutcome, Integer> stateCounts;
        private final Throwable firstException;

        /**
         * Creates a batch report.
         *
         * @param stateCounts counts keyed by terminal or current future state
         * @param firstException the first observed failure, or {@code null}
         */
        BatchReport(Map<TaskOutcome, Integer> stateCounts, @Nullable Throwable firstException) {
            this.stateCounts = Maps.immutableEnumMap(Objects.requireNonNull(stateCounts, "stateCounts cannot be null"));
            this.firstException = firstException;
        }

        /**
         * Provides counts by future state, for example {@code SUCCESS=3, FAILED=1}.
         *
         * @return the immutable state count map, empty when the batch had no elements
         */
        public Map<TaskOutcome, Integer> stateCounts() {
            return stateCounts;
        }

        /**
         * Returns the first exception from failed tasks.
         *
         * @return the first failure, or {@code null} if no task failed
         */
        @Nullable
        public Throwable firstException() {
            return firstException;
        }

        @Override
        public String toString() {
            return "BatchReport{stateCounts=" + stateCounts + ", firstException=" + firstException + '}';
        }
    }
}

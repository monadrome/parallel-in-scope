package io.github.monadrome.parallelinscope;

import static com.google.common.collect.Maps.toImmutableEnumMap;

import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

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
final class TaskBatch<T> implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(TaskBatch.class.getName());

    private final ListenableFuture<?> submitCanceller;
    private final List<TaskFuture<T>> results;
    private final BodyCompletionTracker bodyCompletion;
    private final @Nullable CancellationToken token;
    private final @Nullable Duration closeGrace;
    private final ListenableFuture<List<TaskCompletion<T>>> completionView;

    private TaskBatch(
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
        this.completionView = aggregateObservations(this.results);
    }

    /**
     * Aggregates the element observation futures into the batch observation sink: once every
     * element observation is published — which already waits out both future settle and body exit
     * per element — the sink is set with the input-ordered immutable list. Element observations
     * never fail, so a failure here is an implementation defect and fails the sink loudly instead
     * of leaving it pending.
     */
    private static <T> ListenableFuture<List<TaskCompletion<T>>> aggregateObservations(List<TaskFuture<T>> results) {
        SettableFuture<List<TaskCompletion<T>>> sink = SettableFuture.create();
        List<ListenableFuture<TaskCompletion<T>>> observations = new ArrayList<>(results.size());
        for (TaskFuture<T> element : results) {
            observations.add(viewOf(element).observationView());
        }
        Futures.addCallback(
                Futures.allAsList(observations),
                new FutureCallback<List<TaskCompletion<T>>>() {
                    @Override
                    public void onSuccess(@Nullable List<TaskCompletion<T>> completions) {
                        // allAsList of a fixed list never yields null.
                        sink.set(ImmutableList.copyOf(Verify.verifyNotNull(completions)));
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        sink.setException(failure);
                    }
                },
                MoreExecutors.directExecutor());
        return TaskObservation.readOnly(sink);
    }

    /**
     * The library's own view of a batch element. Every element handed to this constructor is the
     * library's {@link Task}; the constructor is package-private, so no foreign {@link TaskFuture}
     * can reach this cast.
     */
    @SuppressWarnings("unchecked")
    private static <T> Task<T> viewOf(TaskFuture<T> element) {
        return (Task<T>) element;
    }

    /**
     * Provides the future running the sliding-window submission loop. Cancelling it stops further
     * submissions and abandons every element not yet handed to the executor, so no future of this
     * batch stays pending; cancelling an element's own view directly completes it as {@code
     * CANCELLED}.
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

    TaskBatchResult<T> finish() {
        awaitElementsAndSubmission();
        long start = System.nanoTime();
        long budget = BodyCompletionTracker.closeGraceBudgetNanos(closeGrace, token);
        awaitBarriers(budget, start);
        return freezeResults();
    }

    /**
     * The {@link #finish()} of a batch carrying a terminal combine: additionally waits out the
     * combine's future and observation before freezing, and returns the combine's own result and
     * observation next to the element batch. The combine's body slot is part of the shared tracker,
     * so the body-exit barrier already covers it.
     */
    <C> BatchCombinedResult<T, C> finishCombined(Task<C> terminal) {
        awaitElementsAndSubmission();
        try {
            Uninterruptibles.getUninterruptibly(terminal);
        } catch (ExecutionException | CancellationException settled) {
            // The combine's outcome is attribution data, frozen below.
        }
        long start = System.nanoTime();
        long budget = BodyCompletionTracker.closeGraceBudgetNanos(closeGrace, token);
        awaitBarriers(budget, start);
        BodyCompletionTracker.awaitSettledUninterruptibly(
                terminal.observationView(), budget, start, "terminal observation signal");
        TaskBatchResult<T> frozen = freezeResults();
        ImmediateResult<C> terminalResult = ImmediateResult.fromTask(terminal);
        TaskCompletion<C> terminalObservation = null;
        ListenableFuture<TaskCompletion<C>> observation = terminal.observationView();
        if (observation.isDone()) {
            try {
                terminalObservation =
                        TaskCompletion.withResult(Verify.verifyNotNull(Futures.getDone(observation)), terminalResult);
            } catch (ExecutionException impossible) {
                throw new AssertionError("terminal observation cannot fail", impossible);
            }
        }
        return BatchCombinedResult.of(frozen, terminalResult, terminalObservation);
    }

    /** Waits out every element future and the submission loop, uninterruptibly. */
    private void awaitElementsAndSubmission() {
        for (TaskFuture<T> result : results) {
            try {
                Uninterruptibles.getUninterruptibly(result);
            } catch (ExecutionException | CancellationException settled) {
                // Outcome data is frozen below, after every task has settled.
            }
        }
        try {
            Uninterruptibles.getUninterruptibly(submitCanceller);
        } catch (ExecutionException | CancellationException settled) {
            // A cancelled submission loop is already stopped; prepared tasks cannot start late.
        }
    }

    /** Waits out the body-exit and element-observation barriers within the cleanup budget. */
    private void awaitBarriers(long budget, long start) {
        BodyCompletionTracker.awaitSettledUninterruptibly(bodyCompletion.bodyExit(), budget, start, "body-exit signal");
        BodyCompletionTracker.awaitSettledUninterruptibly(completionView, budget, start, "batch observation signal");
    }

    /** Freezes per-element results and observations; every future is terminal by here. */
    private TaskBatchResult<T> freezeResults() {
        List<ImmediateResult<T>> frozen = new ArrayList<>(results.size());
        List<@Nullable TaskCompletion<T>> observations = new ArrayList<>(results.size());
        for (TaskFuture<T> result : results) {
            ImmediateResult<T> immediate = ImmediateResult.fromTask(viewOf(result));
            frozen.add(immediate);
            ListenableFuture<TaskCompletion<T>> observation = result.completionFuture();
            try {
                observations.add(
                        observation.isDone()
                                ? TaskCompletion.withResult(
                                        Verify.verifyNotNull(Futures.getDone(observation)), immediate)
                                : null);
            } catch (ExecutionException impossible) {
                throw new AssertionError("task observation cannot fail", impossible);
            }
        }
        Map<String, Integer> unfinished = bodyCompletion.stuckBodySummary();
        BodyCompletionTracker.warnUnfinished("batch", unfinished, LOGGER);
        return new TaskBatchResult<>(frozen, observations, unfinished);
    }

    /**
     * Returns the batch observation future: the input-ordered, immutable list of every element's
     * final {@link TaskCompletion} snapshot — identity, submit/start/end times, queue wait,
     * outcome, failure, and on success the result.
     *
     * <p>Each element snapshot is published only after its future is terminal <em>and</em> its
     * task body has exited (or was determined to never run), so the recorded end times are always
     * final; the aggregate completes only once every element snapshot is published. Elements that
     * never started — rejected, cancelled, or abandoned by the sliding window — are included with
     * zero start/end times and their real outcome. Once the batch scope has completed this future
     * is guaranteed to be done: {@link #awaitBodyCompletion(Duration)} returning {@code true}
     * implies the data is already available, with no extra wait. A {@link #close()} that exhausts
     * its close grace while bodies ignore interruption may leave this future pending; it then
     * completes as the remaining bodies exit.
     *
     * <p>Element failure and cancellation complete this future successfully with the real outcome
     * data; it never returns {@code null}, never requires polling, and ignores cancellation
     * ({@code cancel(...)} returns {@code false}). This is the full-fidelity observation entry —
     * timing, failures, and queue wait — while {@link #report()} stays the lightweight outcome
     * summary. Register immediate reactions with {@code Futures.addCallback} on an executor of
     * the caller's choice.
     *
     * @return the batch observation future
     */
    public ListenableFuture<List<TaskCompletion<T>>> completionFuture() {
        return completionView;
    }

    /**
     * Waits for every element and returns its values in input order — the common "run these, give
     * me the results, fail if any failed" path in one call.
     *
     * <p>Unlike {@link #results()}, forgetting to handle failure is not possible here: the first
     * recorded element failure in input order takes precedence over fail-fast cancellation and
     * propagates as an {@link ExecutionException}; cancellation with no recorded failure surfaces
     * as {@link CancellationException}; and an interrupted wait restores the interrupt flag and
     * throws {@link LeanCancellationException}.
     *
     * @return the element values in input order
     * @throws ExecutionException if any element failed
     * @throws CancellationException if any element was cancelled
     * @throws LeanCancellationException if the calling thread is interrupted while waiting
     */
    public List<T> valuesOrThrow() throws ExecutionException {
        try {
            return Futures.allAsList(results).get();
        } catch (ExecutionException aggregateFailure) {
            Throwable recordedFailure = firstRecordedFailure();
            if (recordedFailure == null) {
                recordedFailure = aggregateFailure.getCause();
            }
            throw new ExecutionException(recordedFailure);
        } catch (CancellationException cancellation) {
            Throwable recordedFailure = firstRecordedFailure();
            if (recordedFailure != null) {
                throw new ExecutionException(recordedFailure);
            }
            throw cancellation;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LeanCancellationException cancellation =
                    new LeanCancellationException("interrupted while awaiting batch values");
            cancellation.initCause(e);
            throw cancellation;
        }
    }

    private @Nullable Throwable firstRecordedFailure() {
        return results.stream()
                .map(TaskFuture::failure)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Creates a result for a fully submitted batch.
     *
     * @param <T> the element result type
     * @param results the individual result futures
     * @return a new batch result
     */
    static <T> TaskBatch<T> of(List<? extends TaskFuture<T>> results) {
        return new TaskBatch<>(Futures.immediateVoidFuture(), results, BodyCompletionTracker.empty(), null, null);
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
    static <T> TaskBatch<T> of(
            BodyCompletionTracker bodyCompletion,
            ListenableFuture<?> submitCanceller,
            List<? extends TaskFuture<T>> results,
            CancellationToken token,
            @Nullable Duration closeGrace) {
        return new TaskBatch<>(submitCanceller, results, bodyCompletion, token, closeGrace);
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
     * is skipped with the interrupt flag preserved. Cancellation runs before the wait and settles
     * every bound element future synchronously, so once this method returns, every future in
     * {@link #results()} is terminal — even when a body is still unwinding. A normal return does
     * not by itself make resources used by task bodies safe to release; confirm body exit with
     * {@link #awaitBodyCompletion(Duration)} first.
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
                BodyCompletionTracker.closeGraceBudgetNanos(closeGrace, token),
                "batch '" + (results.isEmpty() ? "?" : results.get(0).taskName()) + "'",
                LOGGER);
    }

    /**
     * Waits until every task body of this batch has exited and every element future has settled,
     * or the budget elapses.
     *
     * <p>Body exit means the user function returned or threw and its {@code finally} completed. A
     * body publishes its exit before its future settles, so body exit alone does not imply a
     * terminal future; this method waits out that window as well. A {@code true} result therefore
     * implies every future in {@link #results()} is terminal —
     * {@link #report()} and {@link #reportString()} called after it read a terminal snapshot — and
     * also covers tasks that will never be entered (cancelled, rejected, or abandoned before
     * execution). It additionally waits out the observation publication barrier, so a {@code true}
     * result also implies {@link #completionFuture()} is already done with the final snapshots.
     * It establishes a happens-before edge from every task body's writes to this
     * thread; once {@code true}, the result cannot be invalidated by a task starting late. {@code
     * false} means the budget elapsed while at least one body had not exited or one future had not
     * settled, which may include tasks that have not started yet.
     *
     * <p>This method never cancels tasks and does not require prior cancellation: cancel through
     * {@link #submitCanceller()} or the element futures first when shutdown is intended, then wait
     * here. A zero timeout performs a single check.
     *
     * @param timeout the cleanup wait budget; independent of the batch's execution deadline
     * @return {@code true} if all task bodies exited and all element futures settled within the
     *     budget
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws IllegalStateException if called from within a task body of this batch
     * @throws InterruptedException if the calling thread is interrupted before or during the wait
     */
    public boolean awaitBodyCompletion(Duration timeout) throws InterruptedException {
        long budgetNanos = Deadlines.saturatedNanos(Objects.requireNonNull(timeout, "timeout cannot be null"));
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        long startNanos = System.nanoTime();
        if (!bodyCompletion.awaitBodyCompletion(timeout)) {
            return false;
        }
        for (TaskFuture<T> future : results) {
            if (!BodyCompletionTracker.awaitSettled(future, budgetNanos, startNanos, null)) {
                return false;
            }
        }
        // Bodies exited and futures settled, so every element observation is published or about to
        // be: the publication barrier fires on the same signals this method just waited out, but a
        // future's get() waiters can wake before its listeners run. Wait out that window too, so a
        // true result guarantees completionFuture() already carries the final snapshots.
        return BodyCompletionTracker.awaitSettled(completionView, budgetNanos, startNanos, "batch observation signal");
    }

    /**
     * Generates execution report: counts tasks by outcome and extracts first failure exception.
     *
     * <p>Counts reflect the futures' states at call time: an element whose body has exited but
     * whose future has not settled yet still reads {@code RUNNING}. For a terminal snapshot, call
     * this after {@link #awaitBodyCompletion(Duration)} returned {@code true} or after {@link
     * #close()} returned — both guarantee every element future is terminal.
     *
     * <p>Each element is classified by {@link TaskFuture#outcome()} from its own token, which is
     * the batch's single token: {@code TIMEOUT} for deadline expiry, {@code FAIL_FAST} for the
     * cascade after a sibling failure, {@code GROUP_CANCELLED} for batch-level or propagated
     * cancellation, and {@code MEMBER_CANCELLED} when no framework path committed (a direct
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
                results.stream().collect(toImmutableEnumMap(TaskFuture::outcome, x -> 1, Integer::sum));
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
    static final class BatchReport {
        private final Map<TaskOutcome, Integer> stateCounts;
        private final @Nullable Throwable firstException;

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
         * Provides counts by outcome, for example {@code SUCCESS=3, USER_FAILURE=1}.
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
        public @Nullable Throwable firstException() {
            return firstException;
        }

        @Override
        public String toString() {
            return "BatchReport{stateCounts=" + stateCounts + ", firstException=" + firstException + '}';
        }
    }
}

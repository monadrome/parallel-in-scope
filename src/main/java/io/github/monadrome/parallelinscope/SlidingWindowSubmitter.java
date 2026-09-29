package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Sliding-window concurrency limiter for task execution.
 *
 * <p>Implements a "submit one when one completes" pattern:
 *
 * <ol>
 *   <li>Submits an initial batch equal to {@code parallelism}
 *   <li>Uses a blocking queue populated by completion listeners on the submitted futures to detect
 *       completion events
 *   <li>Fills freed slots incrementally with remaining tasks
 * </ol>
 *
 * <p>Each submitted future is also the exact runnable handed to the worker pool, so cancelling it
 * is directly visible to queue maintenance such as {@code ThreadPoolExecutor.purge()}.
 *
 * @param <V> the result type of tasks
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class SlidingWindowSubmitter<V> {

    private static final Logger LOGGER = Logger.getLogger(SlidingWindowSubmitter.class.getName());

    private final ListeningExecutorService pool;
    private final BlockingQueue<ListenableFuture<V>> blockingQueue = new LinkedBlockingQueue<>();
    private final MultiTaskContext unit;
    private final ListeningExecutorService submitterPool;
    private final BodyCompletionTracker bodyCompletion;
    private final @Nullable Duration closeGrace;

    /** Creates a submitter for the new immutable multi-task unit. */
    public SlidingWindowSubmitter(
            ListeningExecutorService pool, MultiTaskContext unit, ListeningExecutorService submitterPool) {
        this(pool, unit, submitterPool, BodyCompletionTracker.empty(), null);
    }

    /**
     * Creates a submitter for the new immutable multi-task unit, carrying the submission's shared
     * body-completion signal and close grace. The tracker must have registered one slot per
     * prepared task before {@link #submitAll(List)} runs.
     */
    public SlidingWindowSubmitter(
            ListeningExecutorService pool,
            MultiTaskContext unit,
            ListeningExecutorService submitterPool,
            BodyCompletionTracker bodyCompletion,
            @Nullable Duration closeGrace) {
        this.unit = Objects.requireNonNull(unit, "unit cannot be null");
        this.submitterPool = Objects.requireNonNull(submitterPool, "submitterPool cannot be null");
        this.bodyCompletion = Objects.requireNonNull(bodyCompletion, "bodyCompletion cannot be null");
        this.closeGrace = closeGrace;
        this.pool = Objects.requireNonNull(pool, "pool cannot be null");
    }

    /**
     * Builds the caller-visible view of every element, before anything is submitted.
     *
     * <p>Each view wraps the prepared future directly, so it is the element's final handle from
     * creation on — no placeholder stands in for an element still waiting for a free slot, and no
     * later bind step swaps a delegate. That is what lets the caller bind its {@link
     * CancellationToken} before submission: the token holds the same objects the caller does, and
     * cancelling one forwards to the prepared future, whose {@code interruptTask()} reaches the
     * thread actually running the body — including a caller thread running it inline.
     *
     * @param tasks prepared task futures, in element order
     * @return the element views, positionally aligned with {@code tasks}
     */
    ImmutableList<Task<V>> viewsFor(List<? extends ExecutionPhaseHintFuture<V>> tasks) {
        ImmutableList.Builder<Task<V>> views = ImmutableList.builderWithExpectedSize(tasks.size());
        for (ExecutionPhaseHintFuture<V> task : tasks) {
            views.add(Task.of(unit.name(), unit.cancellationToken(), task));
        }
        return views.build();
    }

    /**
     * Submits all tasks and returns the batch result immediately.
     *
     * <p>Each returned future is the {@link Task} view of the exact {@link ExecutionPhaseHintFuture}
     * passed in: the caller prepares tasks via {@link TaskSubmissions} and builds their views with
     * {@link #viewsFor}, and this executor only coordinates when each prepared future enters the
     * worker pool.
     *
     * @param tasks prepared task futures to execute
     * @param results the element views from {@link #viewsFor}, positionally aligned with {@code
     *     tasks}; taken as a parameter so the caller can bind them before submission starts
     * @return TaskBatchResult containing individual task futures
     */
    public TaskBatchResult<V> submitAll(List<? extends ExecutionPhaseHintFuture<V>> tasks, List<Task<V>> results) {
        if (tasks.isEmpty()) {
            return TaskBatchResult.of(
                    bodyCompletion,
                    Futures.immediateVoidFuture(),
                    ImmutableList.of(),
                    unit.cancellationToken(),
                    closeGrace);
        }

        int start = Math.min(tasks.size(), parallelism());

        for (int i = 0; i < start; i++) {
            try {
                fallbackSubmit(tasks, i);
            } catch (Throwable failure) {
                // Catching Throwable, not only RuntimeException | Error: execute(Runnable) declares
                // no checked exceptions, but a hostile executor can still throw one through
                // generics erasure, and any handoff failure must terminate the batch the same way.
                // A handoff failure — rejection or the executor throwing mid-handoff — is the
                // batch's shared verdict for every element; wrapping it keeps each element
                // attributed as a submission failure rather than a user one.
                logHandoffError(failure, i, "the initial window");
                failRemainingAsSubmissionFailures(tasks, i, failure);
                return TaskBatchResult.of(
                        bodyCompletion, Futures.immediateVoidFuture(), results, unit.cancellationToken(), closeGrace);
            }
        }

        int remaining = tasks.size() - start;
        if (remaining <= 0) {
            return TaskBatchResult.of(
                    bodyCompletion, Futures.immediateVoidFuture(), results, unit.cancellationToken(), closeGrace);
        }

        AtomicInteger nextIndex = new AtomicInteger(start);
        ListenableFuture<?> submittingFuture = submitterPool.submit(() -> submitRemaining(tasks, results, nextIndex));
        // A cancellation may win before the submitter thread starts. In that case the callable
        // never gets a chance to abandon its placeholders, so close them from the cancellation
        // callback as well. The submitter loop remains responsible for normal interruption.
        submittingFuture.addListener(
                () -> {
                    if (submittingFuture.isCancelled()) {
                        abandonRemaining(
                                tasks,
                                results,
                                nextIndex.get(),
                                new InterruptedException("remaining task submission cancelled"));
                    }
                },
                directExecutor());

        return TaskBatchResult.of(bodyCompletion, submittingFuture, results, unit.cancellationToken(), closeGrace);
    }

    /**
     * Fails every element from {@code fromIndex} on as a submission failure, in two passes.
     *
     * <p>A handoff failure is the batch's shared verdict: the element that hit it never reached the
     * executor, and neither will any element after it. Every one of them must report {@code
     * SUBMISSION_FAILURE} with the original throwable as cause, which is the contract in
     * {@code design/batch-submission-failure-semantics.md}.
     *
     * <p>Two passes, because the token is already bound by the time this runs. Settling any element
     * exceptionally fires the token's fail-fast cascade synchronously, on this thread, and that
     * cascade cancels every sibling still pending — which would overwrite their verdict with a
     * cancellation and lose the reason the batch failed. No settle order avoids this: whichever
     * element settles first triggers the cascade against the rest. So pass one claims each element
     * and records its attribution without settling anything (invisible to the token, which watches
     * the futures rather than the observations), and pass two settles them. An element cancelled by
     * the cascade in between still reports its recorded submission failure.
     *
     * <p>The cascade does still cancel the elements before {@code fromIndex} — the ones already
     * handed to the executor. That is the intended fail-fast behaviour and unchanged from before.
     */
    private void failRemainingAsSubmissionFailures(
            List<? extends ExecutionPhaseHintFuture<V>> tasks, int fromIndex, Throwable failure) {
        int size = tasks.size();
        boolean[] claimed = new boolean[size - fromIndex];
        for (int pending = fromIndex; pending < size; pending++) {
            claimed[pending - fromIndex] = tasks.get(pending).claimSubmissionFailure(failure);
        }
        for (int pending = fromIndex; pending < size; pending++) {
            if (claimed[pending - fromIndex]) {
                tasks.get(pending).settleSubmissionFailure();
            }
        }
    }

    private void fallbackSubmit(List<? extends ExecutionPhaseHintFuture<V>> tasks, int i) {
        ExecutionPhaseHintFuture<V> task = tasks.get(i);
        MultiTaskContext previous = SubmissionScope.install(unit);
        try {
            submit(task);
        } finally {
            SubmissionScope.restore(previous);
        }
    }

    /**
     * Submits a prepared future to the worker pool and returns it. The listener is registered
     * before the handoff, so a task rejected or canceled before it runs still reaches the
     * completion queue that drives the sliding window.
     */
    private ListenableFuture<V> submit(ExecutionPhaseHintFuture<V> task) {
        task.addListener(() -> blockingQueue.add(task), directExecutor());
        pool.execute(task);
        return task;
    }

    /**
     * Reports an executor handoff {@code Error} once, at the catch site that records it. Rejections
     * are ordinary control flow and stay quiet; an {@code Error} escaping {@code execute()} signals
     * a broken executor or a failing VM and is always worth an operator's attention. The batch path
     * never reaches {@code ExecutionPhaseHintFuture.submitPrepared}, so this is the only place the
     * failure is logged — no site logs it twice.
     */
    private void logHandoffError(Throwable failure, int index, String phase) {
        if (failure instanceof Error) {
            LOGGER.log(
                    Level.SEVERE,
                    failure,
                    () -> "executor handoff threw an Error in batch '" + unit.name() + "' at element " + index
                            + " during " + phase
                            + "; the affected elements are failed as submission failures");
        }
    }

    private int parallelism() {
        return unit.effectiveParallelism();
    }

    private int submitRemaining(
            List<? extends ExecutionPhaseHintFuture<V>> tasks, List<Task<V>> result, AtomicInteger nextIndex) {
        int index = nextIndex.get();
        int size = tasks.size();
        int submitted = 0;
        while (index < size) {
            ListenableFuture<V> completed;
            try {
                completed = blockingQueue.take();
            } catch (InterruptedException e) {
                abandonRemaining(tasks, result, index, e);
                Thread.currentThread().interrupt();
                return submitted;
            }
            // Claim the index as soon as a slot is taken, before the completion check: the
            // cancellation callback abandons only indexes strictly beyond nextIndex, so it can
            // never overwrite an index this iteration already claimed. The cancelled/done branch
            // below still abandons from index locally, covering this iteration as well.
            nextIndex.set(index + 1);
            if (completed.isCancelled() || result.get(index).isDone()) {
                abandonRemaining(tasks, result, index, null);
                return submitted;
            }
            if (Thread.currentThread().isInterrupted()) {
                abandonRemaining(
                        tasks,
                        result,
                        index,
                        new InterruptedException("submitter thread interrupted while scheduling remaining tasks"));
                Thread.currentThread().interrupt();
                return submitted;
            }
            try {
                // PROTOTYPE: no bind step — the view already wraps this prepared future.
                fallbackSubmit(tasks, index);
            } catch (Throwable failure) {
                // Same Throwable audit as the initial window: a sneaky checked throwable must not
                // escape this loop either. The submission future retains the failure for
                // diagnostics; unchecked types keep their identity, a checked one is wrapped.
                logHandoffError(failure, index, "the sliding-window refill");
                abandonRemaining(tasks, result, index, failure);
                Throwables.throwIfUnchecked(failure);
                throw new RuntimeException(failure);
            }
            submitted++;
            index++;
        }
        return submitted;
    }

    /**
     * Completes every future that will never receive a submission so the batch always reaches a
     * terminal state. Direct placeholder cancellation produces {@code CANCELLED}; an interrupted
     * submitter or rejected submission records its cause. Without this cleanup, {@link
     * Futures#allAsList} could wait forever and hide the reason in {@link TaskBatchResult#report()}.
     *
     * <p>The prepared futures behind the abandoned placeholders are never submitted and never
     * cancelled, so their body slots are released here as skipped — exactly once, guarded by the
     * same atomic state the cancel-before-run path uses. The skip runs before the placeholder is
     * settled so that a caller observing the abandonment (a thrown {@code valuesOrThrow}, a
     * report) already finds the prepared body released and its slot published.
     *
     * @param tasks the prepared task futures, positionally aligned with {@code result}
     * @param result the batch futures
     * @param fromIndex the first never-submitted future index (inclusive)
     * @param reason the failure reported for the abandoned futures, or {@code null} to cancel them
     *     when the batch is already being canceled
     */
    private static <V> void abandonRemaining(
            List<? extends ExecutionPhaseHintFuture<V>> tasks,
            List<Task<V>> result,
            int fromIndex,
            @Nullable Throwable reason) {
        // Settle the prepared future directly: its own phase CAS makes this idempotent and keeps
        // the body unentered, and the view over it reports the outcome to the caller.
        if (reason == null) {
            for (int i = fromIndex; i < result.size(); i++) {
                tasks.get(i).skipBody();
                tasks.get(i).cancel(true);
            }
            return;
        }
        // A reason means a handoff failure in the refill loop, which carries the same shared-verdict
        // semantics as the initial window, so it takes the same two-pass treatment.
        int size = result.size();
        boolean[] claimed = new boolean[size - fromIndex];
        for (int i = fromIndex; i < size; i++) {
            claimed[i - fromIndex] = tasks.get(i).claimSubmissionFailure(reason);
        }
        for (int i = fromIndex; i < size; i++) {
            if (claimed[i - fromIndex]) {
                tasks.get(i).settleSubmissionFailure();
            }
        }
    }
}

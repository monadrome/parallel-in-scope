package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li>Submits an initial batch equal to {@code parallelism} on the calling thread
 *   <li>Claims the next element for every completion event of a submitted element — the completion
 *       listener itself performs the refill, so no framework thread parks waiting for work
 *   <li>Stops claiming when the batch is cancelled, a handoff fails, or every element is claimed
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
    private final MultiTaskContext unit;
    private final BodyCompletionTracker bodyCompletion;
    private final @Nullable Duration closeGrace;

    /** Creates a submitter for the new immutable multi-task unit. */
    public SlidingWindowSubmitter(ListeningExecutorService pool, MultiTaskContext unit) {
        this(pool, unit, BodyCompletionTracker.empty(), null);
    }

    /**
     * Creates a submitter for the new immutable multi-task unit, carrying the submission's shared
     * body-completion signal and close grace. The tracker must have registered one slot per
     * prepared task before {@link #submitAll(List, List)} runs.
     */
    public SlidingWindowSubmitter(
            ListeningExecutorService pool,
            MultiTaskContext unit,
            BodyCompletionTracker bodyCompletion,
            @Nullable Duration closeGrace) {
        this.unit = Objects.requireNonNull(unit, "unit cannot be null");
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
     * @return TaskBatch containing individual task futures
     */
    public TaskBatch<V> submitAll(List<? extends ExecutionPhaseHintFuture<V>> tasks, List<Task<V>> results) {
        if (tasks.isEmpty()) {
            return TaskBatch.of(
                    bodyCompletion,
                    Futures.immediateVoidFuture(),
                    ImmutableList.of(),
                    unit.cancellationToken(),
                    closeGrace);
        }

        RefillDriver driver = new RefillDriver(tasks, results);
        // The initial window is claimed on this thread; on a direct executor or a saturated
        // CallerRunsPolicy pool the completions arrive inline, and the guard inside the driver
        // turns those nested completion events into iterations of its claim loop rather than
        // recursion.
        driver.drain();

        int remaining = tasks.size() - Math.min(tasks.size(), parallelism());
        if (remaining <= 0 || driver.initialHandoffFailure()) {
            return TaskBatch.of(
                    bodyCompletion, Futures.immediateVoidFuture(), results, unit.cancellationToken(), closeGrace);
        }

        ListenableFuture<Integer> signal = driver.signal();
        // A cancellation may win while a completion-triggered refill is between claiming an index
        // and handing it off. The listener claims every unclaimed index first, so the refill sees
        // the settled signal and disposes of its claimed index itself; the loop remains responsible
        // for normal completion.
        signal.addListener(
                () -> {
                    if (signal.isCancelled()) {
                        driver.abandonUnclaimed(new InterruptedException("remaining task submission cancelled"));
                    }
                },
                directExecutor());

        return TaskBatch.of(bodyCompletion, signal, results, unit.cancellationToken(), closeGrace);
    }

    /**
     * Fails every element from {@code fromIndex} on as a submission failure, in two passes.
     *
     * <p>A handoff failure is the batch's shared verdict: the element that hit it never reached the
     * executor, and neither will any element after it. Every one of them must report {@code
     * SUBMISSION_FAILURE} with the original throwable as cause, which is the contract recorded in
     * {@code design/decision-log.md}.
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
     * handed to the executor. That is the intended fail-fast behavior and unchanged from before.
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

    private void fallbackSubmit(List<? extends ExecutionPhaseHintFuture<V>> tasks, int i, Runnable onComplete) {
        ExecutionPhaseHintFuture<V> task = tasks.get(i);
        MultiTaskContext previous = SubmissionScope.install(unit);
        try {
            submit(task, onComplete);
        } finally {
            SubmissionScope.restore(previous);
        }
    }

    /**
     * Submits a prepared future to the worker pool. The listener is registered before the handoff,
     * so a task rejected or cancelled before it runs still releases its window slot when it
     * settles.
     *
     * <p>A task already terminal when its turn comes — cancelled by the deadline cascade while
     * the window was still filling, for example — is not handed to the pool at all: its {@code
     * run()} would CAS-fail and no-op, so the handoff is pure waste. The listener fires immediately
     * for a done future, releasing the slot for the next claim.
     */
    private void submit(ExecutionPhaseHintFuture<V> task, Runnable onComplete) {
        task.addListener(onComplete, directExecutor());
        if (!task.isDone()) {
            pool.execute(task);
        }
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
            try {
                LOGGER.log(
                        Level.SEVERE,
                        failure,
                        () -> "executor handoff threw an Error in batch '" + unit.name() + "' at element " + index
                                + " during " + phase
                                + "; the affected elements are failed as submission failures");
            } catch (Throwable ignored) {
                // A user-installed log handler must not skip the shared-verdict failure of the
                // remaining elements.
            }
        }
    }

    private int parallelism() {
        return unit.effectiveParallelism();
    }

    /**
     * Event-driven sliding-window refill for one batch.
     *
     * <p>Instead of a framework thread parked in a completion queue (one per concurrently refilling
     * batch), every submitted element's completion listener claims and hands off the next element
     * itself. Claims are serialized by a single non-reentrant guard so that an inline completion —
     * a direct executor or a saturated CallerRunsPolicy pool running the next body on the
     * completing thread — extends the guard holder's loop instead of recursing.
     *
     * <p>The slot invariant: a claim consumes one window slot, the element's completion releases it
     * exactly once, and at most {@code start} claims are outstanding, so no more than {@code
     * parallelism} elements of this batch are ever in flight. Cancellation claims every unclaimed
     * index first ({@link #abandonUnclaimed}), so a refill that already claimed an index disposes
     * of it locally and never hands a cancelled batch's element to the pool.
     */
    private final class RefillDriver {

        private final List<? extends ExecutionPhaseHintFuture<V>> tasks;
        private final List<Task<V>> results;
        private final int size;
        private final int start;
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final AtomicInteger freeSlots;
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicInteger refillCount = new AtomicInteger();
        private final SettableFuture<Integer> signal = SettableFuture.create();
        private @Nullable Throwable initialFailure;

        RefillDriver(List<? extends ExecutionPhaseHintFuture<V>> tasks, List<Task<V>> results) {
            this.tasks = tasks;
            this.results = results;
            this.size = tasks.size();
            this.start = Math.min(size, parallelism());
            this.freeSlots = new AtomicInteger(start);
        }

        ListenableFuture<Integer> signal() {
            return signal;
        }

        /** Whether an initial-window handoff failed; the caller then mirrors the pre-refill shape. */
        boolean initialHandoffFailure() {
            return initialFailure != null;
        }

        /**
         * Claims and hands off elements while a window slot is free and elements remain. Runs on
         * the submitter for the initial window and afterwards on whichever thread completes an
         * element; concurrent completion events lose the guard and rely on the holder's loop.
         */
        void drain() {
            while (true) {
                if (!draining.compareAndSet(false, true)) {
                    return;
                }
                try {
                    while (nextIndex.get() < size && freeSlots.get() > 0) {
                        if (!handoff(nextIndex.getAndIncrement())) {
                            return;
                        }
                    }
                    if (nextIndex.get() >= size) {
                        settleSuccess();
                    }
                } finally {
                    draining.set(false);
                }
                // A completion may have released its slot just before the guard dropped; that
                // event's own drain attempt lost the guard and returned, so retake it when
                // claimable work is still left.
                if (settled.get() || nextIndex.get() >= size || freeSlots.get() <= 0) {
                    return;
                }
            }
        }

        /**
         * Hands off one claimed element. Initial-window claims skip a terminal element without
         * disturbing live siblings; refill claims treat one as the batch winding down and abandon
         * the rest. Returns {@code false} when the batch is settled and the drain must stop.
         */
        private boolean handoff(int index) {
            if (index >= size) {
                // The cancellation path's nextIndex.getAndSet(size) can land between the claim
                // loop's range check and the increment, so the increment observes the phantom
                // index size. No element exists for it; the batch is already settled and the claim
                // loop's next range check exits.
                return false;
            }
            freeSlots.decrementAndGet();
            ExecutionPhaseHintFuture<V> task = tasks.get(index);
            if (index < start) {
                try {
                    // No bind step — the view already wraps this prepared future.
                    fallbackSubmit(tasks, index, this::onComplete);
                } catch (Throwable failure) {
                    // Catching Throwable, not only RuntimeException | Error: execute(Runnable)
                    // declares no checked exceptions, but a hostile executor can still throw one
                    // through generics erasure, and any handoff failure must terminate the batch
                    // the same way. A handoff failure — rejection or the executor throwing
                    // mid-handoff — is the batch's shared verdict for every element; wrapping it
                    // keeps each element attributed as a submission failure rather than a user one.
                    logHandoffError(failure, index, "the initial window");
                    failRemainingAsSubmissionFailures(tasks, index, failure);
                    initialFailure = failure;
                    settleFailure(failure);
                    return false;
                }
                return true;
            }
            if (settled.get() || task.isDone()) {
                // Cancelled between the claim and the handoff, or already settled by the deadline
                // cascade: never submitted, so abandon from this index on and stop claiming.
                abandonRemaining(tasks, results, index, null);
                settleSuccess();
                return false;
            }
            try {
                fallbackSubmit(tasks, index, this::onComplete);
                refillCount.incrementAndGet();
                return true;
            } catch (Throwable failure) {
                // Same Throwable audit as the initial window: a sneaky checked throwable must not
                // escape into the completing thread that ran this listener. The signal retains the
                // failure for diagnostics; unchecked types keep their identity, a checked one is
                // wrapped.
                logHandoffError(failure, index, "the sliding-window refill");
                abandonRemaining(tasks, results, index, failure);
                settleFailure(failure);
                return false;
            }
        }

        /** Releases the claimed element's window slot and claims the next element if one is free. */
        private void onComplete() {
            freeSlots.incrementAndGet();
            drain();
        }

        /**
         * Abandons every element from the first unclaimed index on, then marks the signal settled
         * so an in-flight refill disposes of its own claimed index instead of handing it off. The
         * claim-first ordering mirrors the old claim-before-completion-check discipline: an index
         * is owned by exactly one path, and abandoning is idempotent when both overlap.
         */
        void abandonUnclaimed(Throwable reason) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            abandonRemaining(tasks, results, nextIndex.getAndSet(size), reason);
        }

        private void settleSuccess() {
            if (settled.compareAndSet(false, true)) {
                signal.set(refillCount.get());
            }
        }

        private void settleFailure(Throwable failure) {
            if (settled.compareAndSet(false, true)) {
                signal.setException(
                        failure instanceof RuntimeException || failure instanceof Error
                                ? failure
                                : new RuntimeException(failure));
            }
        }
    }

    /**
     * Completes every future that will never receive a submission so the batch always reaches a
     * terminal state. Directly cancelling the prepared future produces {@code CANCELLED}; a
     * cancelled refill signal or a failed handoff records its cause as a submission failure.
     * Without this cleanup, the batch's synchronous result would wait on elements that no worker
     * will ever settle, and the recorded cause would be lost.
     *
     * <p>The abandoned prepared futures are never submitted, so their body slots are released here
     * as skipped — exactly once, guarded by the same atomic state the cancel-before-run path uses.
     * The skip runs before the future is settled so that a caller observing the abandonment (a
     * thrown {@code valuesOrThrow}, a report) already finds the prepared body released and its slot
     * published.
     *
     * @param tasks the prepared task futures, positionally aligned with {@code result}
     * @param result the batch futures
     * @param fromIndex the first never-submitted future index (inclusive)
     * @param reason the failure reported for the abandoned futures, or {@code null} to cancel them
     *     when the batch is already being cancelled
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

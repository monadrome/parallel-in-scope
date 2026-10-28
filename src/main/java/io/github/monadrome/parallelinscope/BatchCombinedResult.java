package io.github.monadrome.parallelinscope;

import com.google.common.base.Verify;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen terminal data of one {@link Par#mapAndCombine} run: the element batch result plus the
 * terminal combine's own outcome.
 *
 * <p>The terminal combine shares the batch's deadline and cancellation lifecycle: it runs exactly
 * once, only after every element succeeded, and it is cancelled without running when any element
 * failed, was cancelled, or the deadline expired first. Its outcome is attributed like an
 * element's — {@code USER_FAILURE} for a business failure of the combine body (the original
 * throwable is preserved), {@code SUBMISSION_FAILURE} when the combine's own executor rejected it,
 * {@code FAIL_FAST}/{@code TIMEOUT}/{@code GROUP_CANCELLED} for the framework paths.
 *
 * <p>The element results keep their full fidelity in {@link #batchResult()}; the shared body
 * tracker covers the combine as well, so {@link #bodyCompletionConfirmed()} and {@link
 * #unfinishedBodies()} report on the elements and the terminal together.
 *
 * @param <E> the element result type
 * @param <C> the terminal combine result type
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @since 0.3.0
 */
public final class BatchCombinedResult<E, C> {

    private final TaskBatchResult<E> elements;
    private final ImmediateResult<C> terminal;
    private final @Nullable TaskCompletion<C> terminalObservation;

    private BatchCombinedResult(
            TaskBatchResult<E> elements, ImmediateResult<C> terminal, @Nullable TaskCompletion<C> terminalObservation) {
        this.elements = Objects.requireNonNull(elements, "elements cannot be null");
        this.terminal = Objects.requireNonNull(terminal, "terminal cannot be null");
        this.terminalObservation = terminalObservation;
    }

    static <E, C> BatchCombinedResult<E, C> of(
            TaskBatchResult<E> elements, ImmediateResult<C> terminal, @Nullable TaskCompletion<C> terminalObservation) {
        return new BatchCombinedResult<>(elements, terminal, terminalObservation);
    }

    /**
     * The frozen element batch: per-element results, observations, and outcome counts, in input
     * order. A successful element may carry a null value; the list the combine body
     * received preserves those nulls.
     *
     * @return the element batch result
     */
    public TaskBatchResult<E> batchResult() {
        return elements;
    }

    /**
     * The terminal combine's frozen outcome.
     *
     * @return the terminal result
     */
    public ImmediateResult<C> terminalResult() {
        return terminal;
    }

    /**
     * The terminal combine's final observation snapshot, or {@code null} when the bounded cleanup
     * wait elapsed before the combine's body-exit observation was published — the same honesty rule
     * as missing element observations.
     *
     * @return the terminal observation, or {@code null} when unavailable
     */
    public @Nullable TaskCompletion<C> terminal() {
        return terminalObservation;
    }

    /**
     * The terminal combine's value after passing through {@link #orThrow()}, so an element's
     * failure is reported as itself rather than as the combine's fail-fast cancellation.
     *
     * @return the terminal value, possibly {@code null} on a null combine result
     * @throws CompletionException wrapping the selected failure when it is checked
     * @throws CancellationException when the run did not succeed and no task recorded a failure
     */
    @SuppressWarnings("CheckReturnValue")
    public @Nullable C terminalValueOrThrow() {
        orThrow();
        try {
            return terminal.valueOrThrow();
        } catch (ExecutionException impossible) {
            throw new AssertionError("successful terminal result cannot fail", impossible);
        }
    }

    /**
     * Returns this result when every element and the combine succeeded; otherwise throws the
     * selected failure. Elements are checked first, exactly as {@link TaskBatchResult#orThrow()};
     * then the combine's own {@code USER_FAILURE} or {@code SUBMISSION_FAILURE} is rethrown as-is
     * when unchecked and wrapped in {@link CompletionException} when checked. A combine that ended
     * otherwise throws its {@link CancellationException}.
     *
     * @return this result
     * @since 0.4.0
     */
    @CheckReturnValue
    @SuppressWarnings("CheckReturnValue")
    public BatchCombinedResult<E, C> orThrow() {
        elements.orThrow();
        if (terminal.successful()) {
            return this;
        }
        String batchName = elements.batchName();
        if (TaskBatchResult.isRecordedFailure(terminal)) {
            throw TaskBatchResult.selectedFailure(
                    Verify.verifyNotNull(terminal.failure()), "batch '" + batchName + "' failed in its combine");
        }
        throw TaskBatchResult.asCancellation(terminal, "batch '" + batchName + "' combine ended with ");
    }

    /**
     * Whether every task body — all elements and the terminal combine — exited within the cleanup
     * budget. See {@link TaskBatchResult#bodyCompletionConfirmed()}.
     *
     * @return {@code true} if every body exited or was atomically prevented from starting
     */
    public boolean bodyCompletionConfirmed() {
        return elements.bodyCompletionConfirmed();
    }

    /**
     * The bodies still running when the cleanup budget elapsed; see {@link
     * TaskBatchResult#unfinishedBodies()}.
     *
     * @return unfinished body counts by task name
     */
    public Map<String, Integer> unfinishedBodies() {
        return elements.unfinishedBodies();
    }
}

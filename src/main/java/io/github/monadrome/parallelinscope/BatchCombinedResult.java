package io.github.monadrome.parallelinscope;

import java.util.Map;
import java.util.Objects;
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
     * The frozen element batch: per-element results, observations, and the outcome-count report,
     * in input order. A successful element may carry a null value; the list the combine body
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
     * The terminal combine's value, throwing its recorded failure.
     *
     * @return the terminal value, possibly {@code null} on a null combine result
     * @throws ExecutionException if the terminal did not succeed; the cause is the original
     *     failure, including for a cancelled terminal
     */
    public @Nullable C terminalValueOrThrow() throws ExecutionException {
        return terminal.valueOrThrow();
    }

    /**
     * Whether every task body — all elements and the terminal combine — exited within the cleanup
     * budget. See {@link TaskBatchResult#bodyCompletionConfirmed()}.
     *
     * @return {@code true} if all bodies exited
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

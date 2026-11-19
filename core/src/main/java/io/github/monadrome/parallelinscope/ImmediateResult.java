package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A terminal value or failure, independent of execution and resource lifetime.
 *
 * <p>Success may contain null. Every other outcome contains a throwable; reading it throws an
 * ExecutionException with that same throwable as its cause, including cancellation. This shallowly
 * immutable container never waits, checks interruption, or changes the execution that produced it.
 */
public final class ImmediateResult<T> {
    private final TaskTermination<T> termination;
    private final @Nullable T value;
    private final @Nullable Throwable failure;

    private ImmediateResult(TaskTermination<T> termination, @Nullable T value, @Nullable Throwable failure) {
        this.termination = checkNotNull(termination, "termination cannot be null");
        this.value = value;
        this.failure = failure;
    }

    /** Creates an already successful result, including a null value. */
    public static <T> ImmediateResult<T> succeeded(@Nullable T value) {
        return new ImmediateResult<>(TaskTermination.success(value), value, null);
    }

    /** Creates a failed result preserving the original throwable in {@link #failure()}. */
    public static <T> ImmediateResult<T> failed(FailureCause cause) {
        checkNotNull(cause, "cause cannot be null");
        return new ImmediateResult<>(TaskTermination.failed(cause), null, cause.cause());
    }

    /** Creates a cancelled result preserving the supplied throwable for transport reads. */
    public static <T> ImmediateResult<T> cancelled(CancellationReason reason, Throwable failure) {
        Throwable original = checkNotNull(failure, "failure cannot be null");
        return new ImmediateResult<>(
                TaskTermination.cancelled(checkNotNull(reason, "reason cannot be null")), null, original);
    }

    /** Returns the immutable logical termination represented by this result. */
    public TaskTermination<T> termination() {
        return termination;
    }

    /** Returns whether the execution claim was acquired. */
    public ExecutionDisposition disposition() {
        return termination.disposition();
    }

    /** Returns the closed failure cause, or null unless this result failed. */
    public @Nullable FailureCause failureCause() {
        return termination.failureCause();
    }

    /** Returns the closed cancellation reason, or null unless this result was cancelled. */
    public @Nullable CancellationReason cancellationReason() {
        return termination.cancellationReason();
    }

    /** Returns the original failure, or null on success. */
    public @Nullable Throwable failure() {
        return failure;
    }

    /**
     * Whether the termination is {@link TaskTermination.Kind#SUCCESS}, matching {@link
     * TaskExitObservation#successful()}.
     *
     * @return {@code true} exactly when this result holds a value
     */
    public boolean successful() {
        return termination.kind() == TaskTermination.Kind.SUCCESS;
    }

    /** Consumes the three canonical task termination branches. */
    public <R> R fold(
            Function<? super @Nullable T, ? extends R> onSuccess,
            Function<? super FailureCause, ? extends R> onFailure,
            BiFunction<? super CancellationReason, ? super ExecutionDisposition, ? extends R> onCancelled) {
        return termination.fold(onSuccess, onFailure, onCancelled);
    }

    /**
     * Reads the value immediately, or wraps the stored failure without changing its identity.
     *
     * @return the successful value, possibly null
     * @throws ExecutionException with the stored failure as its cause for every non-success outcome
     */
    public @Nullable T valueOrThrow() throws ExecutionException {
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        return value;
    }

    /**
     * Returns a read-only, already-completed Guava adapter for this frozen value or failure.
     * Cancellation always returns false and {@code isCancelled()} is always false. Stored failures,
     * including cancellation exceptions, become {@link ExecutionException} causes without conversion.
     * Both {@code get} methods read immediately and preserve the calling thread's interrupt flag.
     * The adapter represents result transport only; it does not wait for execution exit or runtime
     * quiescence. Listeners run through the consumer's executor, outside the completed business scope.
     */
    public ListenableFuture<@Nullable T> asFuture() {
        ListenableFuture<@Nullable T> delegate =
                failure == null ? Futures.immediateFuture(value) : Futures.immediateFailedFuture(failure);
        return new ForwardingListenableFuture.SimpleForwardingListenableFuture<@Nullable T>(delegate) {
            @Override
            public @Nullable T get() throws ExecutionException {
                return Futures.getDone(delegate());
            }

            @Override
            public @Nullable T get(long timeout, TimeUnit unit) throws ExecutionException {
                checkNotNull(unit, "unit cannot be null");
                return get();
            }
        };
    }

    /**
     * Returns a diagnostic string naming the termination branch and either the value or the
     * failure, for example {@code ImmediateResult[kind=FAILED, failure=java.lang.IllegalStateException:
     * boom]}. The format is for logs and debuggers, not for parsing. Identity stays reference
     * identity: results with equal contents are distinct objects.
     */
    @Override
    public String toString() {
        return failure == null
                ? "ImmediateResult[kind=" + termination.kind() + ", value=" + value + ']'
                : "ImmediateResult[kind=" + termination.kind() + ", failure=" + failure + ']';
    }

    /** Freezes a done task using its own pinned or recomputed termination. */
    static <T> ImmediateResult<T> fromTask(TaskFuture<T> task) {
        TaskTermination<T> termination =
                Verify.verifyNotNull(task.termination(), "task '%s' is still running", task.taskName());
        return fromTask(task, termination);
    }

    /** Freezes a done task using the enclosing scope's authoritative termination. */
    static <T> ImmediateResult<T> fromTask(TaskFuture<T> task, TaskTermination<T> termination) {
        switch (termination.kind()) {
            case SUCCESS:
                try {
                    return new ImmediateResult<>(termination, Futures.getDone(task), null);
                } catch (ExecutionException impossible) {
                    throw new AssertionError("successful task cannot have a failed future", impossible);
                }
            case FAILED:
                return new ImmediateResult<>(
                        termination,
                        null,
                        Verify.verifyNotNull(termination.failureCause()).cause());
            case CANCELLED:
                return new ImmediateResult<>(termination, null, cancellationFailure(task, termination));
            default:
                throw new AssertionError("unknown task termination kind: " + termination.kind());
        }
    }

    /**
     * The throwable stored for a cancellation-attributed task: the task's own cancellation
     * exception when the future failed with one, otherwise a {@link LeanCancellationException}
     * naming the cancellation reason and chaining the settled signal.
     */
    private static Throwable cancellationFailure(TaskFuture<?> task, TaskTermination<?> termination) {
        Throwable settled;
        try {
            Futures.getDone(task);
            throw new AssertionError("cancelled task has a successful value");
        } catch (ExecutionException failure) {
            settled = Verify.verifyNotNull(failure.getCause());
            if (settled instanceof CancellationException) {
                return settled;
            }
        } catch (CancellationException cancelled) {
            LeanCancellationException lean = leanCancellation(task.taskName(), termination);
            lean.initCause(cancelled);
            return lean;
        }
        LeanCancellationException lean = leanCancellation(task.taskName(), termination);
        lean.initCause(settled);
        return lean;
    }

    private static LeanCancellationException leanCancellation(String taskName, TaskTermination<?> termination) {
        CancellationReason reason =
                Verify.verifyNotNull(termination.cancellationReason(), "cancelled termination carries no reason");
        return new LeanCancellationException("task '" + taskName + "' ended with " + reason.kind());
    }
}

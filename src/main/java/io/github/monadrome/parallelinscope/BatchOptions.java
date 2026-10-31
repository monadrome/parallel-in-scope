package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable options of one {@link Par#map} batch: its name, its requested parallelism, and the
 * execution policy applied to every element.
 *
 * <p>A batch is a fan-out over homogeneous input: its identity is shared by all elements, and its
 * concurrency limit controls the sliding window. Timeout and enqueue rejection form the execution
 * policy; an optional {@link TaskType} declaration is diagnostic only and never changes execution.
 * {@link #cancelOn(ListenableFuture, boolean)} adds an optional caller-owned cancellation input for
 * the whole scope.
 *
 * <p>The timeout is a forced explicit choice between {@link #inheritTimeout(String)} and {@link
 * #timeout(String, Duration)}; inheriting requires an enclosing scoped task at map time. Every
 * wither returns a new instance.
 *
 * @since 0.3.0
 */
@CheckReturnValue
public final class BatchOptions {
    private final String name;
    private final int parallelism;
    private final @Nullable Duration timeout;
    private final TaskType taskType;
    private final boolean rejectEnqueue;
    private final @Nullable Duration closeGrace;
    private final @Nullable ListenableFuture<?> cancellationSignal;
    private final boolean cancellationInterrupt;

    private BatchOptions(
            String name,
            int parallelism,
            @Nullable Duration timeout,
            TaskType taskType,
            boolean rejectEnqueue,
            @Nullable Duration closeGrace,
            @Nullable ListenableFuture<?> cancellationSignal,
            boolean cancellationInterrupt) {
        this.name = Validation.requireName(name, "name");
        this.parallelism = parallelism;
        this.timeout = timeout;
        this.taskType = taskType;
        this.rejectEnqueue = rejectEnqueue;
        this.closeGrace = closeGrace;
        this.cancellationSignal = cancellationSignal;
        this.cancellationInterrupt = cancellationInterrupt;
    }

    /**
     * Returns batch options that inherit the enclosing scope's deadline.
     *
     * @param name the batch name
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static BatchOptions inheritTimeout(String name) {
        return new BatchOptions(name, Integer.MAX_VALUE, null, TaskType.UNKNOWN, false, null, null, false);
    }

    /**
     * Returns batch options with an explicit timeout for the whole batch.
     *
     * <p>Any positive duration is accepted. One whose deadline does not fit the clock range
     * resolves to the latest representable deadline: it remains a finite execution deadline and
     * still determines the default cleanup budget, never meaning "no deadline".
     *
     * @param name the batch name
     * @param timeout the positive execution timeout
     * @throws NullPointerException if {@code name} or {@code timeout} is null
     * @throws IllegalArgumentException if {@code name} is blank, or if {@code timeout} is negative or zero
     */
    public static BatchOptions timeout(String name, Duration timeout) {
        return new BatchOptions(
                name,
                Integer.MAX_VALUE,
                Validation.requirePositive(timeout, "timeout"),
                TaskType.UNKNOWN,
                false,
                null,
                null,
                false);
    }

    /**
     * Returns a copy of these options with the given requested parallelism.
     *
     * @throws IllegalArgumentException if {@code parallelism} is not positive
     */
    public BatchOptions parallelism(int parallelism) {
        return new BatchOptions(
                name,
                Validation.requirePositive(parallelism, "parallelism"),
                timeout,
                taskType,
                rejectEnqueue,
                closeGrace,
                cancellationSignal,
                cancellationInterrupt);
    }

    /**
     * Returns a copy of these options with the given diagnostic task type for every element.
     *
     * <p>The declaration describes the element bodies; nested batches and the terminal combine of
     * {@link Par#mapAndCombine} do not inherit it. It never changes admission, enqueueing,
     * rejection, or any element's outcome; see {@link TaskType}.
     *
     * @throws NullPointerException if {@code taskType} is null
     */
    public BatchOptions taskType(TaskType taskType) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                checkNotNull(taskType, "taskType cannot be null"),
                rejectEnqueue,
                closeGrace,
                cancellationSignal,
                cancellationInterrupt);
    }

    /**
     * Returns a copy of these options with the given enqueue-rejection policy.
     *
     * <p>The policy is honored only when the registered executor's queue is a {@link
     * SmartBlockingQueue}; with any other queue, enqueue rejection is never triggered and this flag
     * is inert.
     */
    public BatchOptions rejectEnqueue(boolean rejectEnqueue) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                taskType,
                rejectEnqueue,
                closeGrace,
                cancellationSignal,
                cancellationInterrupt);
    }

    /**
     * Returns a copy with the cleanup budget used by {@link Par#map} after results settle, waiting
     * for direct task bodies and their final observations before returning.
     *
     * <p>The budget starts after result convergence. {@link Duration#ZERO} checks exit without
     * waiting. When unset, the budget is derived from the remaining execution deadline. A terminal
     * cancellation result can coexist with a body still closing resources; inspect {@link
     * TaskBatchResult#bodyCompletionConfirmed()} for the frozen direct-body exit status.
     *
     * @throws NullPointerException if {@code closeGrace} is null
     * @throws IllegalArgumentException if {@code closeGrace} is negative
     */
    public BatchOptions closeGrace(Duration closeGrace) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                taskType,
                rejectEnqueue,
                Validation.requireNonNegative(closeGrace, "closeGrace"),
                cancellationSignal,
                cancellationInterrupt);
    }

    /**
     * Returns a copy that cancels the batch when {@code signal} completes successfully, fails, or
     * is cancelled. The source remains caller-owned: the library never completes or cancels it.
     *
     * <p>The explicit {@code interrupt} argument controls interruption of running bodies; it is not
     * inferred from the source's completion. Interruption is cooperative and does not guarantee
     * that arbitrary IO or user code stops. The source takes effect at execution admission, before
     * any body can start; an empty batch returns successfully without registering.
     *
     * @throws NullPointerException if {@code signal} is null
     */
    public BatchOptions cancelOn(ListenableFuture<?> signal, boolean interrupt) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                taskType,
                rejectEnqueue,
                closeGrace,
                checkNotNull(signal, "signal cannot be null"),
                interrupt);
    }

    /** The batch name; every element of the batch shares it. */
    public String name() {
        return name;
    }

    /**
     * Requested parallelism; always positive. The default {@link Integer#MAX_VALUE} places no limit
     * beyond the batch size — the effective limit is the task count. Set a lower value to bound the
     * sliding window.
     */
    public int parallelism() {
        return parallelism;
    }

    /** The explicit execution timeout; empty means the enclosing scope's deadline is inherited. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Returns the diagnostic task type declared for every element; {@link TaskType#UNKNOWN} by
     * default.
     */
    public TaskType taskType() {
        return taskType;
    }

    /**
     * Returns whether enqueue refusal is requested; it has effect only with a {@link
     * SmartBlockingQueue}.
     */
    public boolean rejectEnqueue() {
        return rejectEnqueue;
    }

    /**
     * The explicit post-convergence cleanup budget used by {@link Par#map}; empty means the budget
     * is derived from the batch's remaining deadline.
     */
    public Optional<Duration> closeGrace() {
        return Optional.ofNullable(closeGrace);
    }

    CancellationToken.@Nullable RequestCancellationLink admitCancellation(CancellationToken token) {
        if (cancellationSignal == null) {
            return null;
        }
        return token.cancelOn(cancellationSignal, cancellationInterrupt);
    }

    /** Adapts these options to the kernel carrier of this batch. */
    UnitSpec spec() {
        return new UnitSpec(name, parallelism, timeout, taskType, rejectEnqueue);
    }
}

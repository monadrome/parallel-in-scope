package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Per-task observation sink backing {@link TaskFuture#completionFuture()}.
 *
 * <p>The sink is a {@link SettableFuture} that is set exactly once with the task's final immutable
 * {@link TaskCompletion} snapshot. Publication waits out the window in which the task future is
 * already terminal but the user body's {@code finally} has not run yet: an instrumented task
 * publishes only after its future has settled <em>and</em> its {@link TaskBodyState} slot reached
 * {@code EXITED} or {@code SKIPPED}, so the recorded end time is always final. Tasks determined to
 * never run (rejected or abandoned before submission) are published directly through {@link
 * #publishSkipped} because the prepared future they were prepared with never settles.
 *
 * <p>The two barrier signals arrive on arbitrary threads (the worker, a cancelling thread, or a
 * rejecting submitter); an atomic countdown publishes on whichever signal arrives last, and the
 * happens-before edges of the countdown make the other signal's writes — the final end time, the
 * terminal future state — visible to the publishing thread.
 *
 * <p>User task failure, cancellation, and rejection all complete the observation future
 * <em>successfully</em> with the real outcome data; a publication failure is an implementation
 * defect and must never leave the sink pending. Callers receive a fixed read-only view whose
 * {@code cancel(...)} returns {@code false} and propagates nowhere.
 *
 * @param <T> the task result type
 */
final class TaskObservation<T> {

    private final SettableFuture<TaskCompletion<T>> sink = SettableFuture.create();
    private final ListenableFuture<TaskCompletion<T>> view;
    private final Supplier<TaskCompletion<T>> settledSnapshot;
    private final AtomicInteger pendingSignals;

    /** Identity for {@link #publishSkipped}; null only on the custom-snapshot shape. */
    private final @Nullable String taskName;

    private final @Nullable String unitId;
    private final int taskIndex;
    private final long submitTimeNanos;

    private TaskObservation(
            Supplier<TaskCompletion<T>> settledSnapshot,
            int pendingSignals,
            @Nullable String taskName,
            @Nullable String unitId,
            int taskIndex,
            long submitTimeNanos) {
        this.settledSnapshot = settledSnapshot;
        this.pendingSignals = new AtomicInteger(pendingSignals);
        this.taskName = taskName;
        this.unitId = unitId;
        this.taskIndex = taskIndex;
        this.submitTimeNanos = submitTimeNanos;
        this.view = readOnly(sink);
    }

    /**
     * Creates the observation of an instrumented task: publishes after {@code settle} is terminal
     * and the task's body slot reached {@code EXITED} or {@code SKIPPED}. A context without a body
     * slot (a task prepared outside any tracked submission) publishes on settle alone.
     */
    static <T> TaskObservation<T> forTask(TaskExecutionContext context, ListenableFuture<T> settle) {
        TaskObservation<T> observation = new TaskObservation<>(
                () -> settledSnapshot(context, settle),
                context.bodyState() == null ? 1 : 2,
                context.multiTaskContext().name(),
                context.multiTaskContext().unitId(),
                context.taskIndex(),
                context.submitTimeNanos());
        settle.addListener(observation::signal, directExecutor());
        TaskBodyState body = context.bodyState();
        if (body != null) {
            body.terminal().addListener(observation::signal, directExecutor());
        }
        return observation;
    }

    /**
     * Creates the observation of a task view without execution instrumentation — a derived fluent
     * view or a test-created task: publishes when {@code settle} terminates, with zero timings and
     * the task name standing in for the unit id.
     */
    static <T> TaskObservation<T> unattributed(String taskName, CancellationToken token, ListenableFuture<T> settle) {
        TaskObservation<T> observation = new TaskObservation<>(
                () -> settledSnapshot(taskName, taskName, 0, 0, 0, 0, token, settle), 1, taskName, taskName, 0, 0);
        settle.addListener(observation::signal, directExecutor());
        return observation;
    }

    /**
     * Creates an observation with a custom snapshot — the group completion summary: publishes when
     * {@code settle} terminates. This shape carries no per-task identity and cannot be published
     * through {@link #publishSkipped}.
     */
    static <T> TaskObservation<T> of(Supplier<TaskCompletion<T>> snapshot, ListenableFuture<?> settle) {
        TaskObservation<T> observation = new TaskObservation<>(snapshot, 1, null, null, 0, 0);
        settle.addListener(observation::signal, directExecutor());
        return observation;
    }

    /**
     * Publishes the never-started snapshot of a task determined to never run — rejected or
     * abandoned before submission — whose prepared future never settles, so the barrier cannot
     * fire. Start and end stay zero by construction. First writer wins: a bind that raced the
     * abandonment keeps whichever snapshot published first.
     */
    void publishSkipped(TaskOutcome outcome, @Nullable Throwable failure) {
        Verify.verify(unitId != null, "custom-snapshot observation cannot publish a skipped snapshot");
        sink.set(TaskCompletion.snapshot(
                Verify.verifyNotNull(taskName), unitId, taskIndex, submitTimeNanos, 0, 0, outcome, null, failure));
    }

    /** The read-only observation view handed to callers; {@code cancel(...)} returns {@code false}. */
    ListenableFuture<TaskCompletion<T>> view() {
        return view;
    }

    private void signal() {
        if (pendingSignals.decrementAndGet() == 0) {
            sink.set(settledSnapshot.get());
        }
    }

    /** Wraps a sink in a fixed view that never propagates cancellation to it. */
    static <V> ListenableFuture<V> readOnly(ListenableFuture<V> sink) {
        return new ForwardingListenableFuture<V>() {
            @Override
            protected ListenableFuture<V> delegate() {
                return sink;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
    }

    /**
     * Builds the final snapshot of a settled task from its execution context: identity, timings,
     * outcome, and — on success — result read straight from the terminal future. Cancellation is
     * attributed through the token chain, never guessed from {@code isCancelled()} alone; the
     * token's committed state is read only after the future is terminal.
     */
    private static <T> TaskCompletion<T> settledSnapshot(TaskExecutionContext context, ListenableFuture<T> settle) {
        MultiTaskContext unit = context.multiTaskContext();
        return settledSnapshot(
                unit.name(),
                unit.unitId(),
                context.taskIndex(),
                context.submitTimeNanos(),
                context.startTimeNanos(),
                context.endTimeNanos(),
                unit.cancellationToken(),
                settle);
    }

    private static <T> TaskCompletion<T> settledSnapshot(
            String taskName,
            String unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            CancellationToken token,
            ListenableFuture<T> settle) {
        if (settle.isCancelled()) {
            return TaskCompletion.snapshot(
                    taskName,
                    unitId,
                    taskIndex,
                    submitTimeNanos,
                    startTimeNanos,
                    endTimeNanos,
                    TokenOutcomes.forCanceled(token, TaskOutcome.MEMBER_CANCELED),
                    null,
                    null);
        }
        try {
            // The future is terminal here, so read it with Futures.getDone: unlike get(), it never
            // throws InterruptedException on a thread that happens to carry the interrupt flag.
            return TaskCompletion.snapshot(
                    taskName,
                    unitId,
                    taskIndex,
                    submitTimeNanos,
                    startTimeNanos,
                    endTimeNanos,
                    TaskOutcome.SUCCESS,
                    Futures.getDone(settle),
                    null);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            return TaskCompletion.snapshot(
                    taskName,
                    unitId,
                    taskIndex,
                    submitTimeNanos,
                    startTimeNanos,
                    endTimeNanos,
                    Task.classifyFailure(token, cause),
                    null,
                    cause);
        }
    }
}

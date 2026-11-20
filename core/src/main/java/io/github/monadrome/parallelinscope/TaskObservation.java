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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-task observation sink backing {@link TaskFuture#completionFuture()}.
 *
 * <p>The sink is a {@link SettableFuture} that is set exactly once with the task's final immutable
 * {@link TaskExitObservation} snapshot. Publication waits out the window in which the task future is
 * already terminal but the user task code's {@code finally} has not run yet: an instrumented task
 * publishes only after its future has settled <em>and</em> its {@link TaskExecutionState} slot reached
 * {@code EXITED} or {@code SKIPPED}, so the recorded end time is always final. A submission-failure
 * snapshot may be published before the task future settles, so a cancellation cascade cannot
 * replace the recorded handoff failure with a cancellation.
 *
 * <p>User task failure, cancellation, and rejection all complete the observation future
 * <em>successfully</em> with the real termination data; a publication failure is an implementation
 * defect and must never leave the sink pending. Callers receive a fixed read-only view whose {@code
 * cancel(...)} returns {@code false} and propagates nowhere.
 *
 * @param <T> the task result type
 */
final class TaskObservation<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskObservation.class);

    private final SettableFuture<TaskExitObservation<T>> sink = SettableFuture.create();
    private final ListenableFuture<TaskExitObservation<T>> view;
    private final Supplier<TaskExitObservation<T>> settledSnapshot;
    private final AtomicInteger pendingSignals;

    /** Identity for {@link #publishSkipped}; null on the shapes no unit backs. */
    private final @Nullable String taskName;

    private final TaskId.@Nullable Unit unitId;
    private final int taskIndex;
    private final long submitTimeNanos;

    private TaskObservation(
            Supplier<TaskExitObservation<T>> settledSnapshot,
            int pendingSignals,
            @Nullable String taskName,
            TaskId.@Nullable Unit unitId,
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
     * and the task's execution slot reached {@code EXITED} or {@code SKIPPED}. A context without an execution
     * slot (a task prepared outside any tracked submission) publishes on settle alone.
     */
    static <T> TaskObservation<T> forTask(TaskExecutionContext context, ListenableFuture<T> settle) {
        TaskObservation<T> observation = new TaskObservation<>(
                () -> settledSnapshot(context, settle),
                context.executionState() == null ? 1 : 2,
                context.multiTaskContext().name(),
                context.multiTaskContext().unitId(),
                context.taskIndex(),
                context.submitTimeNanos());
        settle.addListener(observation::signal, directExecutor());
        TaskExecutionState taskCode = context.executionState();
        if (taskCode != null) {
            taskCode.terminal().addListener(observation::signal, directExecutor());
        }
        return observation;
    }

    /**
     * Creates the observation of a task view without execution instrumentation — a derived fluent
     * view or a test-created task: publishes when {@code settle} terminates, with zero timings and
     * no unit identity. No unit backs such a view, so its name must not stand in for one.
     */
    static <T> TaskObservation<T> unattributed(String taskName, CancellationToken token, ListenableFuture<T> settle) {
        TaskObservation<T> observation = new TaskObservation<>(
                () -> settledSnapshot(taskName, null, 0, 0, 0, 0, token, settle), 1, taskName, null, 0, 0);
        settle.addListener(observation::signal, directExecutor());
        return observation;
    }

    /**
     * Creates an observation with a custom snapshot — the group completion summary: publishes when
     * {@code settle} terminates. This shape carries no per-task identity and cannot be published
     * through {@link #publishSkipped}.
     */
    static <T> TaskObservation<T> of(Supplier<TaskExitObservation<T>> snapshot, ListenableFuture<?> settle) {
        TaskObservation<T> observation = new TaskObservation<>(snapshot, 1, null, null, 0, 0);
        settle.addListener(observation::signal, directExecutor());
        return observation;
    }

    /**
     * Publishes the final snapshot of a task prevented from starting. Start and end times are zero.
     * First writer wins: a concurrent publication keeps whichever snapshot was published first.
     */
    void publishSkipped(TaskTermination<T> termination, @Nullable Throwable failure) {
        Verify.verify(unitId != null, "an observation with no unit cannot publish a skipped snapshot");
        publish(TaskExitObservation.snapshot(
                Verify.verifyNotNull(taskName), unitId, taskIndex, submitTimeNanos, 0, 0, termination, null, failure));
    }

    /** The read-only observation view handed to callers; {@code cancel(...)} returns {@code false}. */
    ListenableFuture<TaskExitObservation<T>> view() {
        return view;
    }

    /**
     * Counts one barrier signal and publishes after every required publication signal arrives.
     * Snapshot-construction failure completes the observation exceptionally and never escapes into
     * the task's listener fan-out.
     */
    private void signal() {
        if (pendingSignals.decrementAndGet() == 0) {
            TaskExitObservation<T> snapshot;
            try {
                snapshot = settledSnapshot.get();
            } catch (Throwable snapshotFailure) {
                failPublication(snapshotFailure);
                return;
            }
            publish(snapshot);
        }
    }

    /** Ends the observation with a snapshot failure, guarded like {@link #publish}. */
    private void failPublication(Throwable snapshotFailure) {
        try {
            sink.setException(snapshotFailure);
        } catch (Throwable listenerFailure) {
            logQuietly(listenerFailure);
        }
        logSnapshotFailureQuietly(snapshotFailure);
    }

    /** Reports a snapshot that could not be built; like {@link #logQuietly}, it cannot throw. */
    @SuppressWarnings("EmptyCatch")
    private static void logSnapshotFailureQuietly(Throwable snapshotFailure) {
        try {
            LOGGER.error("a task observation snapshot could not be built", snapshotFailure);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Publishes the finalized snapshot without letting a caller's listener escape into the path
     * that runs this.
     */
    private void publish(TaskExitObservation<T> snapshot) {
        try {
            sink.set(snapshot);
        } catch (Throwable failure) {
            logQuietly(failure);
        }
    }

    /** A failing logging backend must not be able to break the path it is only observing. */
    private static void logQuietly(Throwable failure) {
        try {
            LOGGER.error("an observation listener failed", failure);
        } catch (Throwable ignored) {
        }
    }

    /** Wraps a sink in a fixed view that never propagates cancellation to it. */
    static <V> ListenableFuture<V> readOnly(ListenableFuture<V> sink) {
        return new ReadOnlyView<>(sink);
    }

    /**
     * A fixed read-only view over a sink future: {@code cancel(...)} returns {@code false} and
     * propagates nowhere. The view owns its diagnostic identity, so rendering it names the view
     * and its own status rather than the sink it forwards to.
     */
    private static final class ReadOnlyView<V> extends ForwardingListenableFuture<V> {
        private final ListenableFuture<V> sink;

        private ReadOnlyView(ListenableFuture<V> sink) {
            this.sink = sink;
        }

        @Override
        protected ListenableFuture<V> delegate() {
            return sink;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        /**
         * Returns a diagnostic string in Guava's future envelope: {@code
         * ReadOnlyView@identityHash[status=...]}. A successful result renders as {@code
         * SimpleClassName@identityHash} and a failure as its type only, never through user
         * rendering code or messages. The format is for logs and debuggers, not for parsing.
         */
        @Override
        public String toString() {
            return "ReadOnlyView@" + Integer.toHexString(System.identityHashCode(this)) + "[status=" + statusBranch()
                    + ']';
        }

        /**
         * The status branch of the envelope, mirroring Guava's {@code AbstractFuture}: {@code
         * CANCELLED}, {@code PENDING}, {@code SUCCESS} with the value's identity, or {@code
         * FAILURE} with the cause's type. The branch reads nothing after the done check, so the
         * pending text cannot contradict a later completion; the outcome read degrades to {@code
         * UNKNOWN} rather than throwing out of a diagnostic.
         */
        private String statusBranch() {
            if (isCancelled()) {
                return "CANCELLED";
            }
            if (!isDone()) {
                return "PENDING";
            }
            return ResultObjects.doneBranch(sink, this);
        }
    }

    /**
     * Builds the final snapshot of a settled task from its execution context: identity, timings,
     * termination, and — on success — result read straight from the terminal future. Cancellation
     * is attributed through the token chain, never guessed from {@code isCancelled()} alone; the
     * token's committed state is read only after the future is terminal. A batch or group result
     * replaces this termination with the frozen attribution through {@code
     * TaskExitObservation.withResult}; only package-private observation futures (a task's own and
     * the batch aggregate) publish it unchanged.
     */
    private static <T> TaskExitObservation<T> settledSnapshot(
            TaskExecutionContext context, ListenableFuture<T> settle) {
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

    private static <T> TaskExitObservation<T> settledSnapshot(
            String taskName,
            TaskId.@Nullable Unit unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            CancellationToken token,
            ListenableFuture<T> settle) {
        TaskTermination<T> termination = Task.settleTermination(taskName, token, settle);
        if (settle.isCancelled()) {
            return TaskExitObservation.snapshot(
                    taskName,
                    unitId,
                    taskIndex,
                    submitTimeNanos,
                    startTimeNanos,
                    endTimeNanos,
                    termination,
                    null,
                    null);
        }
        try {
            return TaskExitObservation.snapshot(
                    taskName,
                    unitId,
                    taskIndex,
                    submitTimeNanos,
                    startTimeNanos,
                    endTimeNanos,
                    termination,
                    Futures.getDone(settle),
                    null);
        } catch (ExecutionException failure) {
            return TaskExitObservation.snapshot(
                    taskName,
                    unitId,
                    taskIndex,
                    submitTimeNanos,
                    startTimeNanos,
                    endTimeNanos,
                    termination,
                    null,
                    failure.getCause());
        }
    }
}

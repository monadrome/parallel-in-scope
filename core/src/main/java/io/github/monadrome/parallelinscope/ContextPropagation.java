package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.alibaba.ttl.TransmittableThreadLocal.Transmitter;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The library's context layer: an immutable, ordered list of {@link ContextPropagator}s applied as
 * one, with the Alibaba TransmittableThreadLocal (TTL) context always first.
 *
 * <p>This is the only place that captures, installs, and restores application context. Capture
 * walks the list forward. Install walks it forward and unwinds on failure. Close walks it backward
 * and attempts every scope. Framework thread state, such as {@link TaskExecutionContext}, {@link
 * SubmissionScope}, and the interrupt status, is deliberately not part of this layer: it must not
 * follow a task across threads.
 */
final class ContextPropagation {

    private static final ContextPropagation TTL_ONLY = new ContextPropagation(ImmutableList.of(Ttl.INSTANCE));

    private final ImmutableList<ContextPropagator> participants;

    private ContextPropagation(ImmutableList<ContextPropagator> participants) {
        this.participants = participants;
    }

    /** The layer that carries TTL values and nothing else. */
    static ContextPropagation ttlOnly() {
        return TTL_ONLY;
    }

    /** The layer that carries TTL values, then {@code registered} in the given order. */
    static ContextPropagation ttlThen(List<ContextPropagator> registered) {
        if (registered.isEmpty()) {
            return TTL_ONLY;
        }
        return new ContextPropagation(ImmutableList.<ContextPropagator>builder()
                .add(Ttl.INSTANCE)
                .addAll(registered)
                .build());
    }

    /**
     * Captures every participant on the calling thread, in order.
     *
     * @return one snapshot standing for all participants
     * @throws RuntimeException the first participant failure; later participants are not captured
     */
    ContextPropagator.Snapshot capture() {
        ContextPropagator.Snapshot[] snapshots = new ContextPropagator.Snapshot[participants.size()];
        for (int index = 0; index < snapshots.length; index++) {
            ContextPropagator participant = participants.get(index);
            snapshots[index] = requireStage(participant.capture(), participant, "capture() returned a null snapshot");
        }
        return new CompositeSnapshot(snapshots);
    }

    /**
     * Captures on the calling thread and returns task code that installs that capture around {@code
     * taskCode}, exactly once, on whichever thread calls it.
     */
    <V> Callable<V> capturing(Callable<V> taskCode) {
        return new CapturedCallable<>(capture(), taskCode);
    }

    /**
     * Runs a command synchronously with an already captured snapshot, restoring the calling thread
     * afterwards, including when the command or a scope close fails. A command failure remains primary
     * and close failures are suppressed on it; a close failure after a successful command is rethrown.
     * Unlike a prepared task, a command executor may install the same snapshot for many commands.
     */
    static void run(ContextPropagator.Snapshot snapshot, Runnable command) {
        try {
            callWithSnapshot(snapshot, () -> {
                command.run();
                return null;
            });
        } catch (Throwable failure) {
            ContextPropagation.<RuntimeException>rethrow(failure);
        }
    }

    /** Installs one snapshot around task code and applies the common failure precedence. */
    private static <V> V callWithSnapshot(ContextPropagator.Snapshot snapshot, Callable<V> taskCode) throws Exception {
        ContextPropagator.Scope scope = snapshot.install();
        V value;
        try {
            value = taskCode.call();
        } catch (Throwable taskFailure) {
            closeAfterTask(scope, taskFailure);
            throw taskFailure;
        }
        scope.close();
        return value;
    }

    /** Keeps every participant close failure directly suppressed on the task failure. */
    private static void closeAfterTask(ContextPropagator.Scope scope, Throwable taskFailure) {
        if (scope instanceof CompositeScope) {
            CompositeScope composite = (CompositeScope) scope;
            closeInReverse(composite.scopes, composite.scopes.length, taskFailure);
        } else {
            closeInReverse(new ContextPropagator.Scope[] {scope}, 1, taskFailure);
        }
    }

    private static <T> T requireStage(@Nullable T stage, Object owner, String problem) {
        return checkNotNull(
                stage, "context propagator stage %s: %s", owner.getClass().getName(), problem);
    }

    /**
     * Closes {@code scopes[count - 1]} down to {@code scopes[0]}, attempting each one.
     *
     * @param primary the failure already in flight, or null
     * @return {@code primary} with every close failure suppressed on it, or the first close failure
     *     with the later ones suppressed on it when {@code primary} is null, or null when nothing
     *     failed
     */
    @SuppressWarnings("ReferenceEquality")
    private static @Nullable Throwable closeInReverse(
            ContextPropagator.Scope[] scopes, int count, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (int index = count - 1; index >= 0; index--) {
            try {
                scopes[index].close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    /* A scope may rethrow the failure already in flight; a throwable cannot suppress itself. */
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }

    /** Rethrows {@code failure} as itself; a stage can smuggle a checked exception through erasure. */
    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void rethrow(Throwable failure) throws X {
        throw (X) failure;
    }

    /** The built-in participant: TTL's own capture, replay, and restore. */
    private enum Ttl implements ContextPropagator {
        INSTANCE;

        @Override
        public Snapshot capture() {
            Object captured = Transmitter.capture();
            return () -> {
                Object backup = Transmitter.replay(captured);
                return () -> Transmitter.restore(backup);
            };
        }
    }

    private static final class CompositeSnapshot implements ContextPropagator.Snapshot {
        private final ContextPropagator.Snapshot[] snapshots;

        private CompositeSnapshot(ContextPropagator.Snapshot[] snapshots) {
            this.snapshots = snapshots;
        }

        @Override
        public ContextPropagator.Scope install() {
            ContextPropagator.Scope[] opened = new ContextPropagator.Scope[snapshots.length];
            int count = 0;
            try {
                for (; count < snapshots.length; count++) {
                    ContextPropagator.Snapshot snapshot = snapshots[count];
                    opened[count] = requireStage(snapshot.install(), snapshot, "install() returned a null scope");
                }
            } catch (Throwable installFailure) {
                closeInReverse(opened, count, installFailure);
                throw installFailure;
            }
            return new CompositeScope(opened);
        }
    }

    private static final class CompositeScope implements ContextPropagator.Scope {
        private final ContextPropagator.Scope[] scopes;

        private CompositeScope(ContextPropagator.Scope[] scopes) {
            this.scopes = scopes;
        }

        @Override
        public void close() {
            Throwable failure = closeInReverse(scopes, scopes.length, null);
            if (failure != null) {
                ContextPropagation.<RuntimeException>rethrow(failure);
            }
        }
    }

    /**
     * Task code with its captured context. The snapshot is taken out on the first call, so a second
     * call cannot install it again and the captured values are not retained through the run.
     */
    static final class CapturedCallable<V> implements Callable<V> {
        private final AtomicReference<ContextPropagator.@Nullable Snapshot> captured;
        private final Callable<V> taskCode;

        private CapturedCallable(ContextPropagator.Snapshot captured, Callable<V> taskCode) {
            this.captured = new AtomicReference<>(captured);
            this.taskCode = taskCode;
        }

        @Override
        public V call() throws Exception {
            ContextPropagator.Snapshot snapshot = captured.getAndSet(null);
            checkState(snapshot != null, "captured context was already installed");
            return callWithSnapshot(snapshot, taskCode);
        }

        /** Returns the scoped task code for package-private reference-release probes. */
        Callable<V> unwrap() {
            return taskCode;
        }

        @Override
        public String toString() {
            return "CapturedCallable{taskCode=" + taskCode + '}';
        }
    }
}

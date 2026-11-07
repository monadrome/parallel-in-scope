package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Kernel state of one adopted group member: a caller-supplied future the group owns from {@code
 * runAll()} on, mirrored into the member future the group binds, classifies, and freezes.
 *
 * <p>Lifecycle: the declaration creates the instance; preparation attaches the member's execution
 * context, whose execution slot stands for the source's outstanding work; then exactly one of {@link
 * #connect()} (the group admitted the member) or {@link #abandon()} (admission failed) claims the
 * source. A connected source settles the mirror through this class's own listener, which writes the
 * end time first; cancelling the mirror cancels the source with the mirror's own interrupt flag
 * ({@code cancel(true)} for every framework cancellation).
 *
 * <p>A listener of the source that throws while the group cancels it is logged and never
 * interrupts the group's cancellation cascade or cleanup. The execution slot is released once the
 * source has settled. The group does not close the source's value; resource lifetime remains with
 * the source's own pipeline or the value's recipient. See {@code design/task-group-adopted-futures.md}.
 */
final class AdoptedSource {
    private static final Logger LOGGER = Logger.getLogger(AdoptedSource.class.getName());

    private final String name;
    private final ListenableFuture<?> source;
    private final Mirror mirror = new Mirror();

    /** Set by the one winner between {@link #connect()} and {@link #abandon()}. */
    private final AtomicBoolean claimed = new AtomicBoolean();

    /** Guards {@link #sourceSettled()}: its listener and a cancellation may both observe the settle. */
    private final AtomicBoolean settled = new AtomicBoolean();

    private volatile boolean connected;

    private volatile @Nullable TaskExecutionContext context;

    AdoptedSource(String name, ListenableFuture<?> source) {
        this.name = name;
        this.source = source;
    }

    /** The member future the group binds and classifies; settled only through the source. */
    ListenableFuture<Object> mirror() {
        return mirror;
    }

    /** Attaches the prepared member context; called once on the admitting thread before binding. */
    void attach(TaskExecutionContext prepared) {
        verify(prepared.executionState() != null, "an adopted member must own an execution slot");
        this.context = prepared;
    }

    /**
     * Connects the source to the mirror after the group and member tokens are bound. A mirror the
     * group cancelled earlier has already cancelled the source; the connection still registers the
     * settle listener, so the execution slot is released when the source finally settles.
     */
    void connect() {
        verify(claimed.compareAndSet(false, true), "adopted source '%s' was already claimed", name);
        TaskExecutionContext prepared = verifyNotNull(context, "adopted member '%s' was not prepared", name);
        verify(
                verifyNotNull(prepared.executionState()).claimRunning(),
                "adopted member '%s' already left PENDING",
                name);
        prepared.markStarted(System.nanoTime());
        connected = true;
        source.addListener(this::sourceSettled, directExecutor());
    }

    /**
     * Releases a source the group never admitted: cancels it and its mirror, and releases the
     * execution slot when the source settles. A no-op once connected.
     */
    void abandon() {
        if (!claimed.compareAndSet(false, true)) {
            return;
        }
        TaskExecutionContext prepared = context;
        TaskExecutionState taskCode = prepared == null ? null : prepared.executionState();
        if (taskCode != null) {
            taskCode.claimRunning();
        }
        cancelSource(true);
        mirror.cancel(false);
        source.addListener(
                () -> {
                    if (taskCode != null) {
                        taskCode.exited();
                    }
                },
                directExecutor());
    }

    private void sourceSettled() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        TaskExecutionContext prepared = verifyNotNull(context);
        prepared.markEnded(System.nanoTime());
        try {
            verifyNotNull(prepared.executionState()).exited();
        } finally {
            /* After the end time: a convergence this settle triggers snapshots the final end time. */
            mirror.settleFrom(source);
        }
    }

    /**
     * Cancels the source on the group's behalf; a failing listener of the source is only logged. A
     * listener that throws an {@link Error} stops Guava from running the source's later listeners,
     * which can include this member's settle listener, so a connected source that is done by now is
     * settled here instead.
     */
    private void cancelSource(boolean mayInterruptIfRunning) {
        try {
            source.cancel(mayInterruptIfRunning);
        } catch (Throwable listenerFailure) {
            try {
                LOGGER.log(
                        Level.WARNING,
                        "a listener of adopted member '" + name + "' failed while the group cancelled it",
                        listenerFailure);
            } catch (Throwable ignored) {
                // A user-installed log handler must not stop the group's cancellation cascade.
            }
        }
        if (connected && source.isDone()) {
            sourceSettled();
        }
    }

    /**
     * The member future the group sees: settled only from the source, and propagating its own
     * cancellation to the source with the same {@code wasInterrupted()} flag; a framework cascade
     * always cancels with {@code cancel(true)}, while the mirror's own {@code cancel(false)} only
     * completes it as cancelled after the source already settled or was abandoned.
     */
    private final class Mirror extends AbstractFuture<Object> {
        void settleFrom(ListenableFuture<?> settled) {
            if (settled.isCancelled()) {
                cancel(false);
                return;
            }
            try {
                set(Futures.getDone(settled));
            } catch (ExecutionException failure) {
                setException(MoreObjects.firstNonNull(failure.getCause(), failure));
            } catch (CancellationException cancelled) {
                cancel(false);
            }
        }

        @Override
        protected void afterDone() {
            if (isCancelled()) {
                cancelSource(wasInterrupted());
            }
        }
    }
}

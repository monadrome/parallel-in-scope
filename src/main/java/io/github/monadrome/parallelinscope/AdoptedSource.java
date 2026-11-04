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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Kernel state of one adopted group member: a caller-supplied future the group owns from {@code
 * runAll()} on, mirrored into the member future the group binds, classifies, and freezes.
 *
 * <p>Lifecycle: the declaration creates the instance; preparation attaches the member's execution
 * context, whose body slot stands for the source's outstanding work; then exactly one of {@link
 * #connect()} (the group admitted the member) or {@link #abandon()} (admission failed) claims the
 * source. A connected source settles the mirror through this class's own listener, which writes the
 * end time first; cancelling the mirror cancels the source with the mirror's own interrupt flag
 * ({@code cancel(true)} for every framework cancellation).
 *
 * <p>A listener of the source that throws while the group cancels it is logged and never
 * interrupts the group's cancellation cascade or cleanup. The body slot is released once the
 * source has settled and — for a member that declares a closer — the group has decided who owns
 * the value and, when nobody does, the closer has returned.
 *
 * <p>The closer runs at most once, only for a non-null successful value, on the runtime's {@code
 * releases} executor rather than on the settling thread, which can be the {@code runAll()} caller
 * or a runtime-internal deadline thread. Only a release the executor refuses (its runtime has shut
 * down) runs on the settling thread. A closer failure never escapes. See {@code
 * design/task-group-adopted-futures.md}.
 */
final class AdoptedSource {
    private static final Logger LOGGER = Logger.getLogger(AdoptedSource.class.getName());

    private final String name;
    private final ListenableFuture<?> source;
    private final @Nullable ResourceCloser<Object> closer;
    private final Executor releases;
    private final Mirror mirror = new Mirror();

    /** Set by the one winner between {@link #connect()} and {@link #abandon()}. */
    private final AtomicBoolean claimed = new AtomicBoolean();

    /** Guards {@link #sourceSettled()}: its listener and a cancellation may both observe the settle. */
    private final AtomicBoolean settled = new AtomicBoolean();

    private volatile boolean connected;

    /*
     * Countdown over the release signals: the source settled, and — only when a closer is declared
     * — the group decided ownership. Whichever signal arrives last closes a discarded value and
     * releases the body slot; the decrement orders the other signal's writes (the decision, the end
     * time) before that step.
     */
    private final AtomicInteger pendingSignals;

    private volatile boolean discarded;
    private volatile @Nullable TaskExecutionContext context;

    @SuppressWarnings("unchecked")
    AdoptedSource(String name, ListenableFuture<?> source, @Nullable ResourceCloser<?> closer, Executor releases) {
        this.name = name;
        this.source = source;
        this.closer = (ResourceCloser<Object>) closer;
        this.releases = releases;
        this.pendingSignals = new AtomicInteger(closer == null ? 1 : 2);
    }

    /** The member future the group binds and classifies; settled only through the source. */
    ListenableFuture<Object> mirror() {
        return mirror;
    }

    /** Attaches the prepared member context; called once on the admitting thread before binding. */
    void attach(TaskExecutionContext prepared) {
        verify(prepared.bodyState() != null, "an adopted member must own a body slot");
        this.context = prepared;
    }

    /**
     * Connects the source to the mirror after the group and member tokens are bound. A mirror the
     * group cancelled earlier has already cancelled the source; the connection still registers the
     * settle listener, so the body slot is released when the source finally settles.
     */
    void connect() {
        verify(claimed.compareAndSet(false, true), "adopted source '%s' was already claimed", name);
        TaskExecutionContext prepared = verifyNotNull(context, "adopted member '%s' was not prepared", name);
        verify(verifyNotNull(prepared.bodyState()).claimRunning(), "adopted member '%s' already left PENDING", name);
        prepared.markStarted(System.nanoTime());
        connected = true;
        source.addListener(this::sourceSettled, directExecutor());
    }

    /**
     * Records the group's ownership decision; a no-op for a member without a closer. {@code
     * discard} is true when no owner received the group's adopted values.
     */
    void decide(boolean discard) {
        if (closer == null) {
            return;
        }
        this.discarded = discard;
        signal();
    }

    /**
     * Releases a source the group never admitted: cancels it and its mirror, closes the value it
     * produced anyway, and releases the body slot preparation may have registered. A no-op once
     * connected.
     */
    void abandon() {
        if (!claimed.compareAndSet(false, true)) {
            return;
        }
        TaskExecutionContext prepared = context;
        TaskBodyState body = prepared == null ? null : prepared.bodyState();
        if (body != null) {
            body.claimRunning();
        }
        cancelSource(true);
        mirror.cancel(false);
        source.addListener(() -> closeThenRelease(body), directExecutor());
    }

    private void sourceSettled() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        verifyNotNull(context).markEnded(System.nanoTime());
        try {
            signal();
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

    private void signal() {
        if (pendingSignals.decrementAndGet() != 0) {
            return;
        }
        TaskBodyState body = verifyNotNull(verifyNotNull(context).bodyState());
        if (discarded) {
            closeThenRelease(body);
        } else {
            body.exited();
        }
    }

    /**
     * Hands the settled source's non-null successful value to the closer on the releases executor,
     * then releases {@code body}; releases it directly when there is nothing to close. Never throws.
     */
    private void closeThenRelease(@Nullable TaskBodyState body) {
        ResourceCloser<Object> release = closer;
        Object value = release == null ? null : producedValue();
        if (release == null || value == null) {
            if (body != null) {
                body.exited();
            }
            return;
        }
        AtomicBoolean ran = new AtomicBoolean();
        Runnable closing = () -> {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            try {
                close(release, value);
            } finally {
                if (body != null) {
                    body.exited();
                }
            }
        };
        try {
            releases.execute(closing);
        } catch (Throwable notStarted) {
            /* The guard keeps an executor that started the task and then threw from running it twice. */
            closing.run();
        }
    }

    private @Nullable Object producedValue() {
        if (source.isCancelled()) {
            return null;
        }
        try {
            return Futures.getDone(source);
        } catch (ExecutionException | CancellationException noValue) {
            return null;
        }
    }

    private void close(ResourceCloser<Object> release, Object value) {
        try {
            release.close(value);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logCloseFailure(interrupted);
        } catch (Throwable failure) {
            logCloseFailure(failure);
        }
    }

    private void logCloseFailure(Throwable failure) {
        try {
            LOGGER.log(Level.WARNING, "closing the discarded value of adopted member '" + name + "' failed", failure);
        } catch (Throwable ignored) {
            // A user-installed log handler must not keep the body slot from being released.
        }
    }
}

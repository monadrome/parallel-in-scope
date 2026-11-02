package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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
 * source. A connected source settles the mirror; cancelling the mirror cancels the source with the
 * same interrupt intent. The body slot is released once the source has settled and — for a member
 * that declares a closer — the group has decided who owns the value and, when nobody does, the
 * closer has returned.
 *
 * <p>The closer runs at most once, only for a non-null successful value, and never escapes a
 * failure into the thread that runs it. See {@code design/task-group-adopted-futures.md}.
 */
final class AdoptedSource {
    private static final Logger LOGGER = Logger.getLogger(AdoptedSource.class.getName());

    private final String name;
    private final ListenableFuture<?> source;
    private final @Nullable ResourceCloser<Object> closer;
    private final SettableFuture<Object> mirror = SettableFuture.create();

    /** Set by the one winner between {@link #connect()} and {@link #abandon()}. */
    private final AtomicBoolean claimed = new AtomicBoolean();

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
    AdoptedSource(String name, ListenableFuture<?> source, @Nullable ResourceCloser<?> closer) {
        this.name = name;
        this.source = source;
        this.closer = (ResourceCloser<Object>) closer;
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
     * Connects the source to the mirror after the group and member tokens are bound. A mirror
     * cancelled before this call cancels the source here, so no admitted source escapes ownership.
     */
    void connect() {
        verify(claimed.compareAndSet(false, true), "adopted source '%s' was already claimed", name);
        TaskExecutionContext prepared = verifyNotNull(context, "adopted member '%s' was not prepared", name);
        verify(verifyNotNull(prepared.bodyState()).claimRunning(), "adopted member '%s' already left PENDING", name);
        prepared.markStarted(System.nanoTime());
        /* Registered before setFuture's own listener, so the end time precedes the mirror's settle. */
        source.addListener(this::sourceSettled, directExecutor());
        mirror.setFuture(source);
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
     * Releases a source the group never admitted: cancels it, closes the value it produced anyway,
     * and releases the body slot preparation may have registered. A no-op once connected.
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
        source.cancel(true);
        source.addListener(
                () -> {
                    try {
                        closeIfProduced();
                    } finally {
                        if (body != null) {
                            body.exited();
                        }
                    }
                },
                directExecutor());
    }

    private void sourceSettled() {
        verifyNotNull(context).markEnded(System.nanoTime());
        signal();
    }

    private void signal() {
        if (pendingSignals.decrementAndGet() != 0) {
            return;
        }
        try {
            if (discarded) {
                closeIfProduced();
            }
        } finally {
            verifyNotNull(verifyNotNull(context).bodyState()).exited();
        }
    }

    /** Passes the source's non-null successful value to the closer; never throws. */
    private void closeIfProduced() {
        ResourceCloser<Object> release = closer;
        if (release == null || source.isCancelled()) {
            return;
        }
        Object value;
        try {
            value = Futures.getDone(source);
        } catch (ExecutionException | CancellationException noValue) {
            return;
        }
        if (value == null) {
            return;
        }
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

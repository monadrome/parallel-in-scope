package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.github.monadrome.parallelinscope.CancellationToken.State.CANCELLED;
import static io.github.monadrome.parallelinscope.CancellationToken.State.FAIL_FAST;
import static io.github.monadrome.parallelinscope.CancellationToken.State.PROPAGATED_CANCELLED;
import static io.github.monadrome.parallelinscope.CancellationToken.State.RUNNING;
import static io.github.monadrome.parallelinscope.CancellationToken.State.SUCCESS;
import static io.github.monadrome.parallelinscope.CancellationToken.State.TIMEOUT;

import com.google.common.base.Ticker;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Cooperative cancellation token carrying a deadline for parallel work.
 *
 * <p>A token may be linked to a parent so that cancellation propagates to child task groups, and a
 * child never outlives its parent: the effective deadline is the minimum of the requested deadline
 * and the parent's. Before task submission, the internal bind operation connects the token to
 * prepared futures and enforces that
 * deadline together with fail-fast cancellation.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class CancellationToken {

    private static final Logger LOGGER = Logger.getLogger(CancellationToken.class.getName());

    private final SettableFuture<Object> futureToken = SettableFuture.create();
    private final AtomicReference<Decision> decision = new AtomicReference<>(Decision.running());
    private final @Nullable CancellationToken parent;
    private final long deadlineNanos;
    private final List<Consumer<State>> stateListeners = new CopyOnWriteArrayList<>();
    private final @Nullable ParentLink parentLink;

    /**
     * The monotonic clock of this token's deadline domain. A child always inherits its parent's
     * clock, so one token tree shares one clock domain; only a root token takes an explicit clock,
     * and public construction always lands on the system clock. Internal tests substitute a manual
     * clock for a root token to make deadline detection deterministic; deadlineNanos is always read
     * against this clock, never mixed with another domain.
     */
    private final Ticker ticker;

    /**
     * The deadline scheduler paired with this token's clock domain, inherited by every child like
     * the clock itself. Only a root token receives it (from the owning {@code ParRuntime}'s test
     * seam); it is {@code null} for tokens created through the public constructors, whose {@link
     * #bind} callers then supply the scheduler explicitly. The pairing is what lets a nested unit
     * fire its deadline on the ancestor's controlled time instead of scheduling a virtual delay on
     * a real scheduler.
     */
    private final @Nullable ScheduledExecutorService timeoutScheduler;

    /**
     * Creates a token linked to a parent, or a root token if {@code parent} is {@code null}.
     *
     * <p>This constructor is the single parent-propagation mechanism: when the parent's work is
     * cancelled, timed out, or fail-fast-cancelled, this token transitions to {@code
     * PROPAGATED_CANCELLED} and cancels its linked future with the parent's interrupt intent — an
     * explicit {@code cancel(false)} propagates without interrupting, while deadline and fail-fast
     * transitions always interrupt. No additional wiring in {@link #bind} or the caller is needed.
     *
     * @param parent the parent token, or {@code null} for a root token
     */
    public CancellationToken(@Nullable CancellationToken parent) {
        this(parent, Long.MAX_VALUE);
    }

    /**
     * Creates a token with a deadline on the monotonic clock, linked to a parent or unlinked.
     *
     * <p>The effective deadline is the minimum of {@code deadlineNanos} and the parent's deadline,
     * so a child scope can only request an earlier deadline, never a later one.
     *
     * @param parent the parent token, or {@code null} for a root token
     * @param deadlineNanos the requested deadline in {@link System#nanoTime()} units
     */
    public CancellationToken(@Nullable CancellationToken parent, long deadlineNanos) {
        this(parent, deadlineNanos, Ticker.systemTicker());
    }

    /**
     * Package-private root-clock seam: the given clock applies only when {@code parent} is null; a
     * child token always inherits its parent's clock, keeping one clock domain per token tree.
     */
    CancellationToken(@Nullable CancellationToken parent, long deadlineNanos, Ticker ticker) {
        this(parent, deadlineNanos, ticker, null);
    }

    /**
     * Root-clock-and-scheduler seam: the clock and its paired deadline scheduler apply only when
     * {@code parent} is null; a child token inherits both from its parent, so the whole tree shares
     * one clock domain and the scheduler that fires on it.
     */
    CancellationToken(
            @Nullable CancellationToken parent,
            long deadlineNanos,
            Ticker ticker,
            @Nullable ScheduledExecutorService timeoutScheduler) {
        this.parent = parent;
        this.ticker = parent == null ? Objects.requireNonNull(ticker, "ticker cannot be null") : parent.ticker;
        this.timeoutScheduler = parent == null ? timeoutScheduler : parent.timeoutScheduler;
        this.deadlineNanos = parent == null ? deadlineNanos : Math.min(deadlineNanos, parent.deadlineNanos());
        if (parent != null) {
            // The listener reaches this token through a severable holder: once this token commits a
            // terminal state, transitionTo clears the holder, so a still-running parent cannot keep
            // this token — and the results carried by its futureToken — alive through the parent's
            // pending listener list until the parent itself finishes.
            ParentLink link = new ParentLink(parent, this);
            // Assign before registering: the parent's future may already be terminal, in which case
            // the listener runs inline and this token commits PROPAGATED_CANCELLED during
            // addListener — before this constructor would otherwise have installed the holder.
            // With the holder already visible, that synchronous terminal transition still severs
            // it, so no unsevered link survives the construction window.
            this.parentLink = link;
            parent.futureToken.addListener(link::parentFinished, directExecutor());
        } else {
            this.parentLink = null;
        }
    }

    /** Creates an unlinked root token with no deadline. */
    public CancellationToken() {
        this(null);
    }

    /**
     * Creates an unlinked root token with no deadline.
     *
     * @return a new cancellation token
     */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    /**
     * Returns the effective deadline in {@link System#nanoTime()} units; {@link Long#MAX_VALUE}
     * means no deadline.
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Returns the remaining duration until this token's deadline.
     *
     * <p>The result is never negative: an elapsed deadline reports {@link Duration#ZERO}. A token
     * without a deadline reports exactly {@link Duration#ofNanos Duration.ofNanos(Long.MAX_VALUE)},
     * the same {@link Long#MAX_VALUE} sentinel {@link #deadlineNanos()} uses — the duration is not
     * eroded by subtracting the current clock reading.
     *
     * @return the remaining duration, saturated at zero and at the no-deadline sentinel
     */
    public Duration remaining() {
        return Duration.ofNanos(Deadlines.remaining(deadlineNanos, ticker.read()));
    }

    /** The monotonic clock of this token's deadline domain; inherited by every child token. */
    Ticker ticker() {
        return ticker;
    }

    /** The deadline scheduler paired with this token's clock domain, or null when none was injected. */
    @Nullable
    ScheduledExecutorService timeoutScheduler() {
        return timeoutScheduler;
    }

    /** Whether this token's deadline has elapsed on its own clock; an unbounded token never expires. */
    boolean deadlineExpired() {
        return deadlineNanos <= ticker.read();
    }

    /**
     * Connects this token to submitted work and arms its deadline.
     *
     * <p>After binding, the token classifies itself: {@code SUCCESS} when every future succeeds,
     * {@code TIMEOUT} when its deadline expires first, and {@code FAIL_FAST}
     * when any future fails. Every cancelling transition cancels the futures and the submission
     * canceller; cancelling an already-successful future is a no-op, so a late cancel never
     * destroys a recorded result. An already-expired deadline commits {@code TIMEOUT}
     * synchronously and cancels the futures before this method returns, so no submitted task can
     * still enter user code on an expired deadline.
     *
     * @param <T> the task result type
     * @param futures the submitted task futures
     * @param submitCanceller the submission future to cancel with the tasks
     * @param timer fallback scheduler used to detect the deadline when this token's tree carries
     *     no domain scheduler of its own
     * @return the aggregate that completes when every submitted future and the submission
     *     canceller are done, so callers tracking completion reuse it instead of building a
     *     second aggregate over the same futures
     */
    <T> ListenableFuture<?> bind(
            List<? extends ListenableFuture<T>> futures,
            ListenableFuture<?> submitCanceller,
            ScheduledExecutorService timer) {
        // The token tree's own domain scheduler wins: a nested unit inherits the ancestor's clock,
        // and only the scheduler paired with that clock fires under its time. The parameter is the
        // fallback for tokens created without a runtime seam — and for a retired domain scheduler:
        // the ancestor runtime may already be closed while one of its cancelled bodies is still
        // running and nests new work into another runtime. The delay stays meaningful there: an
        // ancestor on the real clock retires with real-domain deadlines, and a retired manual clock
        // can no longer advance, so falling back fires the deadline no later than the domain would.
        ScheduledExecutorService deadlineTimer =
                timeoutScheduler != null ? timeoutScheduler : Objects.requireNonNull(timer);
        // A pending successfulAsList is the one cancellable handle that reaches both the task
        // futures and the submission canceller: it stays pending until every input is done, so
        // cancelling it still propagates after one task already failed or was cancelled.
        ListenableFuture<?> allFutures = Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller);
        if (Deadlines.remaining(deadlineNanos, ticker.read()) == 0L) {
            // The deadline already expired: behave as if the timeout callback had already run.
            // Scheduling a zero-delay timeout would leave the token RUNNING until the timer
            // thread gets to it, and submitted tasks could enter user code in that window. The
            // futures are cancelled even when the state was already committed (a pre-bind cancel):
            // that is exactly the cancellation the committed state implies.
            transitionTo(TIMEOUT);
            cancelBoundWork(allFutures);
            futureToken.setException(new TimeoutException());
            return allFutures;
        }
        // The business outcome and the framework deadline are distinct event sources and must not
        // be conflated by exception type: a member body may legitimately throw TimeoutException,
        // and classifying on instanceof would record that business failure as a framework TIMEOUT.
        // The aggregate callback below is the only fail-fast source; the scheduled timer task is
        // the only deadline source.
        ListenableFuture<?> businessOutcome = Futures.allAsList(futures);
        // A token that is already terminal has no deadline left to enforce: no transition can ever
        // succeed again, and the cancellation it implies reaches the futures through the
        // futureToken/setFuture bridge below. Skipping the arming also keeps a terminal token's bind
        // from touching a scheduler whose owning runtime may already be closed.
        if (deadlineNanos != Long.MAX_VALUE && state() == RUNNING) {
            // Saturate the subtraction: the expired-deadline branch above guarantees a positive
            // result, the enclosing if excludes the sentinel, and Deadlines.remaining normalizes
            // the wrapped-negative case.
            Runnable timeoutAction = () -> {
                // The cancel is unconditional: a committed state implies the cancellation
                // even when this task loses the first-wins race, matching the
                // expired-deadline branch.
                transitionTo(TIMEOUT);
                cancelBoundWork(allFutures);
            };
            long deadlineDelay = Deadlines.remaining(deadlineNanos, ticker.read());
            ScheduledFuture<?> timeoutHandle = scheduleDeadline(deadlineTimer, timer, timeoutAction, deadlineDelay);
            // A token that settles by any path completes futureToken; cancelling the scheduled
            // task there keeps the shared timer from retaining this token and its futures until
            // the deadline.
            futureToken.addListener(() -> timeoutHandle.cancel(false), directExecutor());
        }
        Futures.addCallback(
                businessOutcome,
                new FutureCallback<Object>() {
                    @Override
                    public void onSuccess(@Nullable Object result) {
                        transitionTo(SUCCESS);
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        // Commit the state before cancelling: a listener can still fix a cause (a
                        // task group escalating a member timeout) before cascade cancellation makes
                        // every path look like fail-fast. The cancel is unconditional: this
                        // callback is the only path that reaches the submission canceller when the
                        // transition itself lost the first-wins race (for example after an explicit
                        // cancel committed CANCELLED first).
                        transitionTo(FAIL_FAST);
                        cancelBoundWork(allFutures);
                    }
                },
                directExecutor());
        futureToken.setFuture(businessOutcome);
        return allFutures;
    }

    /**
     * Schedules the deadline action on the domain scheduler, falling back to the caller's scheduler
     * when the domain scheduler rejects because its owning runtime was already closed (a cancelled
     * ancestor body can still be running and nesting new work). When both are the same scheduler —
     * the token tree carries no domain scheduler — the rejection propagates.
     */
    private static ScheduledFuture<?> scheduleDeadline(
            ScheduledExecutorService deadlineTimer,
            ScheduledExecutorService timer,
            Runnable timeoutAction,
            long deadlineDelay) {
        try {
            return deadlineTimer.schedule(timeoutAction, deadlineDelay, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException retired) {
            if (deadlineTimer == timer) {
                throw retired;
            }
            return timer.schedule(timeoutAction, deadlineDelay, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Cancels bound work with the intent recorded by the winning token transition. A deadline or
     * fail-fast transition keeps the default interrupting policy; an explicit cancel(false), whether
     * direct or propagated, must reach the same futures without interrupting a running body.
     */
    private void cancelBoundWork(ListenableFuture<?> allFutures) {
        allFutures.cancel(verifyNotNull(decision.get()).interrupt);
    }

    /** Immutable terminal decision; state and interrupt intent publish together. */
    private static final class Decision {
        private final State state;
        private final boolean interrupt;

        private Decision(State state, boolean interrupt) {
            this.state = state;
            this.interrupt = interrupt;
        }

        static Decision running() {
            return new Decision(RUNNING, true);
        }
    }

    /**
     * Cancels this token and its linked work, interrupting running threads.
     *
     * <p>This is the common case; it is equivalent to {@link #cancel(boolean) cancel(true)}. Use
     * {@link #cancel(boolean)} with {@code false} only when running tasks must be allowed to
     * complete without interruption.
     */
    public void cancel() {
        cancel(true);
    }

    /**
     * Cancels this token and its linked work.
     *
     * <p>The interrupt policy is part of the cancellation and propagates down the token tree:
     * {@code cancel(false)} on a token cancels its linked work and every descendant's linked work
     * without interrupting running threads, while deadline expiry and fail-fast cancellation always
     * interrupt.
     *
     * @param useInterrupt whether to interrupt running threads
     */
    public void cancel(boolean useInterrupt) {
        if (transitionTo(CANCELLED, useInterrupt)) {
            futureToken.cancel(useInterrupt);
        }
    }

    /**
     * Cancels this token as a timeout without waiting for its own deadline to expire.
     *
     * <p>Intended for {@link TaskGroup}: when a member exceeds its own deadline, the group
     * escalates that timeout onto the group token so the group's completion reason stays {@code
     * TIMEOUT} instead of collapsing into fail-fast.
     */
    void timeoutCancel() {
        if (transitionTo(TIMEOUT)) {
            futureToken.cancel(true);
        }
    }

    /**
     * Cancels this token as a fail-fast cancellation, committing {@code FAIL_FAST} before the
     * cascade runs.
     *
     * <p>Intended for {@link TaskGroup}'s terminal combine: the combine is always the last task to
     * complete, so its failure must commit the group state synchronously — convergence reading the
     * token must not observe a still-{@code RUNNING} group and misattribute the terminal business
     * failure as a cancellation. Member failures keep the established rule and do not use this
     * path.
     */
    void failFastCancel() {
        if (transitionTo(FAIL_FAST)) {
            futureToken.cancel(true);
        }
    }

    /**
     * Returns the current state.
     *
     * @return the current state
     */
    public State state() {
        // state only ever transitions between non-null enum constants
        return verifyNotNull(decision.get()).state;
    }

    /**
     * Returns the terminal state that originated the cancellation, following propagation links.
     *
     * <p>When this token was cancelled by its parent, its own state is {@code PROPAGATED_CANCELLED}
     * and carries no reason; this method walks the parent chain to the first token whose terminal
     * state is not {@code PROPAGATED_CANCELLED} and returns that originating state. A token that
     * reached its terminal state on its own (or is still running) simply reports {@link #state()}.
     * The walk is safe at any moment: a token only transitions to {@code PROPAGATED_CANCELLED}
     * after its parent committed a terminal state, and terminal states never change afterward.
     *
     * @return the originating terminal state, or the current state when nothing propagated
     */
    public State originState() {
        CancellationToken token = this;
        State s = token.state();
        while (s == PROPAGATED_CANCELLED && token.parent != null) {
            token = token.parent;
            s = token.state();
        }
        return s;
    }

    /**
     * Registers a callback invoked synchronously right after a terminal transition commits and
     * before the associated cancellation actions run.
     *
     * <p>Intended for {@link TaskGroup}: a group listens on a member token so a member timeout
     * escalates to the group before cascade cancellation runs. It is not a general-purpose hook.
     * A listener registered after the token left {@code RUNNING} is never invoked. Listener
     * failures are logged and swallowed; they must not break cancellation.
     */
    void addStateListener(Consumer<State> listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    /** Commits a terminal transition from {@code RUNNING}, notifying state listeners when it wins. */
    private boolean transitionTo(State terminal) {
        return transitionTo(terminal, true);
    }

    /** Publishes a terminal state and its interrupt intent as one immutable decision. */
    private boolean transitionTo(State terminal, boolean interrupt) {
        Decision current = verifyNotNull(decision.get());
        if (current.state != RUNNING || !decision.compareAndSet(current, new Decision(terminal, interrupt))) {
            return false;
        }
        ParentLink link = parentLink;
        if (link != null) {
            link.sever();
        }
        notifyStateListeners(terminal);
        return true;
    }

    private void notifyStateListeners(State newState) {
        for (Consumer<State> listener : stateListeners) {
            try {
                listener.accept(newState);
            } catch (Throwable failure) {
                // Diagnostics must not escape: this runs inside transitionTo, and a throwing handler
                // would otherwise skip the caller's aftermath (cancel's futureToken cascade).
                try {
                    LOGGER.log(Level.WARNING, "CancellationToken state listener failed", failure);
                } catch (Throwable ignored) {
                    // A broken log handler is not allowed to alter cancellation behavior.
                }
            }
        }
    }

    /**
     * The parent side of a parent→child cancellation link.
     *
     * <p>The parent's completion listener reaches the child only through this holder. {@link
     * #parentFinished()} runs when the parent's future completes; it propagates the cancellation
     * only while the child is still running. {@link CancellationToken#transitionTo} severs the
     * holder when the child commits any terminal state, so a completed child token — including the
     * business results carried by its {@code futureToken} — stays collectable instead of being
     * pinned by the parent's pending listener list for the parent's whole lifetime.
     */
    private static final class ParentLink {

        private final CancellationToken parent;
        private @Nullable CancellationToken child;

        ParentLink(CancellationToken parent, CancellationToken child) {
            this.parent = parent;
            this.child = child;
        }

        void parentFinished() {
            CancellationToken linked = child;
            if (linked == null || !parent.state().shouldInterruptCurrentThread()) {
                return;
            }
            // Carry the parent's interrupt intent through the link: an explicit cancel(false)
            // keeps its no-interrupt policy for the whole subtree, while the framework's own
            // transitions (TIMEOUT, FAIL_FAST) keep the default true.
            boolean interrupt = verifyNotNull(parent.decision.get()).interrupt;
            if (linked.transitionTo(PROPAGATED_CANCELLED, interrupt)) {
                linked.futureToken.cancel(interrupt);
            }
        }

        /** Drops the child reference; called exactly once by the child's own terminal transition. */
        void sever() {
            child = null;
        }
    }

    /** Lifecycle state of a {@link CancellationToken}. */
    public enum State {

        /** The task is running. */
        RUNNING,
        /** The task completed successfully. */
        SUCCESS,
        /** A sibling task failed, triggering fail-fast cancellation. */
        FAIL_FAST,
        /** The task timed out. */
        TIMEOUT,
        /** The token was explicitly cancelled. */
        CANCELLED,
        /** The parent token was cancelled. */
        PROPAGATED_CANCELLED;

        /** Returns whether this state requires interruption. */
        boolean shouldInterruptCurrentThread() {
            return this != RUNNING && this != SUCCESS;
        }
    }
}

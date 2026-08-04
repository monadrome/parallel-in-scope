package io.github.huatalk.parallelinscope.control;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The lifecycle half of the {@link LifecycleBlockingQueue} twin pair: a Guava {@link Service} that
 * owns admission, the activity counters, the registry of in-flight blocking calls, and the shutdown
 * sweep.
 *
 * <p>{@code LifecycleBlockingQueue} must extend {@link
 * com.google.common.util.concurrent.ForwardingBlockingQueue ForwardingBlockingQueue} to inherit
 * forwarding semantics, and Java has no multiple inheritance, so the service state machine cannot
 * also be inherited there. This class supplies it. The two twins hold each other and cooperate only
 * through the narrow callbacks below — {@link #enter}, {@link #leave}, {@link #onInterrupted} in one
 * direction, {@link LifecycleBlockingQueue#lifecycleName()} in the other — so neither reaches into
 * the other's internals.
 *
 * <p>{@link AbstractService} is the right base among Guava's abstractions here. {@link
 * com.google.common.util.concurrent.AbstractIdleService AbstractIdleService} and {@link
 * com.google.common.util.concurrent.AbstractExecutionThreadService AbstractExecutionThreadService}
 * both terminate as soon as their {@code shutDown()} returns, and both spend a thread doing it; a
 * wrapped queue needs neither a thread while running nor termination at that moment. It must stay
 * {@link Service.State#STOPPING STOPPING} until the last blocking call has drained, which requires
 * driving {@link #notifyStopped()} manually from whichever caller drains last.
 *
 * <p>All state transitions, listener dispatch, failure propagation, and termination waiting are left
 * to {@link AbstractService}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see LifecycleBlockingQueue
 * @see BlockingCall
 */
final class QueueLifecycleService extends AbstractService {

    private final LifecycleBlockingQueue<?> owner;

    /**
     * Closes admission for new blocking calls. Set once, inside {@link #doStop()} or {@link
     * #doCancelStart()}, before the sweep begins, and never cleared. This single write is the
     * atomic point that divides admitted calls from rejected ones.
     */
    private final AtomicBoolean admissionClosed = new AtomicBoolean();

    /** Guarantees {@link #notifyStopped()} is invoked at most once across all draining threads. */
    private final AtomicBoolean terminationPublished = new AtomicBoolean();

    /** In-flight {@link BlockingOp#PUT} and {@link BlockingOp#TAKE} calls. */
    private final AtomicInteger activeUntimed = new AtomicInteger();

    /** In-flight {@link BlockingOp#TIMED_OFFER} and {@link BlockingOp#TIMED_POLL} calls. */
    private final AtomicInteger activeTimed = new AtomicInteger();

    /**
     * The precise set of calls currently registered, with the operation and thread of each.
     *
     * <p>A {@link ConcurrentHashMap} keyset, not a lock-guarded collection: registration must never
     * make one blocking call wait for another. Its weakly consistent iterator is sufficient for the
     * sweep because {@link #admissionClosed} is written before the sweep reads the registry, while
     * every admitted call is registered before it reads {@code admissionClosed}. Any call the sweep
     * cannot see has therefore already observed the closed gate and rejects itself.
     */
    private final Set<BlockingCall> activeCalls = ConcurrentHashMap.newKeySet();

    /**
     * Creates a lifecycle service bound to its queue twin.
     *
     * @param owner the queue twin this service governs
     */
    QueueLifecycleService(LifecycleBlockingQueue<?> owner) {
        this.owner = owner;
    }

    /**
     * Admits one blocking call, charging it to the counter for its operation class and registering it
     * so a shutdown sweep can find it.
     *
     * <p>Admission touches only atomics: the counter increment, the registry insertion, and the read
     * of {@link #admissionClosed}. No mutual exclusion is used, so concurrent blocking calls never
     * queue behind one another in this wrapper.
     *
     * <p>Registration deliberately happens <i>before</i> the gate is re-read. That order is what
     * makes the handoff airtight. A call that reads the gate as open has already published itself,
     * so a sweep that closes the gate afterwards is guaranteed to see and interrupt it; a call that
     * reads the gate as closed withdraws itself and never touches the backing queue. There is no
     * ordering in which a call both misses the sweep and reaches the backing queue.
     *
     * @param op the operation about to be attempted
     * @return the registered call, to be passed to {@link #leave} once the operation returns
     * @throws QueueShutdownException if admission is closed, in which case nothing remains registered
     */
    BlockingCall enter(BlockingOp op) {
        startIfNew();
        BlockingCall call = new BlockingCall(op, Thread.currentThread());
        counterFor(op).incrementAndGet();
        activeCalls.add(call);
        if (admissionClosed.get() || state().compareTo(State.RUNNING) > 0) {
            leave(call);
            throw rejected(op);
        }
        return call;
    }

    /**
     * Deregisters a finished call, settling its handshake and releasing its counter.
     *
     * <p>{@link BlockingCall#settle()} runs first, so if a sweep had claimed this call, this method
     * does not return until that sweep's interrupt has actually been issued. The counter is then
     * released, and if this was the last active call of a service already stopping, termination is
     * published.
     *
     * @param call the call previously returned by {@link #enter}
     * @return {@code true} if a shutdown sweep claimed this call and interrupted its owner, meaning
     *     any pending interrupt on the current thread belongs to this call
     */
    boolean leave(BlockingCall call) {
        boolean claimedByShutdown = call.settle();
        if (claimedByShutdown && Thread.currentThread() == call.owner()) {
            Thread.interrupted();
        }
        activeCalls.remove(call);
        counterFor(call.op()).decrementAndGet();
        publishTerminationIfDrained();
        return claimedByShutdown;
    }

    /**
     * Classifies an {@link InterruptedException} observed by a blocking call.
     *
     * <p>An interrupt is attributed to the lifecycle only when both halves agree: the service has
     * left the running states, <i>and</i> this specific call was claimed by the sweep. Requiring the
     * per-call claim is what prevents a genuine external interrupt that happens to arrive during
     * shutdown from being misreported as a lifecycle interrupt.
     *
     * @param call the call that observed the interrupt
     * @param claimedByShutdown the result of {@link #leave(BlockingCall)} for that call
     * @param cause the exception thrown by the backing queue
     * @return the lifecycle exception to throw instead, or {@code null} if the interrupt was external
     *     and {@code cause} should propagate unchanged
     */
    @Nullable
    QueueShutdownException onInterrupted(BlockingCall call, boolean claimedByShutdown,
                                        InterruptedException cause) {
        if (!claimedByShutdown || state().compareTo(State.RUNNING) <= 0) {
            return null;
        }
        return new QueueShutdownException(
                owner.lifecycleName() + " was shut down while " + call.op() + " was blocked", cause);
    }

    private QueueShutdownException rejected(BlockingOp op) {
        return new QueueShutdownException(
                owner.lifecycleName() + " is " + state() + "; " + op + " is no longer accepted");
    }

    private AtomicInteger counterFor(BlockingOp op) {
        return op.timed() ? activeTimed : activeUntimed;
    }

    /**
     * Returns the number of in-flight untimed blocking calls.
     *
     * @return current {@link BlockingOp#PUT} plus {@link BlockingOp#TAKE} count
     */
    int activeUntimedCount() {
        return activeUntimed.get();
    }

    /**
     * Returns the number of in-flight timed blocking calls.
     *
     * @return current {@link BlockingOp#TIMED_OFFER} plus {@link BlockingOp#TIMED_POLL} count
     */
    int activeTimedCount() {
        return activeTimed.get();
    }

    /**
     * Reports whether new blocking calls are still admitted.
     *
     * @return {@code true} once shutdown has closed the gate
     */
    boolean isAdmissionClosed() {
        return admissionClosed.get();
    }

    /**
     * Moves a {@link State#NEW NEW} service to {@link State#RUNNING RUNNING} so a queue is usable
     * without an explicit {@link #startAsync()}.
     *
     * <p>A wrapped queue has nothing to initialize, so autostart keeps the common case ergonomic. The
     * {@link IllegalStateException} from a concurrent {@code startAsync()} is swallowed because
     * losing that race means the winner has already started the service.
     */
    void startIfNew() {
        if (state() != State.NEW) {
            return;
        }
        try {
            startAsync();
        } catch (IllegalStateException alreadyStarted) {
            // A concurrent caller won the start race; the service is started either way.
        }
    }

    /** Completes startup immediately: a wrapped queue is operational as soon as it exists. */
    @Override
    protected void doStart() {
        notifyStarted();
    }

    /**
     * Requests shutdown, closing admission before any state transition is attempted.
     *
     * <p>Closing the gate here rather than only in {@link #doStop()} covers the {@link State#NEW NEW}
     * case: {@link AbstractService#stopAsync()} takes a {@code NEW} service straight to {@link
     * State#TERMINATED TERMINATED} without ever invoking {@code doStop()}, so a gate closed only there
     * would stay open on a queue that is already terminated.
     */
    void requestStop() {
        admissionClosed.set(true);
        stopAsync();
    }

    /** Closes admission, interrupts every registered call, then terminates once they have drained. */
    @Override
    protected void doStop() {
        shutdown();
    }

    /**
     * Handles {@link #stopAsync()} arriving during startup, which for this service is the same work
     * as {@link #doStop()}: close the gate, sweep, and terminate when drained.
     */
    @Override
    protected void doCancelStart() {
        shutdown();
    }

    /**
     * Runs the shutdown sequence: close admission first, then interrupt everything already admitted.
     *
     * <p>The order is what makes the sweep complete. Because the gate is closed before the registry
     * is read, no call can be admitted after the sweep begins, so a single pass is enough — there is
     * no need to re-scan for late arrivals.
     */
    private void shutdown() {
        admissionClosed.set(true);
        for (BlockingCall call : new HashSet<>(activeCalls)) {
            call.interruptForShutdown();
        }
        publishTerminationIfDrained();
    }

    /**
     * Terminates the service once shutdown has been requested and both activity counters read zero.
     *
     * <p>Called by {@link #shutdown()} and by every {@link #leave} so that whichever thread happens to
     * drain the last call publishes termination. Gating on {@link State#STOPPING STOPPING} covers both
     * halves of the condition: it implies {@link #admissionClosed} was set, so a zero counter reading
     * cannot be undone by a later admission, and it excludes a service that {@link
     * AbstractService#stopAsync()} already took from {@link State#NEW NEW} straight to {@link
     * State#TERMINATED TERMINATED} without ever invoking {@link #doStop()} — calling {@link
     * #notifyStopped()} there would throw. {@link #terminationPublished} makes the call single even
     * when several threads observe zero at once.
     */
    private void publishTerminationIfDrained() {
        if (state() != State.STOPPING || activeUntimed.get() != 0 || activeTimed.get() != 0) {
            return;
        }
        if (terminationPublished.compareAndSet(false, true)) {
            notifyStopped();
        }
    }

    @Override
    public String toString() {
        return owner.lifecycleName() + " [" + state() + ", untimed=" + activeUntimed.get()
                + ", timed=" + activeTimed.get() + ']';
    }
}

package io.github.huatalk.parallelinscope.control;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One in-flight blocking call on a {@link LifecycleBlockingQueue}, and the handshake that decides
 * whether shutdown is allowed to interrupt it.
 *
 * <p>A call is registered before it enters the backing queue and deregistered after it leaves. In
 * between, a shutdown sweep may pick it and interrupt its thread. That creates the race this class
 * exists to close: a call may finish, deregister, and return its thread to a pool at the same moment
 * a sweep decides to interrupt it. Without a handshake the interrupt would land on a thread that has
 * already moved on to unrelated work.
 *
 * <p>The handshake is a four-phase, per-call state machine advanced only by CAS, so distinct calls
 * never contend and no call ever waits behind a lock held by another:
 *
 * <ol>
 *   <li>{@link Phase#RUNNING RUNNING} — registered; a sweep may still claim it.
 *   <li>{@link Phase#CLAIMED CLAIMED} — a sweep won the CAS and owes this call exactly one {@link
 *       Thread#interrupt()}. Reached only from {@code RUNNING}.
 *   <li>{@link Phase#DELIVERED DELIVERED} — the sweep has issued that interrupt. The owner may now
 *       observe or clear its own interrupt status without racing the sweep.
 *   <li>{@link Phase#FINISHED FINISHED} — terminal. No interrupt will ever be issued for this call.
 * </ol>
 *
 * <p>Exactly one of two transitions therefore wins. Either the owner moves {@code RUNNING ->
 * FINISHED} first, and the sweep's claim CAS fails so no interrupt is issued at all; or the sweep
 * moves {@code RUNNING -> CLAIMED} first, and the owner waits for {@code DELIVERED} before
 * finishing, so the interrupt is guaranteed to have landed inside the call's own scope.
 *
 * <p>Instances are safe for concurrent use by the owning thread and any number of sweeping threads.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see LifecycleBlockingQueue
 */
final class BlockingCall {

    /** Phases of the per-call shutdown handshake. */
    enum Phase {
        /** Registered and still interruptible by a shutdown sweep. */
        RUNNING,
        /** Selected by a shutdown sweep, which still owes this call one {@code interrupt()}. */
        CLAIMED,
        /** The shutdown sweep has issued its {@code interrupt()} for this call. */
        DELIVERED,
        /** Terminal: the call has left the backing queue and can no longer be interrupted. */
        FINISHED
    }

    private final BlockingOp op;
    private final Thread owner;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);

    /**
     * Memoizes the outcome of {@link #settle()} so it can be queried repeatedly. Written by the
     * owner before {@link #settled} is published, read by the owner afterwards.
     */
    private boolean claimedByShutdown;

    /** Publishes {@link #claimedByShutdown}; {@code true} once {@link #settle()} has completed. */
    private volatile boolean settled;

    /**
     * Creates a registered call in the {@link Phase#RUNNING RUNNING} phase.
     *
     * <p>Identity is the instance itself; no shared counter is consulted, so constructing a call adds
     * no contended write. Registration relies on identity semantics, which {@link Object}'s inherited
     * {@code equals}/{@code hashCode} already provide.
     *
     * @param op the operation about to be attempted
     * @param owner the thread that will block inside the backing queue
     */
    BlockingCall(BlockingOp op, Thread owner) {
        this.op = op;
        this.owner = owner;
    }

    /**
     * Returns the operation this call is performing.
     *
     * @return the intercepted operation
     */
    BlockingOp op() {
        return op;
    }

    /**
     * Returns the thread blocked inside the backing queue.
     *
     * @return the owning thread
     */
    Thread owner() {
        return owner;
    }

    /**
     * Returns the current handshake phase.
     *
     * @return the current phase
     */
    Phase phase() {
        return phase.get();
    }

    /**
     * Attempts, on behalf of a shutdown sweep, to claim this call and interrupt its owner.
     *
     * <p>Claiming is a single CAS out of {@link Phase#RUNNING RUNNING}, so at most one sweep ever
     * interrupts a given call, and a call that has already settled is never interrupted. On a
     * successful claim the owner is interrupted and the phase advances to {@link Phase#DELIVERED
     * DELIVERED}, which releases an owner waiting inside {@link #settle()}. The phase is advanced
     * even if {@link Thread#interrupt()} throws, so a failed interrupt cannot strand the owner.
     *
     * <p>May be called by any thread, and concurrently with {@link #settle()}.
     *
     * @return {@code true} if this call was claimed and its owner interrupted; {@code false} if the
     *     owner had already settled, in which case no interrupt was issued
     */
    boolean interruptForShutdown() {
        if (!phase.compareAndSet(Phase.RUNNING, Phase.CLAIMED)) {
            return false;
        }
        try {
            owner.interrupt();
        } finally {
            phase.set(Phase.DELIVERED);
        }
        return true;
    }

    /**
     * Drives this call to {@link Phase#FINISHED FINISHED} and reports whether shutdown interrupted
     * it. Called by the owner once it has left the backing queue, on both the normal and the
     * exceptional path.
     *
     * <p>If the owner wins the race, the phase moves straight from {@code RUNNING} to {@code
     * FINISHED} and no interrupt will ever be issued for this call. If a sweep won instead, this
     * method waits for the sweep to reach {@link Phase#DELIVERED DELIVERED} before finishing. That
     * wait is what guarantees the lifecycle interrupt is confined to this call: once this method
     * returns {@code true}, the interrupt has already been issued, so the caller can consume it and
     * hand a clean thread back to its pool.
     *
     * <p>The wait is bounded by the sweep's progress between two CAS-free instructions, never by
     * another blocking call, and spins with {@link Thread#yield()} rather than blocking, so no
     * monitor is acquired anywhere on this path. Idempotent: later calls return the memoized result
     * without spinning.
     *
     * @return {@code true} if a shutdown sweep claimed this call and has issued its interrupt;
     *     {@code false} if the call finished before any sweep could claim it
     */
    boolean settle() {
        if (settled) {
            return claimedByShutdown;
        }
        boolean claimed;
        if (phase.compareAndSet(Phase.RUNNING, Phase.FINISHED)) {
            claimed = false;
        } else {
            while (phase.get() == Phase.CLAIMED) {
                Thread.yield();
            }
            claimed = true;
            phase.set(Phase.FINISHED);
        }
        claimedByShutdown = claimed;
        settled = true;
        return claimed;
    }

    @Override
    public String toString() {
        return "BlockingCall{op=" + op + ", thread=" + owner.getName() + ", phase=" + phase.get() + '}';
    }
}

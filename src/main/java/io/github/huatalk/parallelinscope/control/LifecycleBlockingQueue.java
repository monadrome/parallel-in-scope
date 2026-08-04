package io.github.huatalk.parallelinscope.control;

import com.google.common.util.concurrent.ForwardingBlockingQueue;
import com.google.common.util.concurrent.Service;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps any {@link BlockingQueue} with a Guava {@link Service} lifecycle, so that shutting the queue
 * down releases every thread blocked inside it instead of leaving them parked forever.
 *
 * <p>An ordinary {@code BlockingQueue} has no shutdown concept. Producers stuck in {@link #put} and
 * consumers stuck in {@link #take} stay stuck until an element arrives, which during shutdown never
 * happens. This wrapper adds the missing lifecycle: it tracks each blocking call in flight and, on
 * shutdown, interrupts exactly those calls.
 *
 * <h2>What is intercepted</h2>
 *
 * Only the four operations that can block:
 *
 * <ul>
 *   <li>{@link #put(Object)}
 *   <li>{@link #take()}
 *   <li>{@link #offer(Object, long, TimeUnit)}
 *   <li>{@link #poll(long, TimeUnit)}
 * </ul>
 *
 * <p>Each delegates to the identical method on the backing queue, preserving its blocking, fairness,
 * and capacity semantics exactly. Notably, {@link #put} and {@link #take} are <i>not</i> emulated by
 * polling with short deadlines, and no poison pill is ever inserted: element flow is untouched, so
 * the queue never yields an element the producer did not offer. Every other queue and collection
 * method keeps plain {@link ForwardingBlockingQueue} forwarding semantics and remains usable after
 * shutdown.
 *
 * <h2>Shutdown</h2>
 *
 * {@link #stopAsync()} and {@link #close()} first close admission atomically, then interrupt every
 * already-registered blocking call. Ordering the gate before the sweep is what makes one pass
 * sufficient: no call can slip in behind the sweep.
 *
 * <p>Interrupts are classified rather than assumed. A blocking call that catches {@link
 * InterruptedException} converts it to {@link QueueShutdownException} — with the original exception
 * as its cause — only when the service has left the running states <i>and</i> the shutdown sweep
 * claimed that specific call. An interrupt from any other source propagates unchanged as {@code
 * InterruptedException}, so cancellation built on thread interruption keeps working. A blocking call
 * that starts after admission closes throws {@link QueueShutdownException} without touching the
 * backing queue.
 *
 * <h2>Concurrency</h2>
 *
 * Entry and exit use only atomic counters and a {@link java.util.concurrent.ConcurrentHashMap}-backed
 * registry — no {@code synchronized} block, {@code ReentrantLock}, or other mutual exclusion appears
 * on the blocking path. Callers never serialize inside the wrapper; the only contention they ever see
 * is the backing queue's own. Untimed calls ({@code put}/{@code take}) and timed calls ({@code
 * offer}/{@code poll}) are charged to two independent counters, and the service reaches {@link
 * Service.State#TERMINATED TERMINATED} only after both reach zero.
 *
 * <p>The "call finished, deregistered, thread reused, and only then the shutdown thread fires its
 * interrupt" race is closed by a per-call CAS handshake; see {@link BlockingCall}. Interrupts issued
 * by shutdown are consumed before the failing call returns, so a pooled thread is never handed back
 * with a stray interrupt.
 *
 * <h2>Lifecycle states</h2>
 *
 * The queue is usable immediately — the first blocking call starts the service implicitly, so {@link
 * #startAsync()} is optional. Because {@code Service} forbids restarts, a stopped queue stays
 * stopped.
 *
 * <h2>Structure</h2>
 *
 * This class extends {@code ForwardingBlockingQueue} and implements {@link Service} and {@link
 * AutoCloseable} by delegating to a package-private {@link QueueLifecycleService} twin, since Java
 * cannot inherit both hierarchies. The pairing is invisible from outside: every {@code Service}
 * method returns and reports on this queue object, and {@link #startAsync()}/{@link #stopAsync()}
 * return {@code this}.
 *
 * <p>Example:
 *
 * <pre>{@code
 * LifecycleBlockingQueue<Task> queue =
 *         new LifecycleBlockingQueue<>(new LinkedBlockingQueue<Task>(128));
 * // consumer thread
 * try {
 *     while (true) {
 *         process(queue.take());
 *     }
 * } catch (QueueShutdownException stopping) {
 *     // the queue was shut down; exit the loop
 * }
 * // shutdown thread
 * queue.stopAsync();
 * queue.awaitTerminated();
 * }</pre>
 *
 * @param <E> the type of elements held in this queue
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see QueueShutdownException
 * @see BlockingCall
 */
public class LifecycleBlockingQueue<E> extends ForwardingBlockingQueue<E>
        implements Service, AutoCloseable {

    private final BlockingQueue<E> delegate;
    private final QueueLifecycleService lifecycle = new QueueLifecycleService(this);
    private final String name;
    private final boolean probeBeforeBlocking;

    /**
     * Wraps the given queue, naming the lifecycle after this class.
     *
     * @param delegate the backing queue; all element storage and blocking semantics remain its own
     */
    public LifecycleBlockingQueue(BlockingQueue<E> delegate) {
        this(delegate, "LifecycleBlockingQueue", false);
    }

    /**
     * Wraps the given queue with an explicit lifecycle name used in service diagnostics and in the
     * messages of thrown {@link QueueShutdownException}s.
     *
     * @param delegate the backing queue; all element storage and blocking semantics remain its own
     * @param name name for this queue's lifecycle
     */
    public LifecycleBlockingQueue(BlockingQueue<E> delegate, String name) {
        this(delegate, name, false);
    }

    /**
     * Wraps the given queue, optionally skipping lifecycle registration for calls that can be satisfied
     * without blocking.
     *
     * <p>With {@code probeBeforeBlocking} enabled, {@link #put(Object)} and {@link #offer(Object, long,
     * TimeUnit)} first try {@link BlockingQueue#offer(Object)}, and {@link #take()} and {@link
     * #poll(long, TimeUnit)} first try {@link BlockingQueue#poll()}. A successful probe returns without
     * allocating a call record, touching either activity counter, or writing to the registry, so the
     * uncontended path costs one extra non-blocking delegate call and nothing else. Calls that would
     * actually block register and behave exactly as they do with probing disabled — and a call that is
     * parked contributes no throughput anyway, so it is the right place to carry the bookkeeping cost.
     *
     * <p>Probing is off by default because it relaxes three otherwise strict guarantees. It can reorder
     * callers relative to a fair or otherwise ordered queue, since a probing arrival may take an element
     * ahead of a producer already queued in the delegate. On a {@link
     * java.util.concurrent.SynchronousQueue SynchronousQueue}, where {@code offer}/{@code poll} succeed
     * only when a counterpart is already waiting, the probe nearly always fails and simply adds work.
     * And because the probe reads the admission gate before its delegate call rather than after, a
     * concurrent shutdown may leave a probing call succeeding where it would otherwise have thrown
     * {@link QueueShutdownException} — harmless, since such a call is indistinguishable from a direct
     * {@link #offer(Object)} or {@link #poll()}, both of which keep forwarding after shutdown by design.
     *
     * <p>What probing does <i>not</i> weaken is the guarantee that matters: a probe never parks, so no
     * call can end up waiting inside the delegate without being registered and therefore interruptible
     * by shutdown. Enable this for plain buffered queues under load, not for queues whose ordering
     * guarantees callers depend on.
     *
     * @param delegate the backing queue; all element storage and blocking semantics remain its own
     * @param name name for this queue's lifecycle
     * @param probeBeforeBlocking whether to attempt a non-blocking delegate call before registering
     */
    public LifecycleBlockingQueue(BlockingQueue<E> delegate, String name, boolean probeBeforeBlocking) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.name = Objects.requireNonNull(name, "name");
        this.probeBeforeBlocking = probeBeforeBlocking;
    }

    @Override
    protected BlockingQueue<E> delegate() {
        return delegate;
    }

    /**
     * Returns the name identifying this queue's lifecycle.
     *
     * @return the lifecycle name
     */
    String lifecycleName() {
        return name;
    }

    /**
     * Inserts the given element, waiting for space as long as necessary.
     *
     * <p>Delegates to the backing queue's own {@link BlockingQueue#put(Object)} with no deadline
     * imposed, then releases its lifecycle registration on every exit path.
     *
     * @param e the element to add
     * @throws QueueShutdownException if the queue is shutting down or already stopped, either because
     *     this call arrived after admission closed or because shutdown interrupted it while blocked;
     *     in the latter case the consumed {@link InterruptedException} is the cause
     * @throws InterruptedException if the calling thread is interrupted by anything other than this
     *     queue's shutdown
     */
    @Override
    public void put(E e) throws InterruptedException {
        if (probeBeforeBlocking && !lifecycle.isAdmissionClosed() && delegate.offer(e)) {
            return;
        }
        guarded(BlockingOp.PUT, () -> {
            delegate.put(e);
            return null;
        });
    }

    /**
     * Retrieves and removes the head of this queue, waiting for an element as long as necessary.
     *
     * <p>Delegates to the backing queue's own {@link BlockingQueue#take()} with no deadline imposed,
     * then releases its lifecycle registration on every exit path.
     *
     * @return the head of this queue
     * @throws QueueShutdownException if the queue is shutting down or already stopped, either because
     *     this call arrived after admission closed or because shutdown interrupted it while blocked;
     *     in the latter case the consumed {@link InterruptedException} is the cause
     * @throws InterruptedException if the calling thread is interrupted by anything other than this
     *     queue's shutdown
     */
    @Override
    public E take() throws InterruptedException {
        if (probeBeforeBlocking && !lifecycle.isAdmissionClosed()) {
            E probed = delegate.poll();
            if (probed != null) {
                return probed;
            }
        }
        return guarded(BlockingOp.TAKE, delegate::take);
    }

    /**
     * Inserts the given element, waiting up to the given time for space.
     *
     * <p>Delegates to the backing queue's own {@link BlockingQueue#offer(Object, long, TimeUnit)} with
     * the caller's deadline unchanged, then releases its lifecycle registration on every exit path.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the deadline elapsed first
     * @throws QueueShutdownException if the queue is shutting down or already stopped, either because
     *     this call arrived after admission closed or because shutdown interrupted it while blocked;
     *     in the latter case the consumed {@link InterruptedException} is the cause
     * @throws InterruptedException if the calling thread is interrupted by anything other than this
     *     queue's shutdown
     */
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (probeBeforeBlocking && !lifecycle.isAdmissionClosed() && delegate.offer(e)) {
            return true;
        }
        return guarded(BlockingOp.TIMED_OFFER, () -> delegate.offer(e, timeout, unit));
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the given time for an element.
     *
     * <p>Delegates to the backing queue's own {@link BlockingQueue#poll(long, TimeUnit)} with the
     * caller's deadline unchanged, then releases its lifecycle registration on every exit path.
     *
     * @param timeout how long to wait before giving up
     * @param unit the unit of {@code timeout}
     * @return the head of this queue, or {@code null} if the deadline elapsed first
     * @throws QueueShutdownException if the queue is shutting down or already stopped, either because
     *     this call arrived after admission closed or because shutdown interrupted it while blocked;
     *     in the latter case the consumed {@link InterruptedException} is the cause
     * @throws InterruptedException if the calling thread is interrupted by anything other than this
     *     queue's shutdown
     */
    @Override
    @Nullable
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (probeBeforeBlocking && !lifecycle.isAdmissionClosed()) {
            E probed = delegate.poll();
            if (probed != null) {
                return probed;
            }
        }
        return guarded(BlockingOp.TIMED_POLL, () -> delegate.poll(timeout, unit));
    }

    /** One blocking operation on the backing queue, invoked between registration and deregistration. */
    private interface DelegateCall<R> {
        @Nullable
        R invoke() throws InterruptedException;
    }

    /**
     * Runs one blocking operation under lifecycle registration.
     *
     * <p>Registers the call, invokes the backing queue's native method, and deregisters exactly once on
     * every exit path. Deregistration happens before the interrupt is classified, because {@link
     * QueueLifecycleService#leave} is what both settles the per-call handshake and reports whether
     * shutdown claimed this call.
     *
     * <p>A call that completes successfully returns its result even if the sweep claimed it a moment
     * earlier: the element has already changed hands, so failing here would lose it. The lifecycle
     * interrupt aimed at that finished wait is consumed rather than propagated.
     *
     * @param op the operation being performed, which selects the activity counter
     * @param body the corresponding native call on the backing queue
     * @param <R> the operation's result type
     * @return whatever {@code body} returned
     * @throws QueueShutdownException if admission is closed or shutdown interrupted this call
     * @throws InterruptedException if the interrupt came from anywhere other than this queue's shutdown
     */
    @Nullable
    private <R> R guarded(BlockingOp op, DelegateCall<R> body) throws InterruptedException {
        BlockingCall call = lifecycle.enter(op);
        boolean left = false;
        try {
            R result = body.invoke();
            left = true;
            lifecycle.leave(call);
            return result;
        } catch (InterruptedException interrupted) {
            left = true;
            QueueShutdownException shutdown =
                    lifecycle.onInterrupted(call, lifecycle.leave(call), interrupted);
            if (shutdown != null) {
                throw shutdown;
            }
            throw interrupted;
        } finally {
            if (!left) {
                lifecycle.leave(call);
            }
        }
    }

    /**
     * Starts this queue's lifecycle explicitly.
     *
     * <p>Optional: the first blocking call starts the service implicitly, since a wrapped queue has
     * nothing to initialize.
     *
     * @return this queue, never the internal service twin
     * @throws IllegalStateException if the lifecycle is not {@link State#NEW NEW}
     */
    @Override
    public Service startAsync() {
        lifecycle.startAsync();
        return this;
    }

    /**
     * Initiates shutdown and returns immediately.
     *
     * <p>Admission for new blocking calls is closed before this method returns, and every call already
     * registered is interrupted. The service reaches {@link State#TERMINATED TERMINATED} once both
     * activity counters drain, which may be after this method returns; use {@link #awaitTerminated()}
     * to wait for it.
     *
     * @return this queue, never the internal service twin
     */
    @Override
    public Service stopAsync() {
        lifecycle.requestStop();
        return this;
    }

    /**
     * Initiates shutdown without waiting, so this queue can be used as a try-with-resources resource.
     *
     * <p>Equivalent to {@link #stopAsync()} and, like it, does not block for in-flight calls to drain.
     * Call {@link #awaitTerminated()} afterwards when the caller needs that guarantee.
     */
    @Override
    public void close() {
        stopAsync();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return lifecycle.isRunning();
    }

    /** {@inheritDoc} */
    @Override
    public State state() {
        return lifecycle.state();
    }

    /** {@inheritDoc} */
    @Override
    public void awaitRunning() {
        lifecycle.awaitRunning();
    }

    /** {@inheritDoc} */
    @Override
    public void awaitRunning(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitRunning(timeout, unit);
    }

    /** {@inheritDoc} */
    @Override
    public void awaitTerminated() {
        lifecycle.awaitTerminated();
    }

    /** {@inheritDoc} */
    @Override
    public void awaitTerminated(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitTerminated(timeout, unit);
    }

    /** {@inheritDoc} */
    @Override
    public Throwable failureCause() {
        return lifecycle.failureCause();
    }

    /** {@inheritDoc} */
    @Override
    public void addListener(Listener listener, Executor executor) {
        lifecycle.addListener(listener, executor);
    }

    /**
     * Returns the number of {@link #put(Object)} and {@link #take()} calls currently blocked.
     *
     * <p>Counted separately from {@link #activeTimedCount()} because untimed calls can only be released
     * by the backing queue or an interrupt, whereas timed calls always expire on their own.
     *
     * @return the current untimed activity count
     */
    public int activeUntimedCount() {
        return lifecycle.activeUntimedCount();
    }

    /**
     * Returns the number of {@link #offer(Object, long, TimeUnit)} and {@link #poll(long, TimeUnit)}
     * calls currently blocked.
     *
     * @return the current timed activity count
     */
    public int activeTimedCount() {
        return lifecycle.activeTimedCount();
    }

    /**
     * Reports whether shutdown has closed this queue to new blocking calls.
     *
     * <p>Non-blocking methods such as {@link #offer(Object)} and {@link #poll()} keep working
     * regardless.
     *
     * @return {@code true} once shutdown has begun
     */
    public boolean isAdmissionClosed() {
        return lifecycle.isAdmissionClosed();
    }

    @Override
    public String toString() {
        return name + " [" + state() + "] " + delegate;
    }
}

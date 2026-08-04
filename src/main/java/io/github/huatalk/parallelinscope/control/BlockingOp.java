package io.github.huatalk.parallelinscope.control;

/**
 * The four potentially blocking {@link java.util.concurrent.BlockingQueue} operations intercepted
 * by {@link LifecycleBlockingQueue}.
 *
 * <p>The {@link #timed()} flag decides which of the two independent activity counters an in-flight
 * call is charged to. Untimed calls can block forever and can only be released by the backing queue
 * or by an interrupt; timed calls always unblock on their own deadline.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
enum BlockingOp {

    /** {@link java.util.concurrent.BlockingQueue#put(Object)}. */
    PUT(false),

    /** {@link java.util.concurrent.BlockingQueue#take()}. */
    TAKE(false),

    /** {@link java.util.concurrent.BlockingQueue#offer(Object, long, java.util.concurrent.TimeUnit)}. */
    TIMED_OFFER(true),

    /** {@link java.util.concurrent.BlockingQueue#poll(long, java.util.concurrent.TimeUnit)}. */
    TIMED_POLL(true);

    private final boolean timed;

    BlockingOp(boolean timed) {
        this.timed = timed;
    }

    /**
     * Reports whether this operation carries a caller-supplied deadline.
     *
     * @return {@code true} for {@link #TIMED_OFFER} and {@link #TIMED_POLL}, {@code false} for
     *     {@link #PUT} and {@link #TAKE}
     */
    boolean timed() {
        return timed;
    }
}

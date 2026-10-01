package io.github.monadrome.parallelinscope;

/**
 * Task type classification. Currently drives only the enqueue decision of {@link
 * SmartBlockingQueue}.
 *
 * <p>The type describes the work, not what to do when the work cannot be scheduled: it does not
 * affect what happens when an executor rejects a task, which is the pool's own {@link
 * java.util.concurrent.RejectedExecutionHandler} and applies to every task type. The only behavior
 * a type selects today is whether {@code SmartBlockingQueue} refuses to enqueue the task — a
 * {@link #CPU_BOUND} task is refused even when {@code rejectEnqueue} is false, the other values
 * are enqueued. With any other queue, the type never changes what the library does.
 *
 * <p>The default on {@link TaskOptions}/{@link BatchOptions} is {@link #IO_BOUND}, paired with
 * {@code rejectEnqueue=false}. That pairing is deliberate: because {@code offer} refuses on
 * {@code CPU_BOUND} <em>or</em> {@code rejectEnqueue}, a {@code CPU_BOUND} default would make a
 * {@code SmartBlockingQueue} refuse every task submitted with default options, so its configured
 * capacity would never be used and every task would reach the rejection handler. Declaring
 * {@link #CPU_BOUND} explicitly is how a caller asks for that refusal.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public enum TaskType {
    /** Network, RPC, database operations. Enqueued. */
    IO_BOUND,
    /** Computation, validation, transformation, filtering. Refused enqueue. */
    CPU_BOUND,
    /**
     * Hybrid: e.g., cache check first, then IO on miss.
     *
     * <p>Currently indistinguishable from {@link #IO_BOUND}: both are enqueued, and neither
     * affects the rejection path. Retained as a declaration of intent for mixed workloads; it is
     * not a scheduling instruction.
     */
    MIXED
}

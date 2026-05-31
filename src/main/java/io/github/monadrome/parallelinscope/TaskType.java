package io.github.monadrome.parallelinscope;

/**
 * Task type classification. Currently drives only the enqueue decision of {@link
 * io.github.monadrome.parallelinscope.SmartBlockingQueue SmartBlockingQueue}.
 *
 * <p>The type describes the work, not what to do when the work cannot be scheduled: it does not
 * affect what happens when an executor rejects a task, which is {@code runOnCallerThread} on
 * {@link TaskOptions}/{@link BatchOptions} and applies to every task type. The only behaviour a
 * type selects today is whether {@code SmartBlockingQueue} refuses to enqueue the task — a
 * {@link #CPU_BOUND} task is refused even when {@code rejectEnqueue} is false, the other values
 * are enqueued. With any other queue, the type never changes what the library does.
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

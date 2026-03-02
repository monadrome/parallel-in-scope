package io.github.linzee1.vformation.scope;

/**
 * Task type classification, determines scheduling behavior.
 * <p>
 * Used by {@link io.github.linzee1.vformation.queue.SmartBlockingQueue SmartBlockingQueue}
 * and {@link io.github.linzee1.vformation.internal.ConcurrentLimitExecutor ConcurrentLimitExecutor}
 * to control how tasks are queued and executed.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public enum TaskType {
    /** Network, RPC, database operations */
    IO_BOUND,
    /** Computation, validation, transformation, filtering */
    CPU_BOUND,
    /** Hybrid: e.g., cache check first, then IO on miss */
    MIXED
}

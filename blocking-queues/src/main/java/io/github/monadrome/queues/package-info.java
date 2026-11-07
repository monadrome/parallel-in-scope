/**
 * Capacity-aware and lifecycle-aware blocking queue implementations for Java applications that need bounded or lifecycle-aware blocking queues.
 *
 * <p>{@link io.github.monadrome.queues.DrainingBlockingQueue} is the lifecycle-aware
 * implementation: its one-way {@code OPEN → DRAINING → DRAINED} close permanently rejects
 * producers while allowing consumers and {@code drainTo} to remove every accepted element. Its
 * terminal signal and post-terminal mutation behavior are selected by {@link
 * io.github.monadrome.queues.DrainingBlockingQueue.ShutdownPolicy}; the queue's API
 * documentation defines the method-level blocking, exception, traversal, and concurrency contracts.
 * {@link io.github.monadrome.queues.ResizableBlockingQueue} provides a
 * dynamically adjustable capacity without depending on the parallel execution kernel.
 *
 * @since 0.3.0
 */
@NullMarked
package io.github.monadrome.queues;

import org.jspecify.annotations.NullMarked;

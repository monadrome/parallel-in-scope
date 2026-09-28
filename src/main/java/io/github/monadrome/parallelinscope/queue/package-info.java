/**
 * Capacity-aware and lifecycle-aware blocking queue implementations for parallel task executors.
 *
 * <p>{@link DrainingBlockingQueue} is the lifecycle-aware
 * implementation: its one-way {@code OPEN → DRAINING → DRAINED} close permanently rejects
 * producers while allowing consumers and {@code drainTo} to remove every accepted element. Its
 * terminal signal and post-terminal mutation behavior are selected by {@link
 * ShutdownPolicy}; the queue's API
 * documentation defines the method-level blocking, exception, traversal, and concurrency contracts.
 * {@link VariableLinkedBlockingQueue} provides a
 * dynamically adjustable capacity without depending on the parallel execution kernel.
 */
@NullMarked
package io.github.monadrome.parallelinscope.queue;

import io.github.monadrome.parallelinscope.queue.DrainingBlockingQueue.ShutdownPolicy;
import org.jspecify.annotations.NullMarked;

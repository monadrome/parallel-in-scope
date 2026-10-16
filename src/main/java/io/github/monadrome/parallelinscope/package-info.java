/**
 * Structured parallel execution, task groups, cooperative cancellation, observation, and extension
 * callbacks.
 *
 * <p>The package intentionally contains both the public API and its package-private execution
 * kernel. Keeping them together lets Java 8 enforce the implementation boundary without exposing
 * bridge types solely for cross-package access.
 *
 * <p>Every public type carries {@code @since}. Tracking starts at 0.3.0, the release that rebuilt
 * this public API around synchronous execution and frozen results: {@code @since 0.3.0} on a type
 * means it is part of the 0.3.0 surface, not that it first appeared then. A member or type added
 * after 0.3.0 carries its own {@code @since} with the release that adds it.
 *
 * @since 0.3.0
 */
@NullMarked
package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.NullMarked;

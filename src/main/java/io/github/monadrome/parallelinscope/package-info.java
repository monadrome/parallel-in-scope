/**
 * Structured parallel execution, task groups, cooperative cancellation, observation, and extension
 * callbacks.
 *
 * <p>The package intentionally contains both the public API and its package-private execution
 * kernel. Keeping them together lets Java 8 enforce the implementation boundary without exposing
 * bridge types solely for cross-package access.
 */
@NullMarked
package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.NullMarked;

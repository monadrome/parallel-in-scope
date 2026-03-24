package io.github.huatalk.vformation.internal;

/**
 * Future execution state, analogous to {@code java.util.concurrent.Future.State} (Java 19+).
 * <p>
 * Provides Java 8-compatible state inspection for futures.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public enum FutureState {
    /** Task is still executing */
    RUNNING,
    /** Task completed successfully */
    SUCCESS,
    /** Task completed with an exception */
    FAILED,
    /** Task was cancelled */
    CANCELLED
}

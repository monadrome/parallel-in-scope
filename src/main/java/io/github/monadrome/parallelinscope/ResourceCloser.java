package io.github.monadrome.parallelinscope;

/**
 * Releases a value produced by an adopted future that the group never handed out.
 *
 * <p>Every non-null value an adopted future produces has exactly one owner. The terminal combine
 * owns the adopted values once its body is entered; a group without a combine hands them to the
 * caller of {@code runAll()} when it succeeds. A member declared with a closer through {@link
 * GroupStart#adopt(TaskKey, com.google.common.util.concurrent.ListenableFuture, ResourceCloser)}
 * passes every other value it produced to the closer exactly once: the value of a group that
 * failed, timed out, or was cancelled, and a value that arrived after the group had already
 * cancelled the future. A value handed to an owner is never passed here, and the closer is never
 * called with {@code null}.
 *
 * <p>The closer runs on the thread that settles the adopted future or converges the group, never
 * before the group's outcome is decided. It should not block; dispatch to an executor of your own
 * when releasing can wait. {@code runAll()} waits for pending closers only within its bounded
 * cleanup wait: {@link TaskGroupResult#bodyCompletionConfirmed()} is {@code true} only after every
 * adopted future settled and every discarded value was closed. An exception thrown here is logged at
 * {@code WARNING} and never changes an outcome; an {@link InterruptedException} also restores the
 * interrupt flag of the thread that ran the closer.
 *
 * @param <T> the adopted value type
 * @since 0.4.0
 */
@FunctionalInterface
public interface ResourceCloser<T> {

    /**
     * Releases a value that no owner received.
     *
     * @param value the discarded value; never null
     * @throws Exception any release failure, which is logged and otherwise ignored
     */
    void close(T value) throws Exception;
}

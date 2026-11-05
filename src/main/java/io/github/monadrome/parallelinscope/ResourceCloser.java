package io.github.monadrome.parallelinscope;

/**
 * Releases a value produced by an adopted future that the group never handed out.
 *
 * <p>Every non-null value an adopted future produces has exactly one owner. The terminal combine
 * owns the adopted values once its execution is entered; a group without a combine hands them to the
 * caller of {@code runAll()} when it succeeds. A member declared with a closer through {@link
 * GroupStart#adopt(TaskKey, com.google.common.util.concurrent.ListenableFuture, ResourceCloser)}
 * passes every other value it produced to the closer exactly once: the value of a group that
 * failed, timed out, or was cancelled, and a value that arrived after the group had already
 * cancelled the future. A value handed to an owner is never passed here, and the closer is never
 * called with {@code null}.
 *
 * <p>The frozen {@link TaskGroupResult} still reports a discarded member's outcome and value, so a
 * failed group can be diagnosed; the value is the closer's to release, so do not use it after
 * {@code runAll()} returns. Likewise the member values of a group with a combine belong to the
 * combine.
 *
 * <p>The closer runs only after the group's outcome is decided, on a runtime-owned thread: not on
 * the thread calling {@code runAll()}, and not on the thread that delivers deadlines. The one
 * exception is a runtime that has shut down its services: the release then runs on the thread that
 * hands it over — the thread that settles a late value, or the caller of a {@code runAll()} that
 * fails because its runtime is closed — and that path makes no bounded-return promise. A closer
 * may block, but it then holds one runtime thread; dispatch to an
 * executor of your own when releasing can wait long. {@code runAll()} waits for pending closers
 * only within its bounded cleanup wait: {@link TaskGroupResult#executionExitConfirmed()} is {@code
 * true} only after every adopted future settled and every discarded value's closer finished
 * running. An exception
 * thrown here is logged at {@code WARNING} and never changes an outcome; an {@link
 * InterruptedException} also restores the interrupt flag of the thread that ran the closer.
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

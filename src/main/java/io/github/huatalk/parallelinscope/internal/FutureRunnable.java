package io.github.huatalk.parallelinscope.internal;

import com.google.common.util.concurrent.ForwardingListenableFuture.SimpleForwardingListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single listenable future and queued runnable with cancellation-aware execution state.
 *
 * @param <V> result type
 */
final class FutureRunnable<V> extends SimpleForwardingListenableFuture<V> implements Runnable {

    private static final Runnable NOOP = () -> { };

    private enum Phase {
        SUBMITTED,
        RUNNING,
        CANCELLED_BEFORE_RUN,
        CANCEL_REQUESTED_RUNNING,
        TERMINAL
    }

    private final ListenableFutureTask<V> task;
    private volatile Runnable queuedCancellationObserver;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SUBMITTED);

    /** Creates a future runnable for a callable. */
    static <V> FutureRunnable<V> create(Callable<V> callable, Runnable queuedCancellationObserver) {
        return new FutureRunnable<>(ListenableFutureTask.create(callable), queuedCancellationObserver);
    }

    /** Creates a future runnable for a runnable and fixed result. */
    static <V> FutureRunnable<V> create(
            Runnable runnable, V result, Runnable queuedCancellationObserver) {
        return new FutureRunnable<>(
                ListenableFutureTask.create(runnable, result), queuedCancellationObserver);
    }

    /** Wraps Guava's future semantics with task-local lifecycle state. */
    private FutureRunnable(ListenableFutureTask<V> task, Runnable queuedCancellationObserver) {
        super(task);
        this.task = task;
        this.queuedCancellationObserver = Objects.requireNonNull(queuedCancellationObserver);
    }

    /** Runs the delegate only after claiming the transition out of the submitted state. */
    @Override
    public void run() {
        if (!phase.compareAndSet(Phase.SUBMITTED, Phase.RUNNING)) {
            return;
        }
        queuedCancellationObserver = NOOP;
        try {
            task.run();
        } finally {
            phase.set(Phase.TERMINAL);
        }
    }

    /** Classifies successful cancellation using the same state raced by {@link #run()}. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!task.cancel(mayInterruptIfRunning)) {
            return false;
        }
        while (true) {
            Phase current = phase.get();
            if (current == Phase.SUBMITTED) {
                if (phase.compareAndSet(Phase.SUBMITTED, Phase.CANCELLED_BEFORE_RUN)) {
                    Runnable observer = queuedCancellationObserver;
                    queuedCancellationObserver = NOOP;
                    observer.run();
                    return true;
                }
            } else if (current == Phase.RUNNING) {
                if (phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED_RUNNING)) {
                    queuedCancellationObserver = NOOP;
                    return true;
                }
            } else {
                queuedCancellationObserver = NOOP;
                return true;
            }
        }
    }
}

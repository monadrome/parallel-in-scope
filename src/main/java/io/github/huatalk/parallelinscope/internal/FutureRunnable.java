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

    private enum Phase {
        SUBMITTED,
        RUNNING,
        CANCELLED_BEFORE_RUN,
        CANCEL_REQUESTED_RUNNING,
        TERMINAL
    }

    private final ListenableFutureTask<V> task;
    private final PurgeContext purgeContext;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SUBMITTED);

    /** Creates a future runnable for a callable. */
    static <V> FutureRunnable<V> create(Callable<V> callable, PurgeContext purgeContext) {
        return new FutureRunnable<>(ListenableFutureTask.create(callable), purgeContext);
    }

    /** Creates a future runnable for a runnable and fixed result. */
    static <V> FutureRunnable<V> create(Runnable runnable, V result, PurgeContext purgeContext) {
        return new FutureRunnable<>(ListenableFutureTask.create(runnable, result), purgeContext);
    }

    /** Wraps Guava's future semantics with task-local lifecycle state. */
    private FutureRunnable(ListenableFutureTask<V> task, PurgeContext purgeContext) {
        super(task);
        this.task = task;
        this.purgeContext = Objects.requireNonNull(purgeContext);
    }

    /** Runs the delegate only after claiming the transition out of the submitted state. */
    @Override
    public void run() {
        if (!phase.compareAndSet(Phase.SUBMITTED, Phase.RUNNING)) {
            return;
        }
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
                    purgeContext.onPossiblyQueuedCancellation();
                    return true;
                }
            } else if (current == Phase.RUNNING) {
                if (phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED_RUNNING)) {
                    return true;
                }
            } else {
                return true;
            }
        }
    }
}

package io.github.huatalk.parallelinscope.internal;

/** Receives advisory signals for submitted tasks cancelled before their run method starts. */
@FunctionalInterface
public interface PurgeContext {

    /** Shared no-op context for executors that do not support heuristic purge. */
    PurgeContext NOOP = () -> { };

    /** Records one task that may still be retained by its executor queue. */
    void onPossiblyQueuedCancellation();
}

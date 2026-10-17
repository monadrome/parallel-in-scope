package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import org.jspecify.annotations.Nullable;

/**
 * The recording side of one observation scope's task graph.
 *
 * <p>Admissions hand in whole fragments through {@link #record}; queries read an immutable {@link
 * Snapshot}. One lock guards both the accumulating builder and the cached snapshot: a write merges
 * its fragment and drops the cache, so the next query derives the task graph and its executor
 * projection from one consistent read of every fragment recorded before it, and never from half of
 * one. Between writes the same snapshot is reused. Nothing under the lock runs user code: merging and
 * projecting are this library's own pure computations.
 *
 * <p>Instances are created and owned by {@code TaskGraphObservationScope}. The class is not final
 * only so tests can substitute a snapshot that fails or blocks.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
class TaskGraphData {

    private final Object lock = new Object();
    private final TaskGraph.Builder recorded = TaskGraph.builder();
    private @Nullable Snapshot cachedSnapshot;

    /** Creates an empty request-scoped graph. */
    TaskGraphData() {}

    /** Merges one admission's fragment whole: a later snapshot holds all of it or, before, none. */
    void record(TaskGraph fragment) {
        checkNotNull(fragment, "fragment cannot be null");
        synchronized (lock) {
            recorded.putAll(fragment);
            cachedSnapshot = null;
        }
    }

    /**
     * Returns the derived view covering every fragment recorded before this call; built once per
     * version of the recorded graph and reused until the next {@link #record}.
     */
    Snapshot snapshot() {
        synchronized (lock) {
            if (cachedSnapshot == null) {
                cachedSnapshot = new Snapshot(recorded.build());
            }
            return cachedSnapshot;
        }
    }

    /** The task graph and its executor projection, both from the same read of the recorded graph. */
    static final class Snapshot {
        private final TaskGraph tasks;
        private final ExecutorGraph executors;

        Snapshot(TaskGraph tasks) {
            this.tasks = tasks;
            this.executors = ExecutorGraph.projectedFrom(tasks);
        }

        TaskGraph tasks() {
            return tasks;
        }

        ExecutorGraph executors() {
            return executors;
        }
    }
}

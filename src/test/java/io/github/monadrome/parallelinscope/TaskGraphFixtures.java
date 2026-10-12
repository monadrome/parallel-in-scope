package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hand-built inputs for graph tests: nodes on named pools, with no runtime, scope, or thread ever
 * involved. A node named for id {@code a} renders as {@code task-a[a]}.
 */
final class TaskGraphFixtures {

    private TaskGraphFixtures() {}

    /** A node with no executor: it belongs to task graphs but never to the executor projection. */
    static TaskNode node(String id) {
        return new TaskNode(id, "task-" + id, null, null, false, TaskType.IO_BOUND, 1, 1, Long.MAX_VALUE);
    }

    /** A pool whose queue parks a submission behind busy workers, so a blocked body can starve it. */
    static Pool pronePool(String label) {
        return new Pool(new ExecutorIdentity(new IdleExecutor(label)), label, true);
    }

    /** A pool that never parks a submission behind a busy worker. */
    static Pool safePool(String label) {
        return new Pool(new ExecutorIdentity(new IdleExecutor(label)), label, false);
    }

    /** One executor resource as nodes record it: identity, display label, and starvation fact. */
    static final class Pool {
        final ExecutorIdentity identity;
        final String label;
        final boolean starvationProne;

        private Pool(ExecutorIdentity identity, String label, boolean starvationProne) {
            this.identity = identity;
            this.label = label;
            this.starvationProne = starvationProne;
        }

        /** The same physical pool registered under another id. */
        Pool registeredAs(String otherLabel) {
            return new Pool(identity, checkNotNull(otherLabel), starvationProne);
        }

        /** A node for unit {@code id} running on this pool. */
        TaskNode node(String id) {
            return new TaskNode(id, "task-" + id, identity, label, starvationProne, TaskType.CPU_BOUND, 1, 1, 0L);
        }
    }

    /** An executor that is never submitted to; only its reference identity matters. */
    private static final class IdleExecutor extends AbstractExecutorService {
        private final String name;

        private IdleExecutor(String name) {
            this.name = name;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException(name);
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}

package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
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

    /**
     * Two starvation-prone pools waiting on each other: {@code a} on {@code pool-a} forks {@code b} on
     * {@code pool-b}, which forks {@code c} back on {@code pool-a}. Its report renders {@code pool-a ->
     * pool-b}, {@code pool-b -> pool-a}, and {@code task-a[a] -> task-b[b]}.
     */
    static TaskGraph twoPoolCycle() {
        Pool first = pronePool("pool-a");
        Pool second = pronePool("pool-b");
        return TaskGraph.builder()
                .admit(first.node("a"), second.node("b"))
                .admit(second.node("b"), first.node("c"))
                .build();
    }

    /** The one node called {@code name} in {@code graph}. */
    static TaskNode named(TaskGraph graph, String name) {
        List<TaskNode> matches = graph.asValueGraph().nodes().stream()
                .filter(node -> node.name().equals(name))
                .collect(ImmutableList.toImmutableList());
        checkState(matches.size() == 1, "expected one node named '%s' but found %s", name, matches);
        return matches.get(0);
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

        /** A node for unit {@code id} running on this pool, resolved with no budget left. */
        TaskNode node(String id) {
            return new TaskNode(id, "task-" + id, identity, label, starvationProne, TaskType.CPU_BOUND, 1, 1, 0L);
        }

        /** A node for unit {@code id} running on this pool, resolved with no deadline at all. */
        TaskNode unboundedNode(String id) {
            return new TaskNode(
                    id, "task-" + id, identity, label, starvationProne, TaskType.CPU_BOUND, 1, 1, Long.MAX_VALUE);
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
            throw new UnsupportedOperationException("pool '" + name + "' is a graph fixture and never runs tasks");
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

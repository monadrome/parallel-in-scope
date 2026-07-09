package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Edge order inside one executor pair, for both keyings of the executor projection.
 *
 * <p>The label-keyed projection groups by task node pair and the identity-keyed one follows
 * recording order. Within a single executor pair those two orders can differ, and the label graph is
 * what the detection report renders — so the order is observable output, not an implementation
 * detail. These cases pin it directly, because the existing graph tests assert which edges exist
 * rather than the sequence they appear in.
 */
class TaskGraphProjectionOrderTest {

    private static TaskEdge edge(ExecutorIdentity source, ExecutorIdentity target, int taskCount) {
        return new TaskEdge(
                1, TaskType.CPU_BOUND, target, source, "target-pool", "source-pool", taskCount, Duration.ZERO, true);
    }

    private static ExecutorIdentity identity(String name) {
        return new ExecutorIdentity(new NamedExecutor(name));
    }

    @Test
    void theLabelKeyedProjectionFollowsTaskGraphGroupingNotRecordingOrder() {
        ExecutorIdentity source = identity("source");
        ExecutorIdentity target = identity("target");
        TaskGraphData data = new TaskGraphData();

        // Interleave two task pairs so recording order and task-pair grouping disagree: recording is
        // a1, b1, a2, while grouping by task pair is a1, a2, then b1.
        data.logTaskPair("pa", "pa", "ca", "ca", edge(source, target, 1));
        data.logTaskPair("pb", "pb", "cb", "cb", edge(source, target, 2));
        data.logTaskPair("pa", "pa", "ca", "ca", edge(source, target, 3));

        ValueGraph<String, List<TaskEdge>> labelGraph = data.executorGraph();
        assertThat(labelGraph.edges()).hasSize(1);
        assertThat(taskCounts(labelGraph)).containsExactly(1, 3, 2);

        // The identity keying reads the recording order instead. Both contain the same three edges.
        assertThat(taskCounts(data.snapshot().executorIdentityGraph())).containsExactly(1, 2, 3);
    }

    @Test
    void anEdgeMissingEitherEndpointKeyIsDroppedByThatKeyingOnly() {
        ExecutorIdentity source = identity("source");
        ExecutorIdentity target = identity("target");
        TaskGraphData data = new TaskGraphData();

        // Identities present, names absent: the identity keying places it, the label keying cannot.
        data.logTaskPair(
                "p",
                "p",
                "c",
                "c",
                new TaskEdge(1, TaskType.CPU_BOUND, target, source, null, null, 1, Duration.ZERO, true));
        // Names present, identities absent: the reverse.
        data.logTaskPair("p", "p", "c", "c", new TaskEdge(1, TaskType.CPU_BOUND, "t", "s", 1, Duration.ZERO, true));

        assertThat(data.snapshot().executorIdentityGraph().edges()).hasSize(1);
        assertThat(data.executorGraph().edges()).hasSize(1);
    }

    @Test
    void anEdgeThatCannotDeadlockIsDroppedByBothKeyings() {
        ExecutorIdentity pool = identity("pool");
        TaskGraphData data = new TaskGraphData();
        data.logTaskPair(
                "p",
                "p",
                "c",
                "c",
                new TaskEdge(1, TaskType.CPU_BOUND, pool, pool, "pool", "pool", 1, Duration.ZERO, false));

        assertThat(data.executorGraph().edges()).isEmpty();
        assertThat(data.snapshot().executorIdentityGraph().edges()).isEmpty();
        assertThat(data.executorSelfLoop()).isFalse();
    }

    private static <N> List<Integer> taskCounts(ValueGraph<N, List<TaskEdge>> graph) {
        List<Integer> counts = new ArrayList<>();
        for (EndpointPair<N> pair : graph.edges()) {
            for (TaskEdge edge : Objects.requireNonNull(
                    graph.edgeValueOrDefault(pair.source(), pair.target(), java.util.Collections.emptyList()))) {
                counts.add(edge.taskCount());
            }
        }
        return counts;
    }

    /** An executor that is never submitted to; only its identity matters here. */
    private static final class NamedExecutor extends java.util.concurrent.AbstractExecutorService {
        private final String name;

        private NamedExecutor(String name) {
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
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    }
}

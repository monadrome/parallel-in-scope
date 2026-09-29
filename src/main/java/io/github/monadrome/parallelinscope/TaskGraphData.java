package io.github.monadrome.parallelinscope;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Task dependency graph data for potential deadlock detection.
 *
 * <p>Records the parent-child task relationships of a single request as ordered edges with
 * {@link TaskEdge} metadata (parallelism, task type, executor name, task count, timeout), and
 * derives the task, executor-name, and executor-identity {@link ValueGraph} views from them. One
 * instance is shared by every thread within the request.
 *
 * <p>Instances are created and owned by {@code TaskGraphObservationScope}. Recorded edges, display
 * labels, and the derived views share one lock: {@link #logTaskPair} appends the edge and drops the
 * cached {@link Snapshot}, so the next query derives every graph, flag, and label from one
 * consistent read of the edges recorded before it. Between writes the same immutable snapshot is
 * reused.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
class TaskGraphData {

    private final Object lock = new Object();
    private final List<TaskEdgeEntry> taskEdges = new ArrayList<>();
    private final Map<String, String> nodeLabels = new HashMap<>();
    private @Nullable Snapshot cachedSnapshot;

    /** Creates an empty request-scoped graph. */
    public TaskGraphData() {}

    /**
     * Returns the task dependency graph over the edges recorded so far.
     *
     * @return the task dependency graph
     */
    public ValueGraph<String, List<TaskEdge>> graph() {
        return snapshot().graph();
    }

    /**
     * Checks whether the task graph recorded so far contains a cycle.
     *
     * @return {@code true} when a task cycle exists
     */
    public boolean taskCycle() {
        return snapshot().taskCycle();
    }

    /**
     * Checks whether the task graph recorded so far contains a self-loop.
     *
     * @return {@code true} when a task self-loop exists
     */
    public boolean selfLoop() {
        return snapshot().taskSelfLoop();
    }

    /**
     * Returns the executor graph built from deadlock-risk snapshots on task edges, keyed by display
     * label for export and detection-event rendering.
     *
     * @return executor dependency graph
     */
    public ValueGraph<String, List<TaskEdge>> executorGraph() {
        return snapshot().executorGraph();
    }

    /**
     * Returns whether the executor graph recorded so far contains a cycle.
     *
     * @return {@code true} when an executor cycle exists
     */
    public boolean executorCycle() {
        return snapshot().executorCycle();
    }

    /**
     * Returns whether the executor graph recorded so far contains a self-loop.
     *
     * @return {@code true} when an executor self-loop exists
     */
    public boolean executorSelfLoop() {
        return snapshot().executorSelfLoop();
    }

    /**
     * Returns the derived view covering every edge recorded before this call.
     *
     * <p>The snapshot is built once per version of the recorded edges and reused until
     * {@link #logTaskPair} records a new one, so repeated queries are cheap and mutually consistent.
     *
     * @return the current immutable snapshot
     */
    Snapshot snapshot() {
        synchronized (lock) {
            if (cachedSnapshot == null) {
                cachedSnapshot = Snapshot.create(taskEdges, nodeLabels);
            }
            return cachedSnapshot;
        }
    }

    /** Returns the node prefixed with its display label, or the bare node when unlabelled. */
    public String displayNode(String node) {
        return snapshot().displayNode(node);
    }

    /** Records one parent-to-child edge. Batch IDs, not reusable task names, keep nodes distinct. */
    public void logTaskPair(
            @Nullable String parentId,
            @Nullable String parentLabel,
            String childId,
            @Nullable String childLabel,
            TaskEdge edge) {
        String source = parentId == null ? "root" : parentId;
        synchronized (lock) {
            nodeLabels.put(source, parentLabel == null ? "NA" : parentLabel);
            nodeLabels.put(childId, childLabel == null ? "NA" : childLabel);
            taskEdges.add(new TaskEdgeEntry(EndpointPair.ordered(source, childId), edge));
            cachedSnapshot = null;
        }
    }

    /**
     * One immutable derived view over the edges recorded up to a single point in time.
     *
     * <p>Every graph, flag, and label is computed from the same copied edge list, so a caller
     * holding a snapshot reads a self-consistent picture even while other threads keep recording
     * edges. Nothing here reads {@link TaskGraphData} state again.
     */
    static final class Snapshot {

        private final ValueGraph<String, List<TaskEdge>> taskGraph;
        private final ValueGraph<String, List<TaskEdge>> executorGraph;
        private final ValueGraph<ExecutorIdentity, List<TaskEdge>> executorIdentityGraph;
        private final Map<String, String> nodeLabels;
        private final boolean taskCycle;
        private final boolean taskSelfLoop;
        private final boolean executorCycle;
        private final boolean executorSelfLoop;

        private Snapshot(
                ValueGraph<String, List<TaskEdge>> taskGraph,
                ValueGraph<String, List<TaskEdge>> executorGraph,
                ValueGraph<ExecutorIdentity, List<TaskEdge>> executorIdentityGraph,
                Map<String, String> nodeLabels) {
            this.taskGraph = taskGraph;
            this.executorGraph = executorGraph;
            this.executorIdentityGraph = executorIdentityGraph;
            this.nodeLabels = nodeLabels;
            this.taskCycle = Graphs.hasCycle(taskGraph.asGraph());
            this.taskSelfLoop = hasSelfLoop(taskGraph);
            // Edges recorded without an executor identity fall back to the label-keyed graph.
            this.executorCycle = executorIdentityGraph.nodes().isEmpty()
                    ? Graphs.hasCycle(executorGraph.asGraph())
                    : Graphs.hasCycle(executorIdentityGraph.asGraph());
            this.executorSelfLoop = executorIdentityGraph.nodes().isEmpty()
                    ? hasSelfLoop(executorGraph)
                    : hasSelfLoop(executorIdentityGraph);
        }

        /** Derives a snapshot from a copy of the given edges and labels. */
        static Snapshot create(List<TaskEdgeEntry> taskEdges, Map<String, String> nodeLabels) {
            List<TaskEdgeEntry> edges = ImmutableList.copyOf(taskEdges);
            ValueGraph<String, List<TaskEdge>> taskGraph = buildTaskGraph(edges);
            ImmutableList.Builder<TaskEdge> recorded = ImmutableList.builder();
            for (TaskEdgeEntry entry : edges) {
                recorded.add(entry.value());
            }
            return new Snapshot(
                    taskGraph,
                    projectOntoExecutors(
                            edgesInTaskGraphOrder(taskGraph), TaskEdge::sourceExecutorName, TaskEdge::executorName),
                    projectOntoExecutors(
                            recorded.build(), TaskEdge::sourceExecutorIdentity, TaskEdge::executorIdentity),
                    ImmutableMap.copyOf(nodeLabels));
        }

        ValueGraph<String, List<TaskEdge>> graph() {
            return taskGraph;
        }

        ValueGraph<String, List<TaskEdge>> executorGraph() {
            return executorGraph;
        }

        ValueGraph<ExecutorIdentity, List<TaskEdge>> executorIdentityGraph() {
            return executorIdentityGraph;
        }

        Map<String, String> nodeLabels() {
            return nodeLabels;
        }

        boolean taskCycle() {
            return taskCycle;
        }

        boolean taskSelfLoop() {
            return taskSelfLoop;
        }

        boolean executorCycle() {
            return executorCycle;
        }

        boolean executorSelfLoop() {
            return executorSelfLoop;
        }

        /** Returns the node prefixed with its display label, or the bare node when unlabelled. */
        String displayNode(String node) {
            String label = nodeLabels.get(node);
            return label == null ? node : label + "[" + node + "]";
        }

        private static ValueGraph<String, List<TaskEdge>> buildTaskGraph(List<TaskEdgeEntry> edges) {
            ListMultimap<EndpointPair<String>, TaskEdge> edgeMap = LinkedListMultimap.create();
            for (TaskEdgeEntry entry : edges) {
                edgeMap.put(entry.edge(), entry.value());
            }

            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder =
                    ValueGraphBuilder.directed().allowsSelfLoops(true).immutable();
            for (Map.Entry<EndpointPair<String>, Collection<TaskEdge>> entry :
                    edgeMap.asMap().entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(), entry.getKey().target(), ImmutableList.copyOf(entry.getValue()));
            }
            return graphBuilder.build();
        }

        /**
         * Projects the recorded edges onto executor nodes under one keying.
         *
         * <p>The two keyings answer the same question about different node identities. The identity
         * graph is the one detection uses, because reference identity is what makes two references
         * the same pool; the label graph is the fallback for edges recorded without identities, and
         * what {@code TaskGraphObservationScope} renders. Both drop the same two classes of edge: one
         * whose child executor cannot deadlock on nested work, and one missing either endpoint key,
         * which cannot be placed under that keying at all.
         *
         * @param edges the recorded edges, in the iteration order the caller wants preserved within
         *     each node pair
         * @param sourceKey the parent endpoint's key under this keying, or null when absent
         * @param targetKey the child endpoint's key under this keying, or null when absent
         */
        private static <N> ValueGraph<N, List<TaskEdge>> projectOntoExecutors(
                Iterable<TaskEdge> edges,
                Function<TaskEdge, @Nullable N> sourceKey,
                Function<TaskEdge, @Nullable N> targetKey) {
            ListMultimap<EndpointPair<N>, TaskEdge> executorEdges = LinkedListMultimap.create();
            for (TaskEdge edge : edges) {
                N source = sourceKey.apply(edge);
                N target = targetKey.apply(edge);
                if (!edge.executorDeadlockProne() || source == null || target == null) {
                    continue;
                }
                executorEdges.put(EndpointPair.ordered(source, target), edge);
            }

            ImmutableValueGraph.Builder<N, List<TaskEdge>> builder = ValueGraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .incidentEdgeOrder(ElementOrder.stable())
                    .immutable();
            for (Map.Entry<EndpointPair<N>, Collection<TaskEdge>> entry :
                    executorEdges.asMap().entrySet()) {
                builder.putEdgeValue(
                        entry.getKey().source(), entry.getKey().target(), ImmutableList.copyOf(entry.getValue()));
            }
            return builder.build();
        }

        /**
         * The recorded edges in task-graph order: grouped by task node pair, as the label-keyed
         * projection has always read them. The identity projection reads the recording order instead,
         * and the two orders differ only within one executor pair's edge list.
         */
        private static List<TaskEdge> edgesInTaskGraphOrder(ValueGraph<String, List<TaskEdge>> taskGraph) {
            ImmutableList.Builder<TaskEdge> ordered = ImmutableList.builder();
            for (EndpointPair<String> pair : taskGraph.edges()) {
                ordered.addAll(Objects.requireNonNull(
                        taskGraph.edgeValueOrDefault(pair.source(), pair.target(), ImmutableList.of())));
            }
            return ordered.build();
        }

        private static <N> boolean hasSelfLoop(ValueGraph<N, List<TaskEdge>> graph) {
            return graph.edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }
    }
}

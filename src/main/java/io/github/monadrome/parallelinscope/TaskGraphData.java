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
import org.checkerframework.checker.nullness.qual.Nullable;

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
final class TaskGraphData {

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
            return new Snapshot(
                    taskGraph,
                    buildExecutorGraph(taskGraph),
                    buildExecutorIdentityGraph(edges),
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
         * Builds the label-keyed executor graph. Edges without both endpoint names cannot be keyed
         * by name and are skipped, matching how the identity graph skips edges without identities.
         */
        private static ValueGraph<String, List<TaskEdge>> buildExecutorGraph(
                ValueGraph<String, List<TaskEdge>> taskGraph) {
            ListMultimap<EndpointPair<String>, TaskEdge> executorEdges = LinkedListMultimap.create();
            for (EndpointPair<String> taskEdgePair : taskGraph.edges()) {
                List<TaskEdge> edges = Objects.requireNonNull(
                        taskGraph.edgeValueOrDefault(taskEdgePair.source(), taskEdgePair.target(), ImmutableList.of()));
                for (TaskEdge taskEdge : edges) {
                    if (!taskEdge.executorDeadlockProne()
                            || taskEdge.executorName() == null
                            || taskEdge.sourceExecutorName() == null) {
                        continue;
                    }
                    EndpointPair<String> executorPair =
                            EndpointPair.ordered(taskEdge.sourceExecutorName(), taskEdge.executorName());
                    executorEdges.put(executorPair, taskEdge);
                }
            }

            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder = ValueGraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .incidentEdgeOrder(ElementOrder.stable())
                    .immutable();
            for (Map.Entry<EndpointPair<String>, Collection<TaskEdge>> entry :
                    executorEdges.asMap().entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(), entry.getKey().target(), ImmutableList.copyOf(entry.getValue()));
            }
            return graphBuilder.build();
        }

        private static ValueGraph<ExecutorIdentity, List<TaskEdge>> buildExecutorIdentityGraph(
                List<TaskEdgeEntry> edges) {
            ListMultimap<EndpointPair<ExecutorIdentity>, TaskEdge> executorEdges = LinkedListMultimap.create();
            for (TaskEdgeEntry entry : edges) {
                TaskEdge edge = entry.value();
                if (!edge.executorDeadlockProne()
                        || edge.executorIdentity() == null
                        || edge.sourceExecutorIdentity() == null) {
                    continue;
                }
                executorEdges.put(EndpointPair.ordered(edge.sourceExecutorIdentity(), edge.executorIdentity()), edge);
            }

            ImmutableValueGraph.Builder<ExecutorIdentity, List<TaskEdge>> builder = ValueGraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .incidentEdgeOrder(ElementOrder.stable())
                    .immutable();
            for (Map.Entry<EndpointPair<ExecutorIdentity>, Collection<TaskEdge>> entry :
                    executorEdges.asMap().entrySet()) {
                builder.putEdgeValue(
                        entry.getKey().source(), entry.getKey().target(), ImmutableList.copyOf(entry.getValue()));
            }
            return builder.build();
        }

        private static <N> boolean hasSelfLoop(ValueGraph<N, List<TaskEdge>> graph) {
            return graph.edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }
    }
}

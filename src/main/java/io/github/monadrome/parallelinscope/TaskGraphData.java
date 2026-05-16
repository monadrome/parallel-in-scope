package io.github.monadrome.parallelinscope;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Task dependency graph data for potential deadlock detection.
 *
 * <p>Holds a directed {@link ValueGraph} of task dependencies shared by all threads within a
 * single request. Records parent-child task relationships as edges with {@link TaskEdge} metadata
 * (parallelism, task type, executor name, task count, timeout).
 *
 * <p>Instances are created and owned by {@code TaskGraphObservationScope}; at request end the
 * context builds directed graphs at both task level and executor level, checking for cycles
 * (potential deadlocks) and self-loops.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
final class TaskGraphData {

    final BlockingQueue<TaskEdgeEntry> subTaskList = new LinkedTransferQueue<>();
    private final Map<String, String> nodeLabels = new ConcurrentHashMap<>();

    private final Supplier<ValueGraph<String, List<TaskEdge>>> graph = Suppliers.memoize(this::generateGraph);
    private final Supplier<ValueGraph<String, List<TaskEdge>>> executorGraph =
            Suppliers.memoize(this::generateExecutorGraph);
    private final Supplier<ValueGraph<ExecutorIdentity, List<TaskEdge>>> executorIdentityGraph =
            Suppliers.memoize(this::generateExecutorIdentityGraph);
    private final Supplier<Boolean> taskCycle = Suppliers.memoize(this::checkTaskCycle);
    private final Supplier<Boolean> selfLoop = Suppliers.memoize(this::checkSelfLoop);

    /** Creates an empty request-scoped graph. */
    public TaskGraphData() {}

    /**
     * Returns the task dependency graph.
     *
     * @return the task dependency graph
     */
    public ValueGraph<String, List<TaskEdge>> graph() {
        return graph.get();
    }

    /**
     * Checks whether the task graph contains a cycle.
     *
     * @return {@code true} when a task cycle exists
     */
    public boolean taskCycle() {
        return taskCycle.get();
    }

    /**
     * Checks whether the task graph contains a self-loop.
     *
     * @return {@code true} when a task self-loop exists
     */
    public boolean selfLoop() {
        return selfLoop.get();
    }

    /**
     * Returns the memoized executor graph built from deadlock-risk snapshots on task edges, keyed
     * by display label for export and detection-event rendering.
     *
     * @return executor dependency graph
     */
    public ValueGraph<String, List<TaskEdge>> executorGraph() {
        return executorGraph.get();
    }

    /**
     * Returns whether the captured executor graph contains a cycle.
     *
     * @return {@code true} when an executor cycle exists
     */
    public boolean executorCycle() {
        ValueGraph<ExecutorIdentity, List<TaskEdge>> g = executorIdentityGraph();
        if (!g.nodes().isEmpty()) {
            return Graphs.hasCycle(g.asGraph());
        }
        // Edges recorded without an executor identity fall back to the label-keyed graph.
        return Graphs.hasCycle(executorGraph().asGraph());
    }

    /**
     * Returns whether the captured executor graph contains a self-loop.
     *
     * @return {@code true} when an executor self-loop exists
     */
    public boolean executorSelfLoop() {
        ValueGraph<ExecutorIdentity, List<TaskEdge>> identityGraph = executorIdentityGraph();
        if (!identityGraph.nodes().isEmpty()) {
            return identityGraph.edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }
        return executorGraph().edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
    }

    /** Returns the memoized identity-keyed executor graph used for cycle and self-loop detection. */
    private ValueGraph<ExecutorIdentity, List<TaskEdge>> executorIdentityGraph() {
        return executorIdentityGraph.get();
    }

    /** Records one parent-to-child edge. Batch IDs, not reusable task names, keep nodes distinct. */
    public void logTaskPair(
            @Nullable String parentId,
            @Nullable String parentLabel,
            String childId,
            @Nullable String childLabel,
            TaskEdge edge) {
        String source = parentId == null ? "root" : parentId;
        nodeLabels.put(source, parentLabel == null ? "NA" : parentLabel);
        nodeLabels.put(childId, childLabel == null ? "NA" : childLabel);
        subTaskList.add(new TaskEdgeEntry(EndpointPair.ordered(source, childId), edge));
    }

    ValueGraph<String, List<TaskEdge>> generateGraph() {
        ListMultimap<EndpointPair<String>, TaskEdge> edgeMap = LinkedListMultimap.create();
        for (TaskEdgeEntry entry : subTaskList) {
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

    /** Returns the node prefixed with its display label, or the bare node when unlabelled. */
    public String displayNode(String node) {
        String label = nodeLabels.get(node);
        return label == null ? node : label + "[" + node + "]";
    }

    boolean checkTaskCycle() {
        ValueGraph<String, List<TaskEdge>> g = graph();
        return g != null && Graphs.hasCycle(g.asGraph());
    }

    boolean checkSelfLoop() {
        return graph().edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
    }

    ValueGraph<String, List<TaskEdge>> generateExecutorGraph() {
        ListMultimap<EndpointPair<String>, TaskEdge> executorEdges = LinkedListMultimap.create();

        for (EndpointPair<String> taskEdgePair : graph().edges()) {
            List<TaskEdge> edges = Objects.requireNonNull(
                    graph().edgeValueOrDefault(taskEdgePair.source(), taskEdgePair.target(), ImmutableList.of()));
            for (TaskEdge taskEdge : edges) {
                String sourceExecutor = taskEdge.sourceExecutorName();
                String targetExecutor = taskEdge.executorName();
                if (!taskEdge.executorDeadlockProne()) {
                    continue;
                }
                EndpointPair<String> executorPair = EndpointPair.ordered(sourceExecutor, targetExecutor);
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

    private ValueGraph<ExecutorIdentity, List<TaskEdge>> generateExecutorIdentityGraph() {
        ListMultimap<EndpointPair<ExecutorIdentity>, TaskEdge> executorEdges = LinkedListMultimap.create();
        for (TaskEdgeEntry entry : subTaskList) {
            TaskEdge edge = entry.value();
            if (!edge.executorDeadlockProne()
                    || edge.executorIdentity() == null
                    || edge.sourceExecutorIdentity() == null) {
                continue;
            }
            EndpointPair<ExecutorIdentity> pair =
                    EndpointPair.ordered(edge.sourceExecutorIdentity(), edge.executorIdentity());
            executorEdges.put(pair, edge);
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
}

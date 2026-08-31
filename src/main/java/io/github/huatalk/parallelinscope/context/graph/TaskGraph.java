package io.github.huatalk.parallelinscope.context.graph;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import io.github.huatalk.parallelinscope.context.GlobalParObservationContext;
import io.github.huatalk.parallelinscope.scope.ExecutorIdentity;
import io.github.huatalk.parallelinscope.spi.LivelockListener;
import io.github.huatalk.parallelinscope.spi.LivelockListener.LivelockEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Task dependency graph for livelock/deadlock detection.
 *
 * <p>Uses a composed {@link TransmittableThreadLocal} to share a directed {@link ValueGraph} of
 * task dependencies across all threads within a single request. Records parent-child task
 * relationships as edges with {@link TaskEdge} metadata (parallelism, task type, executor name,
 * task count, timeout).
 *
 * <p>At request end, builds directed graphs at both task level and executor level, checking for
 * cycles (potential deadlocks) and self-loops.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>Request start: {@link GlobalParObservationContext} creates a new Data instance
 *   <li>During request: the GlobalPar execution path records batch-instance relationships
 *   <li>Request end: {@link #destroyAfterRequest(GlobalParObservationContext)} checks for cycles
 *       and notifies listeners
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
public final class TaskGraph {

    private static final Logger logger = Logger.getLogger(TaskGraph.class.getName());

    private static final TransmittableThreadLocal<Data> TTL = new TransmittableThreadLocal<Data>() {
        @Override
        protected Data initialValue() {
            return null;
        }

        @Override
        public Data copy(Data parentValue) {
            return parentValue;
        }
    };

    private TaskGraph() {}

    /** Task graph data (thread-safe, shared across all threads within a request). */
    public static class Data {
        final BlockingQueue<TaskEdgeEntry> subTaskList = new LinkedTransferQueue<>();
        private final Map<String, String> nodeLabels = new ConcurrentHashMap<>();

        private volatile ValueGraph<String, List<TaskEdge>> graph;
        private volatile Boolean taskCycle;
        private volatile Boolean selfLoop;

        /** Creates an empty request-scoped graph. */
        public Data() {}

        /**
         * Returns the task dependency graph.
         *
         * @return the task dependency graph
         */
        public ValueGraph<String, List<TaskEdge>> getGraph() {
            if (graph == null) {
                synchronized (this) {
                    if (graph == null) {
                        graph = generateGraph();
                    }
                }
            }
            return graph;
        }

        /**
         * Checks whether the task graph contains a cycle.
         *
         * @return {@code true} when a task cycle exists
         */
        public boolean isTaskCycle() {
            if (taskCycle == null) {
                taskCycle = checkTaskCycle();
            }
            return taskCycle;
        }

        /**
         * Checks whether the task graph contains a self-loop.
         *
         * @return {@code true} when a task self-loop exists
         */
        public boolean isSelfLoop() {
            if (selfLoop == null) {
                selfLoop = checkSelfLoop();
            }
            return selfLoop;
        }

        /**
         * Returns the executor graph built from deadlock-risk snapshots on task edges.
         *
         * @return executor dependency graph
         */
        public ValueGraph<String, List<TaskEdge>> getExecutorGraph() {
            return generateExecutorGraph();
        }

        /**
         * Returns whether the captured executor graph contains a cycle.
         *
         * @return {@code true} when an executor cycle exists
         */
        public boolean isExecutorCycle() {
            ValueGraph<ExecutorIdentity, List<TaskEdge>> g = generateExecutorIdentityGraph();
            if (g != null && !g.nodes().isEmpty()) {
                return Graphs.hasCycle(g.asGraph());
            }
            ValueGraph<String, List<TaskEdge>> legacy = getExecutorGraph();
            return legacy != null && Graphs.hasCycle(legacy.asGraph());
        }

        /**
         * Returns whether the captured executor graph contains a self-loop.
         *
         * @return {@code true} when an executor self-loop exists
         */
        public boolean isExecutorSelfLoop() {
            ValueGraph<ExecutorIdentity, List<TaskEdge>> identityGraph = generateExecutorIdentityGraph();
            if (identityGraph != null && !identityGraph.nodes().isEmpty()) {
                return identityGraph.edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
            }
            return getExecutorGraph().edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }

        ValueGraph<String, List<TaskEdge>> generateGraph() {
            Map<EndpointPair<String>, List<TaskEdge>> edgeMap = new LinkedHashMap<>();
            for (TaskEdgeEntry entry : subTaskList) {
                edgeMap.computeIfAbsent(entry.getEdge(), k -> new ArrayList<>()).add(entry.getValue());
            }
            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder =
                    ValueGraphBuilder.directed().allowsSelfLoops(true).immutable();
            for (Map.Entry<EndpointPair<String>, List<TaskEdge>> entry : edgeMap.entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(),
                        entry.getKey().target(),
                        Collections.unmodifiableList(entry.getValue()));
            }
            return graphBuilder.build();
        }

        String displayNode(String node) {
            String label = nodeLabels.get(node);
            return label == null ? node : label + "[" + node + "]";
        }

        boolean checkTaskCycle() {
            ValueGraph<String, List<TaskEdge>> g = getGraph();
            return g != null && Graphs.hasCycle(g.asGraph());
        }

        boolean checkSelfLoop() {
            return getGraph().edges().stream().anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }

        ValueGraph<String, List<TaskEdge>> generateExecutorGraph() {
            Map<EndpointPair<String>, List<TaskEdge>> executorEdges = new LinkedHashMap<>();

            for (EndpointPair<String> taskEdgePair : getGraph().edges()) {
                List<TaskEdge> edges = Objects.requireNonNull(getGraph()
                        .edgeValueOrDefault(
                                taskEdgePair.source(), taskEdgePair.target(), Collections.emptyList()));
                for (TaskEdge taskEdge : edges) {
                    String sourceExecutor = taskEdge.getSourceExecutorName();
                    String targetExecutor = taskEdge.getExecutorName();
                    if (!taskEdge.isExecutorDeadlockProne()) {
                        continue;
                    }
                    EndpointPair<String> executorPair = EndpointPair.ordered(sourceExecutor, targetExecutor);
                    executorEdges
                            .computeIfAbsent(executorPair, k -> new ArrayList<>())
                            .add(taskEdge);
                }
            }

            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder = ValueGraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .incidentEdgeOrder(ElementOrder.stable())
                    .immutable();
            for (Map.Entry<EndpointPair<String>, List<TaskEdge>> entry : executorEdges.entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(),
                        entry.getKey().target(),
                        Collections.unmodifiableList(entry.getValue()));
            }
            return graphBuilder.build();
        }

        private ValueGraph<ExecutorIdentity, List<TaskEdge>> generateExecutorIdentityGraph() {
            Map<EndpointPair<ExecutorIdentity>, List<TaskEdge>> executorEdges = new LinkedHashMap<>();
            for (TaskEdgeEntry entry : subTaskList) {
                TaskEdge edge = entry.getValue();
                if (!edge.isExecutorDeadlockProne()
                        || edge.getExecutorIdentity() == null
                        || edge.getSourceExecutorIdentity() == null) {
                    continue;
                }
                EndpointPair<ExecutorIdentity> pair =
                        EndpointPair.ordered(edge.getSourceExecutorIdentity(), edge.getExecutorIdentity());
                executorEdges.computeIfAbsent(pair, k -> new ArrayList<>()).add(edge);
            }
            ImmutableValueGraph.Builder<ExecutorIdentity, List<TaskEdge>> builder = ValueGraphBuilder.directed()
                    .allowsSelfLoops(true)
                    .incidentEdgeOrder(ElementOrder.stable())
                    .immutable();
            for (Map.Entry<EndpointPair<ExecutorIdentity>, List<TaskEdge>> entry : executorEdges.entrySet()) {
                builder.putEdgeValue(
                        entry.getKey().source(),
                        entry.getKey().target(),
                        Collections.unmodifiableList(entry.getValue()));
            }
            return builder.build();
        }
    }

    // ==================== Request lifecycle ====================

    /** Initializes a graph owned by one GlobalPar observation scope. */
    public static Data initOnRequest(GlobalParObservationContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        if (context.isClosed()) {
            throw new IllegalStateException("observation context is already closed");
        }
        Data previous = TTL.get();
        TTL.set(new Data());
        return previous;
    }

    /** Installs the graph owned by an observation scope on the current worker thread. */
    public static void install(GlobalParObservationContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        TTL.set(context.data());
    }

    /** Restores a graph captured before entering a scoped worker task. */
    public static void restore(@Nullable Data data) {
        if (data == null) TTL.remove();
        else TTL.set(data);
    }

    /** Destroys a GlobalPar-owned graph using the GlobalPar livelock policy. */
    public static void destroyAfterRequest(GlobalParObservationContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        try {
            Data data = TTL.get();
            if (data != null
                    && !data.subTaskList.isEmpty()
                    && context.owner().livelockPolicy().enabled()) {
                LivelockEvent event = buildDetectionEvent(data);
                if (event != null && event.hasAnyIssue()) {
                    logger.log(Level.WARNING, "[[title=TaskGraph,function=livelockDetection]]" + event);
                    for (LivelockListener listener :
                            context.owner().livelockPolicy().listeners()) {
                        try {
                            listener.onDetection(event);
                        } catch (Exception e) {
                            logger.log(
                                    Level.WARNING,
                                    "LivelockListener callback failed: "
                                            + listener.getClass().getName(),
                                    e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.log(
                    Level.WARNING,
                    "[[title=TaskGraph,function=destroyAfterRequest]]" + "Failed to run GlobalPar livelock detection",
                    e);
        } finally {
            if (context.previousData() == null) {
                TTL.remove();
            } else {
                TTL.set(context.previousData());
            }
            context.complete();
        }
    }

    private static LivelockEvent buildDetectionEvent(Data data) {
        boolean hasTaskCycle = data.isTaskCycle();
        boolean hasSelfLoop = data.isSelfLoop();
        boolean hasExecutorCycle = data.isExecutorCycle();
        boolean hasExecutorSelfLoop = data.isExecutorSelfLoop();

        if (!hasTaskCycle && !hasSelfLoop && !hasExecutorCycle && !hasExecutorSelfLoop) {
            return null;
        }

        String taskEdges = data.getGraph().edges().stream()
                .map(p -> {
                    List<TaskEdge> edges = data.getGraph()
                            .edgeValueOrDefault(p.source(), p.target(), Collections.emptyList());
                    return data.displayNode(p.source()) + " -> " + data.displayNode(p.target()) + " " + edges;
                })
                .collect(Collectors.joining(", "));
        String executorEdges = data.getExecutorGraph().edges().stream()
                .map(p -> {
                    List<TaskEdge> edges =
                            data.getExecutorGraph().edgeValueOrDefault(p.source(), p.target(), Collections.emptyList());
                    return p.source() + " -> " + p.target() + " " + edges;
                })
                .collect(Collectors.joining(", "));

        return new LivelockEvent(
                hasTaskCycle, hasSelfLoop,
                hasExecutorCycle, hasExecutorSelfLoop,
                taskEdges, executorEdges);
    }

    // ==================== Data access ====================

    /**
     * Gets the current request's Data.
     *
     * @return the current request data, or {@code null} outside a graph lifecycle
     */
    public static @Nullable Data data() {
        return TTL.get();
    }

    /** Records a new-model edge using unique batch identities and display labels. */
    public static void logTaskPair(
            @Nullable String parentId, @Nullable String parentLabel, String childId, String childLabel, TaskEdge edge) {
        Data data = TTL.get();
        if (data == null) return;
        String source = parentId == null ? "root" : parentId;
        data.nodeLabels.put(source, parentLabel == null ? "NA" : parentLabel);
        data.nodeLabels.put(childId, childLabel == null ? "NA" : childLabel);
        data.subTaskList.add(new TaskEdgeEntry(EndpointPair.ordered(source, childId), edge));
    }

    /**
     * Checks if any task cycle exists.
     *
     * @return {@code true} when the current request graph contains a task cycle
     */
    public static boolean hasTaskCycle() {
        Data data = data();
        return data != null && data.isTaskCycle();
    }

    /**
     * Checks if any task self-loop exists.
     *
     * @return {@code true} when the current request graph contains a task self-loop
     */
    public static boolean hasSelfLoop() {
        Data data = data();
        return data != null && data.isSelfLoop();
    }

    /**
     * Returns whether the current request graph contains an executor cycle.
     *
     * @return {@code true} when an executor cycle exists
     */
    public static boolean hasExecutorCycle() {
        Data data = data();
        return data != null && data.isExecutorCycle();
    }

    /**
     * Returns whether the current request graph contains an executor self-loop.
     *
     * @return {@code true} when an executor self-loop exists
     */
    public static boolean hasExecutorSelfLoop() {
        Data data = data();
        return data != null && data.isExecutorSelfLoop();
    }
}

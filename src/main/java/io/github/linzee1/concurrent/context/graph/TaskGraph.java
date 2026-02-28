package io.github.linzee1.concurrent.context.graph;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import io.github.linzee1.concurrent.scope.StructuredParallel;
import io.github.linzee1.concurrent.spi.LivelockListener;
import io.github.linzee1.concurrent.spi.LivelockListener.LivelockEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.stream.Collectors;

/**
 * Task dependency graph for livelock/deadlock detection.
 * <p>
 * Extends {@link TransmittableThreadLocal} to share a directed {@link ValueGraph} of task
 * dependencies across all threads within a single request. Records parent-child task
 * relationships as edges with {@link TaskEdge} metadata (parallelism, task type, executor name,
 * task count, timeout).
 * <p>
 * At request end, builds directed graphs at both task level and executor level,
 * checking for cycles (potential deadlocks) and self-loops.
 * <p>
 * Lifecycle:
 * <ul>
 *   <li>Request start: {@link #initOnRequest()} creates a new Data instance</li>
 *   <li>During request: {@link #logTaskPair(String, String, TaskEdge)} records fork relationships</li>
 *   <li>Request end: {@link #destroyAfterRequest()} checks for cycles and notifies listeners</li>
 * </ul>
 *
 * @author linqh
 */
@SuppressWarnings("all")
public class TaskGraph extends TransmittableThreadLocal<TaskGraph.Data> {

    private static final TaskGraph TTL = new TaskGraph();

    private TaskGraph() {
    }

    /**
     * Task graph data (thread-safe, shared across all threads within a request).
     */
    public static class Data {
        final BlockingQueue<TaskEdgeEntry> subTaskList = new LinkedTransferQueue<>();

        private volatile ValueGraph<String, TaskEdge> graph;
        private volatile Boolean taskCycle;
        private volatile Boolean selfLoop;
        private volatile ValueGraph<String, List<TaskEdge>> executorGraph;
        private volatile Boolean executorCycle;
        private volatile Boolean executorSelfLoop;

        public ValueGraph<String, TaskEdge> getGraph() {
            if (graph == null) {
                synchronized (this) {
                    if (graph == null) {
                        graph = generateGraph();
                    }
                }
            }
            return graph;
        }

        public boolean isTaskCycle() {
            if (taskCycle == null) {
                taskCycle = checkTaskCycle();
            }
            return taskCycle;
        }

        public boolean isSelfLoop() {
            if (selfLoop == null) {
                selfLoop = checkSelfLoop();
            }
            return selfLoop;
        }

        public ValueGraph<String, List<TaskEdge>> getExecutorGraph() {
            if (executorGraph == null) {
                synchronized (this) {
                    if (executorGraph == null) {
                        executorGraph = generateExecutorGraph();
                    }
                }
            }
            return executorGraph;
        }

        public boolean isExecutorCycle() {
            if (executorCycle == null) {
                executorCycle = checkExecutorCycle();
            }
            return executorCycle;
        }

        public boolean isExecutorSelfLoop() {
            if (executorSelfLoop == null) {
                executorSelfLoop = checkExecutorSelfLoop();
            }
            return executorSelfLoop;
        }

        ValueGraph<String, TaskEdge> generateGraph() {
            ImmutableValueGraph.Builder<String, TaskEdge> graphBuilder =
                    ValueGraphBuilder.directed()
                            .allowsSelfLoops(true)
                            .<String, TaskEdge>immutable();
            for (TaskEdgeEntry entry : subTaskList) {
                graphBuilder.putEdgeValue(
                        entry.getEdge().source(),
                        entry.getEdge().target(),
                        entry.getValue());
            }
            return graphBuilder.build();
        }

        boolean checkTaskCycle() {
            ValueGraph<String, TaskEdge> g = getGraph();
            return g != null && Graphs.hasCycle(g.asGraph());
        }

        boolean checkSelfLoop() {
            return getGraph().edges().stream()
                    .anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }

        ValueGraph<String, List<TaskEdge>> generateExecutorGraph() {
            Map<String, String> taskToExecutorMap = StructuredParallel.getTaskToExecutorMapping();
            Map<EndpointPair<String>, List<TaskEdge>> executorEdges = new LinkedHashMap<>();

            for (EndpointPair<String> taskEdgePair : getGraph().edges()) {
                String sourceExecutor = taskToExecutorMap.getOrDefault(taskEdgePair.source(), "NA");
                String targetExecutor = taskToExecutorMap.getOrDefault(taskEdgePair.target(), "NA");
                EndpointPair<String> executorPair = EndpointPair.ordered(sourceExecutor, targetExecutor);
                TaskEdge taskEdge = getGraph().edgeValueOrDefault(
                        taskEdgePair.source(), taskEdgePair.target(), null);
                executorEdges.computeIfAbsent(executorPair, k -> new ArrayList<>()).add(taskEdge);
            }

            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder =
                    ValueGraphBuilder.directed()
                            .allowsSelfLoops(true)
                            .incidentEdgeOrder(ElementOrder.insertion())
                            .<String, List<TaskEdge>>immutable();
            for (Map.Entry<EndpointPair<String>, List<TaskEdge>> entry : executorEdges.entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(),
                        entry.getKey().target(),
                        Collections.unmodifiableList(entry.getValue()));
            }
            return graphBuilder.build();
        }

        boolean checkExecutorCycle() {
            ValueGraph<String, List<TaskEdge>> g = getExecutorGraph();
            return g != null && Graphs.hasCycle(g.asGraph());
        }

        boolean checkExecutorSelfLoop() {
            return getExecutorGraph().edges().stream()
                    .anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }
    }

    @Override
    protected Data initialValue() {
        return null;
    }

    @Override
    public Data copy(Data parentValue) {
        return parentValue;
    }

    // ==================== Request lifecycle ====================

    /**
     * Initializes task graph at request entry.
     */
    public static void initOnRequest() {
        TTL.set(new Data());
    }

    /**
     * Destroys task graph at request end. Runs livelock detection and notifies listeners.
     */
    public static void destroyAfterRequest() {
        try {
            Data data = TTL.get();
            if (data != null && !data.subTaskList.isEmpty()) {
                if (StructuredParallel.isLivelockDetectionEnabled()) {
                    LivelockEvent event = buildDetectionEvent(data);
                    if (event != null && event.hasAnyIssue()) {
                        StructuredParallel.getLogger().warn("[[title=TaskGraph,function=livelockDetection]]{}", event);
                        notifyLivelockListeners(event);
                    }
                }
            }
        } catch (Exception e) {
            StructuredParallel.getLogger().warn("[[title=TaskGraph,function=destroyAfterRequest]]Failed to run livelock detection", e);
        } finally {
            TTL.remove();
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
                    TaskEdge edge = data.getGraph().edgeValueOrDefault(p.source(), p.target(), null);
                    String edgeStr = edge != null ? " " + edge : "";
                    return p.source() + " -> " + p.target() + edgeStr;
                })
                .collect(Collectors.joining(", "));
        String executorEdges = data.getExecutorGraph().edges().stream()
                .map(p -> {
                    List<TaskEdge> edges = data.getExecutorGraph()
                            .edgeValueOrDefault(p.source(), p.target(), Collections.emptyList());
                    return p.source() + " -> " + p.target() + " " + edges;
                })
                .collect(Collectors.joining(", "));

        return new LivelockEvent(
                hasTaskCycle, hasSelfLoop,
                hasExecutorCycle, hasExecutorSelfLoop,
                taskEdges, executorEdges);
    }

    private static void notifyLivelockListeners(LivelockEvent event) {
        List<LivelockListener> listeners = StructuredParallel.getLivelockListeners();
        for (LivelockListener listener : listeners) {
            try {
                listener.onDetection(event);
            } catch (Exception e) {
                StructuredParallel.getLogger().warn("LivelockListener callback failed: {}", listener.getClass().getName(), e);
            }
        }
    }

    // ==================== Data access ====================

    /**
     * Gets the current request's Data.
     */
    public static Data data() {
        return TTL.get();
    }

    /**
     * Records a parent-child task relationship with edge metadata.
     *
     * @param parent parent task name
     * @param child  child task name
     * @param edge   edge metadata (parallelism, task type, executor, etc.)
     */
    public static void logTaskPair(String parent, String child, TaskEdge edge) {
        Data data = TTL.get();
        if (data == null) {
            return;
        }
        parent = parent != null ? parent : "NA";
        data.subTaskList.add(new TaskEdgeEntry(EndpointPair.ordered(parent, child), edge));
    }

    /**
     * Checks if any task cycle exists.
     */
    public static boolean hasTaskCycle() {
        Data data = data();
        return data != null && data.isTaskCycle();
    }

    /**
     * Checks if any task self-loop exists.
     */
    public static boolean hasSelfLoop() {
        Data data = data();
        return data != null && data.isSelfLoop();
    }

    /**
     * Checks if any executor cycle exists.
     */
    public static boolean hasExecutorCycle() {
        Data data = data();
        return data != null && data.isExecutorCycle();
    }

    /**
     * Checks if any executor self-loop exists.
     */
    public static boolean hasExecutorSelfLoop() {
        Data data = data();
        return data != null && data.isExecutorSelfLoop();
    }
}

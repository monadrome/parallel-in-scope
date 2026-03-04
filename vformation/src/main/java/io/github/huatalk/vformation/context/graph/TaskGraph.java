package io.github.huatalk.vformation.context.graph;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import io.github.huatalk.vformation.scope.ParConfig;
import io.github.huatalk.vformation.spi.LivelockListener;
import io.github.huatalk.vformation.spi.LivelockListener.LivelockEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Task dependency graph for livelock/deadlock detection.
 * <p>
 * Uses a composed {@link TransmittableThreadLocal} to share a directed {@link ValueGraph} of task
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
 *   <li>Request end: {@link #destroyAfterRequest(ParConfig)} checks for cycles and notifies listeners</li>
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

    private TaskGraph() {
    }

    /**
     * Task graph data (thread-safe, shared across all threads within a request).
     */
    public static class Data {
        final BlockingQueue<TaskEdgeEntry> subTaskList = new LinkedTransferQueue<>();

        private volatile ValueGraph<String, List<TaskEdge>> graph;
        private volatile Boolean taskCycle;
        private volatile Boolean selfLoop;

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

        /**
         * Gets the executor-level graph, using the provided config for thread pool resolution.
         */
        public ValueGraph<String, List<TaskEdge>> getExecutorGraph(ParConfig config) {
            return generateExecutorGraph(config);
        }

        /**
         * Checks if any executor cycle exists, using the provided config.
         */
        public boolean isExecutorCycle(ParConfig config) {
            ValueGraph<String, List<TaskEdge>> g = getExecutorGraph(config);
            return g != null && Graphs.hasCycle(g.asGraph());
        }

        /**
         * Checks if any executor self-loop exists, using the provided config.
         */
        public boolean isExecutorSelfLoop(ParConfig config) {
            return getExecutorGraph(config).edges().stream()
                    .anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }

        ValueGraph<String, List<TaskEdge>> generateGraph() {
            Map<EndpointPair<String>, List<TaskEdge>> edgeMap = new LinkedHashMap<>();
            for (TaskEdgeEntry entry : subTaskList) {
                edgeMap.computeIfAbsent(entry.getEdge(), k -> new ArrayList<>())
                        .add(entry.getValue());
            }
            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder =
                    ValueGraphBuilder.directed()
                            .allowsSelfLoops(true)
                            .<String, List<TaskEdge>>immutable();
            for (Map.Entry<EndpointPair<String>, List<TaskEdge>> entry : edgeMap.entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(),
                        entry.getKey().target(),
                        Collections.unmodifiableList(entry.getValue()));
            }
            return graphBuilder.build();
        }

        boolean checkTaskCycle() {
            ValueGraph<String, List<TaskEdge>> g = getGraph();
            return g != null && Graphs.hasCycle(g.asGraph());
        }

        boolean checkSelfLoop() {
            return getGraph().edges().stream()
                    .anyMatch(p -> Objects.equals(p.nodeU(), p.nodeV()));
        }

        ValueGraph<String, List<TaskEdge>> generateExecutorGraph(ParConfig config) {
            Map<EndpointPair<String>, List<TaskEdge>> executorEdges = new LinkedHashMap<>();

            for (EndpointPair<String> taskEdgePair : getGraph().edges()) {
                List<TaskEdge> edges = getGraph().edgeValueOrDefault(
                        taskEdgePair.source(), taskEdgePair.target(), Collections.<TaskEdge>emptyList());
                for (TaskEdge taskEdge : edges) {
                    String sourceExecutor = taskEdge.getSourceExecutorName();
                    String targetExecutor = taskEdge.getExecutorName();
                    if (!canDeadlock(targetExecutor, config)) {
                        continue;
                    }
                    EndpointPair<String> executorPair = EndpointPair.ordered(sourceExecutor, targetExecutor);
                    executorEdges.computeIfAbsent(executorPair, k -> new ArrayList<>()).add(taskEdge);
                }
            }

            ImmutableValueGraph.Builder<String, List<TaskEdge>> graphBuilder =
                    ValueGraphBuilder.directed()
                            .allowsSelfLoops(true)
                            .incidentEdgeOrder(ElementOrder.stable())
                            .<String, List<TaskEdge>>immutable();
            for (Map.Entry<EndpointPair<String>, List<TaskEdge>> entry : executorEdges.entrySet()) {
                graphBuilder.putEdgeValue(
                        entry.getKey().source(),
                        entry.getKey().target(),
                        Collections.unmodifiableList(entry.getValue()));
            }
            return graphBuilder.build();
        }
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
     *
     * @param config the ParConfig instance for livelock detection settings and listeners
     */
    public static void destroyAfterRequest(ParConfig config) {
        try {
            Data data = TTL.get();
            if (data != null && !data.subTaskList.isEmpty()) {
                if (config.isLivelockDetectionEnabled()) {
                    LivelockEvent event = buildDetectionEvent(data, config);
                    if (event != null && event.hasAnyIssue()) {
                        logger.log(Level.WARNING, "[[title=TaskGraph,function=livelockDetection]]" + event);
                        notifyLivelockListeners(config, event);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[[title=TaskGraph,function=destroyAfterRequest]]Failed to run livelock detection", e);
        } finally {
            TTL.remove();
        }
    }

    private static LivelockEvent buildDetectionEvent(Data data, ParConfig config) {
        boolean hasTaskCycle = data.isTaskCycle();
        boolean hasSelfLoop = data.isSelfLoop();
        boolean hasExecutorCycle = data.isExecutorCycle(config);
        boolean hasExecutorSelfLoop = data.isExecutorSelfLoop(config);

        if (!hasTaskCycle && !hasSelfLoop && !hasExecutorCycle && !hasExecutorSelfLoop) {
            return null;
        }

        String taskEdges = data.getGraph().edges().stream()
                .map(p -> {
                    List<TaskEdge> edges = data.getGraph().edgeValueOrDefault(
                            p.source(), p.target(), Collections.<TaskEdge>emptyList());
                    return p.source() + " -> " + p.target() + " " + edges;
                })
                .collect(Collectors.joining(", "));
        String executorEdges = data.getExecutorGraph(config).edges().stream()
                .map(p -> {
                    List<TaskEdge> edges = data.getExecutorGraph(config)
                            .edgeValueOrDefault(p.source(), p.target(), Collections.emptyList());
                    return p.source() + " -> " + p.target() + " " + edges;
                })
                .collect(Collectors.joining(", "));

        return new LivelockEvent(
                hasTaskCycle, hasSelfLoop,
                hasExecutorCycle, hasExecutorSelfLoop,
                taskEdges, executorEdges);
    }

    private static void notifyLivelockListeners(ParConfig config, LivelockEvent event) {
        List<LivelockListener> listeners = config.getLivelockListeners();
        for (LivelockListener listener : listeners) {
            try {
                listener.onDetection(event);
            } catch (Exception e) {
                logger.log(Level.WARNING, "LivelockListener callback failed: " + listener.getClass().getName(), e);
            }
        }
    }

    // ==================== Pool-aware deadlock risk ====================

    /**
     * Determines whether the given executor can participate in deadlocks,
     * using the provided ParConfig for thread pool resolution.
     *
     * @param executorName the executor name to check
     * @param config       the ParConfig instance for thread pool resolution
     * @return true if the executor is deadlock-prone or unknown
     */
    public static boolean canDeadlock(String executorName, ParConfig config) {
        if (executorName == null || "NA".equals(executorName)) {
            return true;
        }
        ThreadPoolExecutor tpe = config.resolveThreadPool(executorName);
        if (tpe == null) {
            return true;
        }
        if (tpe.getQueue() instanceof SynchronousQueue) {
            return false;
        }
        if (tpe.getMaximumPoolSize() >= Integer.MAX_VALUE) {
            return false;
        }
        return true;
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
     * Checks if any executor cycle exists, using the provided config.
     */
    public static boolean hasExecutorCycle(ParConfig config) {
        Data data = data();
        return data != null && data.isExecutorCycle(config);
    }

    /**
     * Checks if any executor self-loop exists, using the provided config.
     */
    public static boolean hasExecutorSelfLoop(ParConfig config) {
        Data data = data();
        return data != null && data.isExecutorSelfLoop(config);
    }
}

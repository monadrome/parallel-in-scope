package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The waits-for projection of a {@link TaskGraph} onto executors, which is where potential
 * deadlocks are detected.
 *
 * <p>Every {@link TaskGraph.Dependency#FORK} edge {@code u → v} whose units both run on a known
 * executor, and whose awaited unit {@code v} runs on a starvation-prone one, becomes the edge
 * {@code executor(u) → executor(v)}: a thread of {@code u}'s pool is blocked, and {@code v} can sit in
 * its pool's queue behind workers that are themselves blocked. A {@link TaskGraph.Dependency#JOIN}
 * edge never projects, because nothing blocks on it. A cycle — a self-loop included — is the
 * structural shape of pools starving each other, or one pool starving itself.
 *
 * <p>Executor nodes are keyed by {@link ExecutorIdentity}, so one pool registered under two ids stays
 * one node and two pools sharing a label stay two; labels are for display only. Each executor edge
 * carries the task edges that projected onto it, in the task graph's edge order, so a report can
 * name the units behind a cycle. Projection is a pure function: it reads the task graph and writes
 * nothing back.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class ExecutorGraph {

    private final ValueGraph<ExecutorIdentity, ImmutableList<EndpointPair<TaskNode>>> graph;
    private final ImmutableMap<ExecutorIdentity, String> labels;

    private ExecutorGraph(
            ValueGraph<ExecutorIdentity, ImmutableList<EndpointPair<TaskNode>>> graph,
            ImmutableMap<ExecutorIdentity, String> labels) {
        this.graph = graph;
        this.labels = labels;
    }

    /** Projects {@code tasks} onto executors; the task graph is only read. */
    static ExecutorGraph projectedFrom(TaskGraph tasks) {
        ValueGraph<TaskNode, TaskGraph.Dependency> source =
                checkNotNull(tasks, "tasks cannot be null").asValueGraph();
        ListMultimap<EndpointPair<ExecutorIdentity>, EndpointPair<TaskNode>> waits = LinkedListMultimap.create();
        SetMultimap<ExecutorIdentity, String> labelsSeen = LinkedHashMultimap.create();
        for (EndpointPair<TaskNode> edge : source.edges()) {
            TaskNode waiter = edge.source();
            TaskNode awaited = edge.target();
            ExecutorIdentity from = waiter.executor();
            ExecutorIdentity to = awaited.executor();
            if (source.edgeValueOrDefault(waiter, awaited, null) != TaskGraph.Dependency.FORK
                    || !awaited.starvationProne()
                    || from == null
                    || to == null) {
                continue;
            }
            waits.put(EndpointPair.ordered(from, to), edge);
            recordLabel(labelsSeen, from, waiter.executorLabel());
            recordLabel(labelsSeen, to, awaited.executorLabel());
        }
        ImmutableValueGraph.Builder<ExecutorIdentity, ImmutableList<EndpointPair<TaskNode>>> builder =
                ValueGraphBuilder.directed()
                        .allowsSelfLoops(true)
                        .nodeOrder(ElementOrder.<ExecutorIdentity>insertion())
                        .incidentEdgeOrder(ElementOrder.<ExecutorIdentity>stable())
                        .immutable();
        for (Map.Entry<EndpointPair<ExecutorIdentity>, Collection<EndpointPair<TaskNode>>> entry :
                waits.asMap().entrySet()) {
            builder.putEdgeValue(
                    entry.getKey().source(), entry.getKey().target(), ImmutableList.copyOf(entry.getValue()));
        }
        ImmutableMap.Builder<ExecutorIdentity, String> labels = ImmutableMap.builder();
        for (Map.Entry<ExecutorIdentity, Collection<String>> entry :
                labelsSeen.asMap().entrySet()) {
            labels.put(entry.getKey(), String.join("/", entry.getValue()));
        }
        return new ExecutorGraph(builder.build(), labels.build());
    }

    private static void recordLabel(
            SetMultimap<ExecutorIdentity, String> labels, ExecutorIdentity executor, @Nullable String label) {
        if (label != null) {
            labels.put(executor, label);
        }
    }

    /** The projection as a read-only Guava value graph: each edge value is its task evidence. */
    ValueGraph<ExecutorIdentity, ImmutableList<EndpointPair<TaskNode>>> asValueGraph() {
        return graph;
    }

    /** Whether some executors wait on each other in a cycle; a self-loop counts. */
    boolean cycle() {
        return Graphs.hasCycle(graph.asGraph());
    }

    /** Whether some executor waits on itself. */
    boolean selfLoop() {
        for (EndpointPair<ExecutorIdentity> edge : graph.edges()) {
            if (edge.source().equals(edge.target())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The executor edges that lie on a cycle, in edge order: {@code a → b} is on a cycle exactly when
     * {@code a} is reachable from {@code b}. Edges that merely lead into a cycle are left out.
     */
    List<EndpointPair<ExecutorIdentity>> cycleEdges() {
        ImmutableList.Builder<EndpointPair<ExecutorIdentity>> onCycle = ImmutableList.builder();
        for (EndpointPair<ExecutorIdentity> edge : graph.edges()) {
            if (Graphs.reachableNodes(graph.asGraph(), edge.target()).contains(edge.source())) {
                onCycle.add(edge);
            }
        }
        return onCycle.build();
    }

    /** The task edges that projected onto {@code edge}; empty when the projection has no such edge. */
    List<EndpointPair<TaskNode>> evidence(EndpointPair<ExecutorIdentity> edge) {
        checkNotNull(edge, "edge cannot be null");
        return Verify.verifyNotNull(
                graph.edgeValueOrDefault(edge.source(), edge.target(), ImmutableList.<EndpointPair<TaskNode>>of()));
    }

    /**
     * The display label of {@code executor}: every label it was seen under, in first-seen order and
     * joined with {@code /}, or the identity's own form when it was seen under none.
     */
    String label(ExecutorIdentity executor) {
        checkNotNull(executor, "executor cannot be null");
        String label = labels.get(executor);
        return label == null ? executor.toString() : label;
    }
}

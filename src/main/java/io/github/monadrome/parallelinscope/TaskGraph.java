package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Verify;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import org.jspecify.annotations.Nullable;

/**
 * An immutable task dependency graph: {@link TaskNode}s connected by {@link Dependency} edges.
 *
 * <p>The design follows Guava {@code common.graph}: the graph owns every adjacency and the nodes
 * own only their own data, so a node never knows its neighbours and can belong to any number of
 * graphs. A graph is a value — written by a {@link Builder} with no runtime, scope, or thread state
 * involved, never changed afterwards, and equal to any other graph with the same nodes and the same
 * edges, however each was built. Algorithms such as the executor projection ({@link
 * ExecutorGraph#projectedFrom}) are functions over a graph, not part of it.
 *
 * <p>Edges point from a unit that already exists to the unit an admission creates: a {@link
 * Dependency#FORK} from the waiter to the awaited, a {@link Dependency#JOIN} from the input to its
 * consumer. Every admission creates new unit ids, so the graphs the library records are acyclic by
 * construction and unit creation order is a topological order. The type itself does not enforce
 * acyclicity — a test may write any shape — so no analysis relies on it. Self-dependencies are
 * rejected: no unit is its own waiter or input.
 *
 * <p>Nodes iterate in insertion order and each node's edges in a stable order, so rendering and
 * projection evidence are reproducible. See {@code design/task-graph.md}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class TaskGraph {

    /** The relation an edge records; the only fact an edge carries. */
    enum Dependency {
        /** The source unit's body blocks synchronously until the target unit finishes. */
        FORK,
        /** The target unit consumes the source unit's result; nothing blocks on the edge. */
        JOIN
    }

    private static final TaskGraph EMPTY = builder().build();

    private final ImmutableValueGraph<TaskNode, Dependency> graph;

    private TaskGraph(ImmutableValueGraph<TaskNode, Dependency> graph) {
        this.graph = graph;
    }

    static Builder builder() {
        return new Builder();
    }

    /** The graph with no nodes. */
    static TaskGraph empty() {
        return EMPTY;
    }

    /** The graph as a read-only Guava value graph, for algorithms and inspection. */
    ValueGraph<TaskNode, Dependency> asValueGraph() {
        return graph;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TaskGraph && ((TaskGraph) other).graph.equals(graph);
    }

    @Override
    public int hashCode() {
        return graph.hashCode();
    }

    @Override
    public String toString() {
        return graph.toString();
    }

    /**
     * Writes a {@link TaskGraph}. A builder is single-threaded; {@link #build()} takes an immutable
     * snapshot, so later writes never reach a graph already built.
     *
     * <p>A pair of nodes carries at most one edge. When both relations are recorded for one pair,
     * {@link Dependency#FORK} wins whichever came first: waiting implies the input relation, and
     * waiting is the only relation that matters for thread starvation.
     */
    static final class Builder {
        private final MutableValueGraph<TaskNode, Dependency> graph = ValueGraphBuilder.directed()
                .allowsSelfLoops(false)
                .nodeOrder(ElementOrder.<TaskNode>insertion())
                .incidentEdgeOrder(ElementOrder.<TaskNode>stable())
                .build();

        private Builder() {}

        /**
         * Records one admission: {@code unit} joins the graph and, when the admission happened inside
         * another unit's body, that caller synchronously waits for it.
         *
         * @param caller the unit whose body admitted {@code unit}, or null for a top-level call; a
         *     top-level unit is simply a node without predecessors
         * @param unit the admitted unit
         * @throws IllegalArgumentException if {@code caller} and {@code unit} are the same unit
         */
        Builder admit(@Nullable TaskNode caller, TaskNode unit) {
            checkNotNull(unit, "unit cannot be null");
            if (caller == null) {
                graph.addNode(unit);
                return this;
            }
            return fork(caller, unit);
        }

        /**
         * Records that {@code waiter}'s body blocks until {@code awaited} finishes.
         *
         * @throws IllegalArgumentException if both are the same unit
         */
        Builder fork(TaskNode waiter, TaskNode awaited) {
            return put(waiter, awaited, Dependency.FORK);
        }

        /**
         * Records that {@code consumer} consumes {@code input}'s result without anything blocking on
         * it. An existing {@link Dependency#FORK} between the two is kept.
         *
         * @throws IllegalArgumentException if both are the same unit
         */
        Builder join(TaskNode input, TaskNode consumer) {
            return put(input, consumer, Dependency.JOIN);
        }

        /** Merges every node and edge of {@code other}, in its order, under the same dominance rule. */
        Builder putAll(TaskGraph other) {
            ValueGraph<TaskNode, Dependency> source = checkNotNull(other, "other cannot be null").graph;
            for (TaskNode node : source.nodes()) {
                graph.addNode(node);
            }
            for (EndpointPair<TaskNode> edge : source.edges()) {
                put(
                        edge.source(),
                        edge.target(),
                        Verify.verifyNotNull(source.edgeValueOrDefault(edge.source(), edge.target(), null)));
            }
            return this;
        }

        /** Takes an immutable snapshot of everything written so far. */
        TaskGraph build() {
            return new TaskGraph(ImmutableValueGraph.copyOf(graph));
        }

        private Builder put(TaskNode source, TaskNode target, Dependency dependency) {
            checkNotNull(source, "source cannot be null");
            checkNotNull(target, "target cannot be null");
            checkArgument(!source.equals(target), "a task unit cannot depend on itself: %s", source);
            if (graph.edgeValueOrDefault(source, target, null) != Dependency.FORK) {
                graph.putEdgeValue(source, target, dependency);
            }
            return this;
        }
    }
}

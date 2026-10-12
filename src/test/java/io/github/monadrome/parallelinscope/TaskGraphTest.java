package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.node;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.graph.EndpointPair;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraph;
import io.github.monadrome.parallelinscope.TaskGraph.Dependency;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskGraph} as an independent value: written without any runtime, shared nodes, value
 * equality, the FORK-over-JOIN rule, and snapshot isolation.
 */
class TaskGraphTest {

    @Test
    void aGraphIsWrittenWithoutAnyRuntimeScopeOrThread() {
        TaskNode caller = node("caller");
        TaskNode left = node("left");
        TaskNode right = node("right");
        TaskNode combine = node("combine");

        TaskGraph graph = TaskGraph.builder()
                .admit(caller, left)
                .admit(caller, right)
                .admit(caller, combine)
                .join(left, combine)
                .join(right, combine)
                .build();

        ValueGraph<TaskNode, Dependency> view = graph.asValueGraph();
        assertThat(view.nodes()).containsExactly(caller, left, right, combine);
        assertThat(view.successors(caller)).containsExactly(left, right, combine);
        assertThat(view.predecessors(combine)).containsExactlyInAnyOrder(caller, left, right);
        assertThat(view.edgeValue(caller, combine)).contains(Dependency.FORK);
        assertThat(view.edgeValue(left, combine)).contains(Dependency.JOIN);
        assertThat(view.hasEdgeConnecting(left, right)).isFalse();
        assertThat(view.hasEdgeConnecting(right, left)).isFalse();
    }

    @Test
    void aTopLevelAdmissionIsANodeWithoutPredecessors() {
        TaskNode unit = node("unit");

        TaskGraph graph = TaskGraph.builder().admit(null, unit).build();

        assertThat(graph.asValueGraph().nodes()).containsExactly(unit);
        assertThat(graph.asValueGraph().predecessors(unit)).isEmpty();
        assertThat(graph.asValueGraph().edges()).isEmpty();
    }

    @Test
    void oneNodeInstanceSitsInSeveralGraphsWithIndependentAdjacency() {
        TaskNode shared = node("shared");
        TaskNode parent = node("parent");
        TaskNode child = node("child");

        TaskGraph asAwaited = TaskGraph.builder().admit(parent, shared).build();
        TaskGraph asWaiter = TaskGraph.builder().admit(shared, child).build();
        TaskGraph alone = TaskGraph.builder().admit(null, shared).build();

        assertThat(asAwaited.asValueGraph().predecessors(shared)).containsExactly(parent);
        assertThat(asAwaited.asValueGraph().successors(shared)).isEmpty();
        assertThat(asWaiter.asValueGraph().predecessors(shared)).isEmpty();
        assertThat(asWaiter.asValueGraph().successors(shared)).containsExactly(child);
        assertThat(alone.asValueGraph().adjacentNodes(shared)).isEmpty();
        for (TaskGraph graph : new TaskGraph[] {asAwaited, asWaiter, alone}) {
            assertThat(graph.asValueGraph().nodes())
                    .filteredOn(n -> n.equals(shared))
                    .singleElement()
                    .isSameAs(shared);
        }
    }

    @Test
    void forkDominatesJoinWhicheverIsWrittenFirst() {
        TaskNode a = node("a");
        TaskNode b = node("b");

        TaskGraph joinFirst = TaskGraph.builder().join(a, b).fork(a, b).build();
        TaskGraph forkFirst = TaskGraph.builder().fork(a, b).join(a, b).build();

        assertThat(joinFirst.asValueGraph().edgeValue(a, b)).contains(Dependency.FORK);
        assertThat(forkFirst.asValueGraph().edgeValue(a, b)).contains(Dependency.FORK);
        assertThat(joinFirst).isEqualTo(forkFirst);
        assertThat(joinFirst.asValueGraph().edges()).hasSize(1);
    }

    @Test
    void aJoinAloneStaysAJoin() {
        TaskNode a = node("a");
        TaskNode b = node("b");

        TaskGraph graph = TaskGraph.builder().join(a, b).join(a, b).build();

        assertThat(graph.asValueGraph().edgeValue(a, b)).contains(Dependency.JOIN);
    }

    @Test
    void aUnitCannotDependOnItself() {
        TaskNode unit = node("unit");
        TaskNode sameId = node("unit");

        assertThatThrownBy(() -> TaskGraph.builder().admit(unit, unit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a task unit cannot depend on itself: task-unit[unit-unit]");
        assertThatThrownBy(() -> TaskGraph.builder().fork(unit, sameId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskGraph.builder().join(unit, unit)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void graphsAreValuesWhateverOrderTheyWereWrittenIn() {
        TaskNode a = node("a");
        TaskNode b = node("b");
        TaskNode c = node("c");

        TaskGraph first = TaskGraph.builder().fork(a, b).join(b, c).build();
        TaskGraph second = TaskGraph.builder()
                .admit(null, c)
                .join(b, c)
                .fork(node("a"), node("b"))
                .build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(TaskGraph.builder().join(a, b).join(b, c).build());
        assertThat(first).isNotEqualTo(TaskGraph.builder().fork(a, b).build());
        assertThat(TaskGraph.empty()).isEqualTo(TaskGraph.builder().build());
    }

    @Test
    void buildTakesASnapshotThatLaterWritesNeverReach() {
        TaskNode a = node("a");
        TaskNode b = node("b");
        TaskGraph.Builder builder = TaskGraph.builder().admit(null, a);

        TaskGraph before = builder.build();
        builder.fork(a, b);
        TaskGraph after = builder.build();

        assertThat(before.asValueGraph().nodes()).containsExactly(a);
        assertThat(before.asValueGraph().edges()).isEmpty();
        assertThat(after.asValueGraph().edges()).containsExactly(EndpointPair.ordered(a, b));
    }

    @Test
    void theValueGraphViewCannotBeMutated() {
        TaskGraph graph = TaskGraph.builder().fork(node("a"), node("b")).build();

        assertThat(graph.asValueGraph()).isNotInstanceOf(MutableValueGraph.class);
    }

    @Test
    void aGraphRendersItsNodesAndEdgeValues() {
        TaskGraph graph = TaskGraph.builder().fork(node("a"), node("b")).build();

        assertThat(graph.toString())
                .isEqualTo(graph.asValueGraph().toString())
                .contains("task-a[unit-a]", "task-b[unit-b]", "FORK");
    }

    @Test
    void putAllKeepsAnExistingForkWhenTheMergedFragmentOnlyJoins() {
        TaskNode a = node("a");
        TaskNode b = node("b");
        TaskGraph fragment = TaskGraph.builder().join(a, b).build();

        TaskGraph merged = TaskGraph.builder().fork(a, b).putAll(fragment).build();

        assertThat(merged.asValueGraph().edgeValue(a, b)).contains(Dependency.FORK);
    }

    @Test
    void putAllMergesInOrderUnderTheSameDominanceRule() {
        TaskNode a = node("a");
        TaskNode b = node("b");
        TaskNode c = node("c");
        TaskGraph fragment = TaskGraph.builder().fork(a, b).admit(null, c).build();

        TaskGraph merged =
                TaskGraph.builder().join(a, b).putAll(fragment).putAll(fragment).build();

        assertThat(merged.asValueGraph().nodes()).containsExactly(a, b, c);
        assertThat(merged.asValueGraph().edgeValue(a, b)).contains(Dependency.FORK);
        assertThat(merged.asValueGraph().edges()).hasSize(1);
        assertThat(TaskGraph.builder().putAll(merged).build()).isEqualTo(merged);
    }
}

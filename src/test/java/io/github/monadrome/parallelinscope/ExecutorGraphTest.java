package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.node;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.pronePool;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.safePool;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.graph.EndpointPair;
import io.github.monadrome.parallelinscope.TaskGraphFixtures.Pool;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The waits-for projection: which task edges reach the executor graph, how executors are keyed, and
 * which shapes are cycles.
 */
class ExecutorGraphTest {

    @Test
    void aForkOntoAStarvationProneExecutorProjectsWithItsEvidence() {
        Pool outer = pronePool("outer");
        Pool inner = pronePool("inner");
        TaskNode caller = outer.node("caller");
        TaskNode batch = inner.node("batch");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(
                TaskGraph.builder().admit(caller, batch).build());

        EndpointPair<ExecutorIdentity> wait = EndpointPair.ordered(outer.identity, inner.identity);
        assertThat(executors.asValueGraph().edges()).containsExactly(wait);
        assertThat(executors.evidence(wait)).containsExactly(EndpointPair.ordered(caller, batch));
        assertThat(executors.cycle()).isFalse();
        assertThat(executors.selfLoop()).isFalse();
        assertThat(executors.cycleEdges()).isEmpty();
    }

    @Test
    void aForkOntoAnExecutorThatCannotStarveDoesNotProject() {
        Pool outer = pronePool("outer");
        Pool cached = safePool("cached");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.builder()
                .admit(outer.node("caller"), cached.node("batch"))
                .build());

        assertThat(executors.asValueGraph().edges()).isEmpty();
        assertThat(executors.asValueGraph().nodes()).isEmpty();
    }

    @Test
    void aJoinNeverProjectsEvenOntoTheInputsOwnStarvationPronePool() {
        Pool pool = pronePool("pool");
        TaskNode batch = pool.node("batch");
        TaskNode combine = pool.node("combine");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(
                TaskGraph.builder().admit(null, batch).join(batch, combine).build());

        assertThat(executors.asValueGraph().edges()).isEmpty();
        assertThat(executors.selfLoop()).isFalse();
        assertThat(executors.cycle()).isFalse();
    }

    @Test
    void aUnitWithoutAnExecutorIsLeftOutInsteadOfFailingTheProjection() {
        Pool pool = pronePool("pool");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.builder()
                .admit(node("caller"), pool.node("a"))
                .admit(pool.node("b"), node("child"))
                .build());

        assertThat(executors.asValueGraph().edges()).isEmpty();
        assertThat(executors.cycle()).isFalse();
    }

    @Test
    void nestedWorkOnOneStarvationPronePoolIsASelfLoopAndACycle() {
        Pool shared = pronePool("shared");
        TaskNode outer = shared.node("outer");
        TaskNode inner = shared.node("inner");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(
                TaskGraph.builder().admit(outer, inner).build());

        EndpointPair<ExecutorIdentity> self = EndpointPair.ordered(shared.identity, shared.identity);
        assertThat(executors.selfLoop()).isTrue();
        assertThat(executors.cycle()).isTrue();
        assertThat(executors.cycleEdges()).containsExactly(self);
        assertThat(executors.evidence(self)).containsExactly(EndpointPair.ordered(outer, inner));
    }

    @Test
    void twoPoolsWaitingOnEachOtherAreACycleWithoutASelfLoop() {
        Pool first = pronePool("first");
        Pool second = pronePool("second");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.builder()
                .admit(first.node("a"), second.node("b"))
                .admit(second.node("b"), first.node("c"))
                .build());

        assertThat(executors.cycle()).isTrue();
        assertThat(executors.selfLoop()).isFalse();
        assertThat(executors.cycleEdges())
                .containsExactly(
                        EndpointPair.ordered(first.identity, second.identity),
                        EndpointPair.ordered(second.identity, first.identity));
    }

    @Test
    void twoPoolsSharingALabelStayTwoExecutors() {
        Pool first = pronePool("pool");
        Pool second = pronePool("pool");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.builder()
                .admit(first.node("a"), second.node("b"))
                .admit(first.node("c"), second.node("d"))
                .build());

        assertThat(executors.asValueGraph().nodes()).containsExactly(first.identity, second.identity);
        assertThat(executors.asValueGraph().edges())
                .containsExactly(EndpointPair.ordered(first.identity, second.identity));
        assertThat(executors.selfLoop()).isFalse();
        assertThat(executors.cycle()).isFalse();
    }

    @Test
    void onePoolRegisteredUnderTwoIdsStaysOneExecutorWithBothLabels() {
        Pool http = pronePool("http");
        Pool alias = http.registeredAs("io");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.builder()
                .admit(http.node("outer"), alias.node("inner"))
                .build());

        assertThat(executors.asValueGraph().nodes()).containsExactly(http.identity);
        assertThat(executors.selfLoop()).isTrue();
        assertThat(executors.label(http.identity)).isEqualTo("http/io");
    }

    @Test
    void aLabelFallsBackToTheIdentityOnlyForAnExecutorNeverSeen() {
        Pool seen = pronePool("seen");
        Pool unseen = pronePool("unseen");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(
                TaskGraph.builder().admit(seen.node("a"), seen.node("b")).build());

        assertThat(executors.label(seen.identity)).isEqualTo("seen");
        assertThat(executors.label(unseen.identity)).isEqualTo(unseen.identity.toString());
        assertThat(executors.evidence(EndpointPair.ordered(unseen.identity, unseen.identity)))
                .isEmpty();
    }

    @Test
    void evidenceFollowsTheTaskGraphsEdgeOrder() {
        Pool source = pronePool("source");
        Pool target = pronePool("target");
        TaskNode first = source.node("first");
        TaskNode second = source.node("second");
        TaskGraph tasks = TaskGraph.builder()
                .admit(first, target.node("x"))
                .admit(second, target.node("y"))
                .admit(first, target.node("z"))
                .build();

        ExecutorGraph executors = ExecutorGraph.projectedFrom(tasks);

        List<EndpointPair<TaskNode>> taskOrder =
                new ArrayList<>(tasks.asValueGraph().edges());
        assertThat(executors.evidence(EndpointPair.ordered(source.identity, target.identity)))
                .containsExactlyElementsOf(taskOrder)
                .extracting(edge -> edge.target().id())
                .containsExactly("x", "z", "y");
    }

    @Test
    void onlyTheEdgesOnACycleAreCycleEdges() {
        Pool entry = pronePool("entry");
        Pool looping = pronePool("looping");
        Pool leaf = pronePool("leaf");

        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.builder()
                .admit(entry.node("a"), looping.node("b"))
                .admit(looping.node("b"), looping.node("c"))
                .admit(looping.node("c"), leaf.node("d"))
                .build());

        assertThat(executors.cycle()).isTrue();
        assertThat(executors.cycleEdges()).containsExactly(EndpointPair.ordered(looping.identity, looping.identity));
    }

    @Test
    void anEmptyTaskGraphProjectsToAnEmptyExecutorGraph() {
        ExecutorGraph executors = ExecutorGraph.projectedFrom(TaskGraph.empty());

        assertThat(executors.asValueGraph().nodes()).isEmpty();
        assertThat(executors.cycle()).isFalse();
        assertThat(executors.selfLoop()).isFalse();
        assertThat(executors.cycleEdges()).isEmpty();
    }
}

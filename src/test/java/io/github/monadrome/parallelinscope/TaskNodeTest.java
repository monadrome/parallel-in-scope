package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.node;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.pronePool;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** {@link TaskNode}: identity, rendering, and the rule that a node describes only its own unit. */
class TaskNodeTest {

    @Test
    void nodesAreEqualByUnitIdAlone() {
        TaskNode onPool = pronePool("pool").node("u");
        TaskNode bare = node("u");

        assertThat(onPool).isEqualTo(bare).hasSameHashCodeAs(bare);
        assertThat(onPool).isNotEqualTo(node("v"));
        assertThat(onPool).hasToString("task-u[u]");
    }

    @Test
    void aNodeDescribesOnlyItselfAndHoldsNoNeighbourGraphOrExecutionState() {
        Set<Class<?>> ownFacts = new HashSet<>(Arrays.asList(
                String.class, ExecutorIdentity.class, boolean.class, TaskType.class, int.class, long.class));

        List<Class<?>> declared = Arrays.stream(TaskNode.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toList());

        assertThat(declared).isNotEmpty().allMatch(ownFacts::contains);
    }

    @Test
    void theBudgetIsTheResolvedNanosAsADuration() {
        TaskNode node = new TaskNode("u", "unit", null, null, false, TaskType.MIXED, 2, 3, 1_500L);

        assertThat(node.budget()).isEqualTo(Duration.ofNanos(1_500L));
        assertThat(node.taskType()).isEqualTo(TaskType.MIXED);
        assertThat(node.parallelism()).isEqualTo(2);
        assertThat(node.taskCount()).isEqualTo(3);
        assertThat(node.executor()).isNull();
        assertThat(node.executorLabel()).isNull();
        assertThat(node.starvationProne()).isFalse();
        assertThat(node("u").budget()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
    }
}

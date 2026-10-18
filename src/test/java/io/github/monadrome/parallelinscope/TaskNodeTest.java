package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
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

    private static TaskNode node(String token) {
        return new TaskNode(
                TaskId.unit(token), "task-" + token, null, null, false, TaskType.IO_BOUND, 1, 1, Long.MAX_VALUE);
    }

    private static TaskNode onPool(String token) {
        return new TaskNode(
                TaskId.unit(token),
                "task-" + token,
                new ExecutorIdentity(MoreExecutors.newDirectExecutorService()),
                "pool",
                true,
                TaskType.IO_BOUND,
                1,
                1,
                Long.MAX_VALUE);
    }

    @Test
    void nodesAreEqualByUnitIdAlone() {
        TaskNode onPool = onPool("u");
        TaskNode bare = node("u");

        assertThat(onPool).isEqualTo(bare).hasSameHashCodeAs(bare);
        assertThat(onPool).isNotEqualTo(node("v"));
        assertThat(onPool).hasToString("task-u[unit-u]");
    }

    @Test
    void aNodeDescribesOnlyItselfAndHoldsNoNeighbourGraphOrExecutionState() {
        Set<Class<?>> ownFacts = new HashSet<>(Arrays.asList(
                String.class,
                TaskId.class,
                ExecutorIdentity.class,
                boolean.class,
                TaskType.class,
                int.class,
                long.class));

        List<Field> declared = Arrays.stream(TaskNode.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .collect(Collectors.toList());

        assertThat(declared).extracting(Field::getType).allMatch(ownFacts::contains);
        assertThat(declared)
                .as("a new field must be reviewed: an allowed type can still smuggle in a neighbour's data")
                .extracting(Field::getName)
                .containsExactlyInAnyOrder(
                        "id",
                        "name",
                        "executor",
                        "executorLabel",
                        "starvationProne",
                        "taskType",
                        "parallelism",
                        "taskCount",
                        "budgetNanos");
    }

    @Test
    void theBudgetIsTheResolvedNanosAsADuration() {
        TaskNode node = new TaskNode(TaskId.unit("u"), "unit", null, null, false, TaskType.MIXED, 2, 3, 1_500L);

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

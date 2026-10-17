package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskIdTest {

    @Test
    void idsOfTheSameKindAndLabelAreEqual() {
        assertThat(TaskId.unit("7")).isEqualTo(TaskId.unit("7")).hasSameHashCodeAs(TaskId.unit("7"));
        assertThat(TaskId.group("7")).isEqualTo(TaskId.group("7")).hasSameHashCodeAs(TaskId.group("7"));
        assertThat(TaskId.unit("7")).isNotEqualTo(TaskId.unit("8"));
        assertThat(TaskId.unit("7")).isNotEqualTo(null);
        assertThat(TaskId.unit("7")).isNotEqualTo("unit-7");
    }

    @Test
    void theKindIsPartOfTheValueSoLabelsNeverLeakAcrossSpaces() {
        TaskId<TaskUnit> unit = TaskId.unit("7");
        TaskId<TaskGroupRun> group = TaskId.group("7");

        assertThat(unit).isNotEqualTo(group);
        assertThat(group).isNotEqualTo(unit);
    }

    @Test
    void renderingNamesTheKindAndTheLabel() {
        assertThat(TaskId.unit("7")).hasToString("unit-7");
        assertThat(TaskId.group("3")).hasToString("group-3");
    }

    @Test
    @SuppressWarnings("NullAway")
    void aLabelIsNeverNullAndNeverBlank() {
        assertThatThrownBy(() -> TaskId.unit(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskId.unit("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskId.group(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskId.group("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreUsableAsMapKeysWithoutMergingSpaces() {
        Map<TaskId<TaskUnit>, String> units = new HashMap<>();
        units.put(TaskId.unit("7"), "unit");
        units.put(TaskId.unit("7"), "replaced");

        assertThat(units).containsExactly(Map.entry(TaskId.unit("7"), "replaced"));
        assertThat(units).doesNotContainKey(TaskId.unit("8"));
        assertThat(units.containsKey(TaskId.group("7"))).isFalse();
    }
}

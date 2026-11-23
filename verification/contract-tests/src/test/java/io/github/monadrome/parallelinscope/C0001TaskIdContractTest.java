package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Contract: design/task-identity.md, "Equality as reconciliation". */
@Tag("C-0001")
class C0001TaskIdContractTest {

    @Test
    void reconciliationUsesTheExactLabelWithoutTrimming() {
        assertThat(TaskId.unit(" 7 ")).isNotEqualTo(TaskId.unit("7"));
        assertThat(TaskId.group(" 7 ")).isNotEqualTo(TaskId.group("7"));
        assertThat(TaskId.unit(" 7 ")).hasToString("unit- 7 ");
        assertThat(TaskId.group(" 7 ")).hasToString("group- 7 ");
    }
}

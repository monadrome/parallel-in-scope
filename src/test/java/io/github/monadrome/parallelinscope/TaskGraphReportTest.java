package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.pronePool;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.twoPoolCycle;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.TaskGraphFixtures.Pool;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link TaskGraphReport}: status, flags, and what the diagnostics render. */
class TaskGraphReportTest {

    private static TaskGraphReport detect(TaskGraph tasks) {
        return TaskGraphReport.detection(ExecutorGraph.projectedFrom(tasks));
    }

    private static TaskGraph selfLoop() {
        Pool shared = pronePool("shared");
        return TaskGraph.builder()
                .admit(shared.node("outer"), shared.node("inner"))
                .build();
    }

    @Test
    void disabledReportHasNoFlagsAndEmptyEdges() {
        TaskGraphReport report = TaskGraphReport.disabled();
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.DISABLED);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.executorCycle()).isFalse();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
    }

    @Test
    void detectionWithoutACycleIsNoIssueWithEmptyEdges() {
        Pool outer = pronePool("outer");
        TaskGraphReport report = detect(TaskGraph.builder()
                .admit(outer.node("a"), pronePool("inner").node("b"))
                .build());

        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.executorCycle()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
    }

    @Test
    void aSelfLoopIsAnIssueAndACycle() {
        TaskGraphReport report = detect(selfLoop());

        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.executorCycle()).isTrue();
        assertThat(report.executorSelfLoop()).isTrue();
        assertThat(report.executorEdges())
                .isEqualTo("shared -> shared [<task-outer[unit-outer] -> task-inner[unit-inner]>]");
        assertThat(report.taskEdges())
                .isEqualTo("task-outer[unit-outer] -> task-inner[unit-inner] "
                        + "{p=1, type=CPU_BOUND, src=shared, exec=shared, count=1, budget=PT0S}");
    }

    @Test
    void aTwoPoolCycleIsAnIssueWithoutASelfLoop() {
        TaskGraphReport report = detect(twoPoolCycle());

        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.executorCycle()).isTrue();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.executorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
        assertThat(report.taskEdges()).contains("task-a[unit-a] -> task-b[unit-b]", "task-b[unit-b] -> task-c[unit-c]");
    }

    @Test
    void onlyTheEdgesOnTheCycleAreRendered() {
        Pool entry = pronePool("entry");
        Pool looping = pronePool("looping");
        Pool leaf = pronePool("leaf");
        TaskGraphReport report = detect(TaskGraph.builder()
                .admit(entry.node("a"), looping.node("b"))
                .admit(looping.node("b"), looping.node("c"))
                .admit(looping.node("c"), leaf.node("d"))
                .build());

        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.executorEdges())
                .startsWith("looping -> looping")
                .doesNotContain("entry")
                .doesNotContain("leaf");
        assertThat(report.taskEdges())
                .startsWith("task-b[unit-b] -> task-c[unit-c]")
                .doesNotContain("task-a[unit-a]")
                .doesNotContain("task-d[unit-d]");
    }

    @Test
    void aUnitWithoutADeadlineRendersItsBudgetAsUnbounded() {
        Pool shared = pronePool("shared");
        TaskGraphReport report = detect(TaskGraph.builder()
                .admit(shared.node("outer"), shared.unboundedNode("inner"))
                .build());

        assertThat(report.taskEdges()).endsWith("budget=unbounded}").doesNotContain("PT");
    }

    @Test
    void anyIssueIsEquivalentToIssueStatus() {
        for (TaskGraphReport report : new TaskGraphReport[] {
            TaskGraphReport.disabled(), detect(TaskGraph.empty()), detect(selfLoop()), detect(twoPoolCycle())
        }) {
            assertThat(report.anyIssue()).isEqualTo(report.status() == TaskGraphReport.Status.ISSUE);
            assertThat(report.anyIssue()).isEqualTo(report.executorCycle());
        }
    }

    @Test
    void toStringKeepsTheLogFieldFormat() {
        TaskGraphReport report = detect(selfLoop());
        assertThat(report.toString())
                .startsWith("executorCycle=true, executorSelfLoop=true, ")
                .contains("taskEdges=[" + report.taskEdges() + "]")
                .contains("executorEdges=[" + report.executorEdges() + "]")
                .doesNotContain("taskCycle");
    }
}

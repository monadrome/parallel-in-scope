package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Contract tests for {@link TaskGraphReport} diagnostics. */
class TaskGraphReportTest {

    @Test
    void disabledReportHasNoFlagsAndEmptyEdges() {
        TaskGraphReport report = TaskGraphReport.disabled();
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.DISABLED);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.taskCycle()).isFalse();
        assertThat(report.selfLoop()).isFalse();
        assertThat(report.executorCycle()).isFalse();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
    }

    @Test
    void detectionWithoutIssueIsNoIssueWithEmptyEdges() {
        TaskGraphReport report = TaskGraphReport.detection(false, false, false, false, "", "");
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
    }

    @Test
    void detectionWithSelfLoopIsIssue() {
        TaskGraphReport report = TaskGraphReport.detection(false, true, false, false, "tasks", "execs");
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.selfLoop()).isTrue();
        assertThat(report.taskEdges()).isEqualTo("tasks");
        assertThat(report.executorEdges()).isEqualTo("execs");
    }

    @Test
    void detectionWithAnyFlagIsIssue() {
        assertThat(TaskGraphReport.detection(true, false, false, false, "t", "e")
                        .status())
                .isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(TaskGraphReport.detection(false, false, true, false, "t", "e")
                        .anyIssue())
                .isTrue();
        assertThat(TaskGraphReport.detection(false, false, false, true, "t", "e")
                        .anyIssue())
                .isTrue();
    }

    @Test
    void anyIssueIsEquivalentToIssueStatus() {
        assertThat(TaskGraphReport.disabled().anyIssue()).isFalse();
        assertThat(TaskGraphReport.detection(false, false, false, false, "", "").anyIssue())
                .isEqualTo(TaskGraphReport.detection(false, false, false, false, "", "")
                                .status()
                        == TaskGraphReport.Status.ISSUE);
        TaskGraphReport issue = TaskGraphReport.detection(true, false, false, false, "t", "e");
        assertThat(issue.anyIssue()).isEqualTo(issue.status() == TaskGraphReport.Status.ISSUE);
    }

    @Test
    void toStringKeepsTheLogFieldFormat() {
        TaskGraphReport report = TaskGraphReport.detection(true, true, true, true, "task-edges", "exec-edges");
        assertThat(report.toString())
                .contains("taskCycle=true")
                .contains("taskEdges=[task-edges]")
                .contains("executorEdges=[exec-edges]");
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.ThreadPoolStarvationReport.Evidence;
import io.github.monadrome.parallelinscope.ThreadPoolStarvationReport.Status;
import io.github.monadrome.parallelinscope.ThreadPoolStarvationReport.WorkloadMeasurement;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Status priority of the starvation report: starvation evidence, then a low-CPU issue, then a
 * sampling failure, then insufficient visibility of executors or thread CPU time.
 */
class ThreadPoolStarvationReportTest {
    private static final Duration INTERVAL = Duration.ofMillis(20);
    private static final List<Evidence> EVIDENCE = Collections.singletonList(new Evidence("worker", 2, 2, 1, 0, 1));
    private static final List<WorkloadMeasurement> ISSUE = Collections.singletonList(measurement(true, false));
    private static final List<WorkloadMeasurement> DRIFT = Collections.singletonList(measurement(false, true));
    private static final List<String> UNSUPPORTED = Collections.singletonList("foreign");

    @Test
    void starvationEvidenceOutranksALowCpuIssue() {
        ThreadPoolStarvationReport report = report(EVIDENCE, ISSUE, UNSUPPORTED, false, "failed");

        assertThat(report.status()).isEqualTo(Status.POTENTIAL_STARVATION);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.workloadMeasurements()).isEqualTo(ISSUE);
    }

    @Test
    void aLowCpuIssueOutranksFailureAndVisibility() {
        ThreadPoolStarvationReport report = report(Collections.emptyList(), ISSUE, UNSUPPORTED, false, "failed");

        assertThat(report.status()).isEqualTo(Status.LOW_CPU_UTILIZATION);
        assertThat(report.anyIssue()).isTrue();
    }

    @Test
    void suspectedDriftAloneNeverChangesTheStatus() {
        assertThat(report(Collections.emptyList(), DRIFT, Collections.emptyList(), true, null)
                        .status())
                .isEqualTo(Status.NO_ISSUE);
        assertThat(report(Collections.emptyList(), DRIFT, Collections.emptyList(), true, "failed")
                        .status())
                .isEqualTo(Status.SAMPLING_FAILED);
    }

    @Test
    void unreadableCpuTimeIsInsufficientVisibilityBelowASamplingFailure() {
        ThreadPoolStarvationReport unreadable =
                report(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, null);

        assertThat(unreadable.status()).isEqualTo(Status.INSUFFICIENT_VISIBILITY);
        assertThat(unreadable.anyIssue()).isFalse();
        assertThat(report(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, "failed")
                        .status())
                .isEqualTo(Status.SAMPLING_FAILED);
        assertThat(report(Collections.emptyList(), Collections.emptyList(), UNSUPPORTED, true, null)
                        .status())
                .isEqualTo(Status.INSUFFICIENT_VISIBILITY);
        assertThat(report(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), true, null)
                        .status())
                .isEqualTo(Status.NO_ISSUE);
    }

    @Test
    void aMeasurementWithoutExecutionTimeHasNoRatio() {
        WorkloadMeasurement empty = new WorkloadMeasurement(
                ParId.of("cpu"), "work", TaskType.COMPUTE, TaskType.UNKNOWN, 0, 1, 0, 0, 0, false, false);

        assertThat(empty.cpuToExecutionRatio()).isNaN();
        assertThat(empty.cpuTime()).isEqualTo(Duration.ZERO);
        assertThat(empty.excludedBodies()).isEqualTo(1);
    }

    private static ThreadPoolStarvationReport report(
            List<Evidence> evidence,
            List<WorkloadMeasurement> measurements,
            List<String> unsupported,
            boolean cpuTimeAvailable,
            @Nullable String failure) {
        return ThreadPoolStarvationReport.of(
                3, INTERVAL, evidence, measurements, unsupported, Collections.emptyList(), cpuTimeAvailable, failure);
    }

    private static WorkloadMeasurement measurement(boolean issue, boolean drift) {
        return new WorkloadMeasurement(
                ParId.of("cpu"),
                "work",
                issue ? TaskType.COMPUTE : TaskType.UNKNOWN,
                drift ? TaskType.COMPUTE : TaskType.UNKNOWN,
                20,
                0,
                20_000_000L,
                200_000_000L,
                20,
                issue,
                drift);
    }
}

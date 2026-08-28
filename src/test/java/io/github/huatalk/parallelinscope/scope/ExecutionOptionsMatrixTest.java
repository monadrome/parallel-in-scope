package io.github.huatalk.parallelinscope.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Getter/builder matrix over {@link ExecutionOptions}: chained builder calls, default values,
 * per-field round trips, and timeout validation.
 */
class ExecutionOptionsMatrixTest {

    @Test
    void defaultsComeFromTheBuilderFieldInitializers() {
        ExecutionOptions options = ExecutionOptions.of("n").build();
        assertThat(options.taskName()).isEqualTo("n");
        assertThat(options.parallelism()).isEqualTo(-1);
        assertThat(options.timeout()).isNull();
        assertThat(options.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(options.rejectEnqueue()).isTrue();
    }

    @Test
    void chainedBuilderCallsKeepReturningTheBuilder() {
        ExecutionOptions.Builder builder = ExecutionOptions.builder();
        ExecutionOptions options = builder
                .taskName("chain")
                .parallelism(3)
                .timeout(Duration.ofSeconds(2))
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        assertThat(options.taskName()).isEqualTo("chain");
        assertThat(options.parallelism()).isEqualTo(3);
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(options.rejectEnqueue()).isFalse();

        // The built snapshot is immune to later builder mutations.
        builder.taskType(TaskType.CPU_BOUND);
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
    }

    @Test
    void explicitNullTimeoutStaysUnsetWhileNonPositiveValuesAreRejected() {
        ExecutionOptions.Builder builder = ExecutionOptions.of("t");
        builder.timeout(null);
        assertThat(builder.build().timeout()).isNull();

        assertThatThrownBy(() -> ExecutionOptions.of("t").timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionOptions.of("t").timeout(Duration.ofMillis(-5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothRejectEnqueuePolaritiesRoundTrip() {
        assertThat(ExecutionOptions.of("a").rejectEnqueue(true).build().rejectEnqueue())
                .isTrue();
        assertThat(ExecutionOptions.of("b").rejectEnqueue(false).build().rejectEnqueue())
                .isFalse();
    }

    @Test
    void taskNameIsPreservedVerbatim() {
        String longName = "order-pipeline-stage-7";
        assertThat(ExecutionOptions.of(longName).build().taskName()).isEqualTo(longName);
    }
}

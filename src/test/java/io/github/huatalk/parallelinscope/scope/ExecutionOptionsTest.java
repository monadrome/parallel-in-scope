package io.github.huatalk.parallelinscope.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ExecutionOptionsTest {
    @Test
    void remainsAnImmutablePerCallInput() {
        ExecutionOptions options = ExecutionOptions.of("load")
                .parallelism(3)
                .timeout(Duration.ofSeconds(2))
                .taskType(TaskType.IO_BOUND)
                .build();

        assertThat(options.taskName()).isEqualTo("load");
        assertThat(options.parallelism()).isEqualTo(3);
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
    }

    @Test
    void rejectsNonPositiveExplicitTimeouts() {
        assertThatThrownBy(() -> ExecutionOptions.of("load").timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionOptions.of("load").timeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsAllExecutionHints() {
        ExecutionOptions options = ExecutionOptions.of("write")
                .parallelism(7)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        assertThat(options.parallelism()).isEqualTo(7);
        assertThat(options.rejectEnqueue()).isFalse();
        assertThat(options.timeout()).isNull();
    }

    @Test
    void resolvesExplicitTimeoutAndParallelismIntoBatchContext() {
        BatchExecutionContext context = BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().defaultTimeoutMillis(1_000).build(), ExecutionOptions.of("write")
                .parallelism(8)
                .timeout(Duration.ofSeconds(3))
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build(), 2, null);

        assertThat(context.taskName()).isEqualTo("write");
        assertThat(context.effectiveParallelism()).isEqualTo(2);
        assertThat(context.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(context.rejectEnqueue()).isFalse();
    }

    @Test
    void resolvesAbsentTimeoutUsingGlobalDefault() {
        BatchExecutionContext context = BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().defaultTimeoutMillis(2_500).build(),
                ExecutionOptions.of("read").parallelism(-1).build(), 4, null);

        assertThat(context.effectiveParallelism()).isEqualTo(4);
        assertThat(context.remaining().toMillis()).isLessThanOrEqualTo(2_500);
    }
}

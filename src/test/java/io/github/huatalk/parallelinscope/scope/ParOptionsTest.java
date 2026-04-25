package io.github.huatalk.parallelinscope.scope;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ParOptions.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ParOptionsTest {

    @Test
    public void testBuilder_defaults() {
        ParOptions options = ParOptions.of("myTask").build();
        assertThat(options.getTaskName()).isEqualTo("myTask");
        assertThat(options.getParallelism()).isEqualTo(-1);
        assertThat(options.getTimeout()).isZero();
        assertThat(options.getTaskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(options.isRejectEnqueue()).isTrue();
    }

    @Test
    public void testIoTask() {
        ParOptions options = ParOptions.ioTask("ioTask").build();
        assertThat(options.getTaskType()).isEqualTo(TaskType.IO_BOUND);
    }

    @Test
    public void testCriticalIoTask() {
        ParOptions options = ParOptions.criticalIoTask("critical", 3000).build();
        assertThat(options.getTaskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(options.timeoutMillis()).isEqualTo(3000);
    }

    @Test
    public void testTimeoutMillis_conversion() {
        ParOptions options = ParOptions.of("test")
                .timeout(5)
                .timeUnit(TimeUnit.SECONDS)
                .build();
        assertThat(options.timeoutMillis()).isEqualTo(5000);
    }

    @Test
    public void testFormalized() {
        ParOptions original = ParOptions.of("test")
                .parallelism(100)
                .build();
        ParOptions formalized = ParOptions.formalized(original, 10, 60_000L);

        // Parallelism should be constrained to taskSize
        assertThat(formalized.getParallelism()).isEqualTo(10);
        // Timeout should use default
        assertThat(formalized.timeoutMillis()).isPositive();
    }

    @Test
    public void testFormalized_negativeParallelism() {
        ParOptions original = ParOptions.of("test")
                .parallelism(-1)
                .build();
        ParOptions formalized = ParOptions.formalized(original, 5, 60_000L);
        assertThat(formalized.getParallelism()).isEqualTo(5);
    }

    @Test
    public void testWithTimeout() {
        ParOptions options = ParOptions.of("test").timeout(1000).build();
        ParOptions updated = options.withTimeout(5000);
        assertThat(updated.getTimeout()).isEqualTo(5000);
        assertThat(updated.getTaskName()).isEqualTo("test");
    }
}

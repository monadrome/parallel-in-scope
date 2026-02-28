package io.github.linzee1.concurrent;

import io.github.linzee1.concurrent.scope.ParallelOptions;
import io.github.linzee1.concurrent.scope.TaskType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParallelOptions.
 *
 * @author linqh
 */
public class ParallelOptionsTest {

    @Test
    public void testBuilder_defaults() {
        ParallelOptions options = ParallelOptions.of("myTask").build();
        assertEquals("myTask", options.getTaskName());
        assertEquals(-1, options.getParallelism());
        assertEquals(0, options.getTimeout());
        assertEquals(TaskType.CPU_BOUND, options.getTaskType());
        assertEquals(3, options.getPriority());
        assertTrue(options.isEnableParallel());
        assertTrue(options.isRejectEnqueue());
        assertFalse(options.isFailFast());
    }

    @Test
    public void testIoTask() {
        ParallelOptions options = ParallelOptions.ioTask("ioTask").build();
        assertEquals(TaskType.IO_BOUND, options.getTaskType());
        assertEquals(2, options.getPriority());
    }

    @Test
    public void testCriticalIoTask() {
        ParallelOptions options = ParallelOptions.criticalIoTask("critical", 3000).build();
        assertEquals(TaskType.IO_BOUND, options.getTaskType());
        assertEquals(1, options.getPriority());
        assertEquals(3000, options.timeoutMillis());
    }

    @Test
    public void testTimeoutMillis_conversion() {
        ParallelOptions options = ParallelOptions.of("test")
                .timeout(5)
                .timeUnit(TimeUnit.SECONDS)
                .build();
        assertEquals(5000, options.timeoutMillis());
    }

    @Test
    public void testFormalized() {
        ParallelOptions original = ParallelOptions.of("test")
                .parallelism(100)
                .build();
        ParallelOptions formalized = ParallelOptions.formalized(original, 10);

        // Parallelism should be constrained to taskSize
        assertEquals(10, formalized.getParallelism());
        // Timeout should use default
        assertTrue(formalized.timeoutMillis() > 0);
    }

    @Test
    public void testFormalized_negativeParallelism() {
        ParallelOptions original = ParallelOptions.of("test")
                .parallelism(-1)
                .build();
        ParallelOptions formalized = ParallelOptions.formalized(original, 5);
        assertEquals(5, formalized.getParallelism());
    }

    @Test
    public void testWithTimeout() {
        ParallelOptions options = ParallelOptions.of("test").timeout(1000).build();
        ParallelOptions updated = options.withTimeout(5000);
        assertEquals(5000, updated.getTimeout());
        assertEquals("test", updated.getTaskName());
    }
}

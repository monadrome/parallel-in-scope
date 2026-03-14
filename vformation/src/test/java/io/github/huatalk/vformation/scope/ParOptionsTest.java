package io.github.huatalk.vformation.scope;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParOptions.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class ParOptionsTest {

    @Test
    public void testBuilder_defaults() {
        ParOptions options = ParOptions.of("myTask").build();
        assertEquals("myTask", options.getTaskName());
        assertEquals(-1, options.getParallelism());
        assertEquals(0, options.getTimeout());
        assertEquals(TaskType.CPU_BOUND, options.getTaskType());
        assertEquals(3, options.getPriority());
        assertTrue(options.isRejectEnqueue());
    }

    @Test
    public void testIoTask() {
        ParOptions options = ParOptions.ioTask("ioTask").build();
        assertEquals(TaskType.IO_BOUND, options.getTaskType());
        assertEquals(2, options.getPriority());
    }

    @Test
    public void testCriticalIoTask() {
        ParOptions options = ParOptions.criticalIoTask("critical", 3000).build();
        assertEquals(TaskType.IO_BOUND, options.getTaskType());
        assertEquals(1, options.getPriority());
        assertEquals(3000, options.timeoutMillis());
    }

    @Test
    public void testTimeoutMillis_conversion() {
        ParOptions options = ParOptions.of("test")
                .timeout(5)
                .timeUnit(TimeUnit.SECONDS)
                .build();
        assertEquals(5000, options.timeoutMillis());
    }

    @Test
    public void testFormalized() {
        ParOptions original = ParOptions.of("test")
                .parallelism(100)
                .build();
        ParOptions formalized = ParOptions.formalized(original, 10, 60_000L);

        // Parallelism should be constrained to taskSize
        assertEquals(10, formalized.getParallelism());
        // Timeout should use default
        assertTrue(formalized.timeoutMillis() > 0);
    }

    @Test
    public void testFormalized_negativeParallelism() {
        ParOptions original = ParOptions.of("test")
                .parallelism(-1)
                .build();
        ParOptions formalized = ParOptions.formalized(original, 5, 60_000L);
        assertEquals(5, formalized.getParallelism());
    }

    @Test
    public void testWithTimeout() {
        ParOptions options = ParOptions.of("test").timeout(1000).build();
        ParOptions updated = options.withTimeout(5000);
        assertEquals(5000, updated.getTimeout());
        assertEquals("test", updated.getTaskName());
    }
}

package io.github.linzee1.vformation;

import com.google.common.base.Ticker;
import io.github.linzee1.vformation.cancel.CancellationToken;
import io.github.linzee1.vformation.context.TaskScopeTl;
import io.github.linzee1.vformation.internal.ScopedCallable;
import io.github.linzee1.vformation.scope.ParConfig;
import io.github.linzee1.vformation.scope.ParOptions;
import io.github.linzee1.vformation.spi.TaskListener;
import io.github.linzee1.vformation.spi.TaskListener.TaskEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScopedCallable lifecycle, timing, and SPI callbacks.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class ScopedCallableTest {

    private ParConfig config;

    @BeforeEach
    public void setUp() {
        config = new ParConfig();
    }

    @AfterEach
    public void tearDown() {
        TaskScopeTl.remove();
    }

    @Test
    public void testLifecycle_contextSetupAndCleanup() throws Exception {
        CancellationToken token = CancellationToken.create();
        // Use default taskName "task" to bypass checkpoint
        ParOptions options = ParOptions.of("task").build();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            // During execution, TaskScopeTl should be initialized
            assertNotNull(TaskScopeTl.getCancellationToken());
            assertNotNull(TaskScopeTl.getParallelOptions());
            return "result";
        }, config);

        callable.setTtlAttachment(ScopedCallable.KEY_CANCELLATION_TOKEN, token);
        callable.setTtlAttachment(ScopedCallable.KEY_PARALLEL_OPTIONS, options);
        callable.setTtlAttachment(ScopedCallable.KEY_EXECUTOR_NAME, "test-pool");

        String result = callable.call();
        assertEquals("result", result);

        // After call, TaskScopeTl should be cleaned up
        assertNull(TaskScopeTl.getCancellationToken());
        assertNull(TaskScopeTl.getParallelOptions());
    }

    @Test
    public void testTimingMetrics_withFakeTicker() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000);
        Ticker fakeTicker = new Ticker() {
            @Override
            public long read() {
                return nanos.get();
            }
        };

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            // Simulate 5ms execution
            nanos.addAndGet(5_000_000);
            return "ok";
        }, config, fakeTicker);

        // Use default taskName "task" to bypass checkpoint
        callable.setTtlAttachment(ScopedCallable.KEY_PARALLEL_OPTIONS, ParOptions.of("task").build());
        callable.setTtlAttachment(ScopedCallable.KEY_CANCELLATION_TOKEN, CancellationToken.create());

        // Simulate 2ms queue wait
        nanos.addAndGet(2_000_000);

        callable.call();

        assertEquals(5_000_000, callable.executionTime());
        assertEquals(2_000_000, callable.waitTime());
        assertEquals(7_000_000, callable.totalTime());
    }

    @Test
    public void testListener_notifiedOnSuccess() throws Exception {
        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        config.addTaskListener(events::add);

        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> "ok", config);
        callable.setTtlAttachment(ScopedCallable.KEY_PARALLEL_OPTIONS, ParOptions.of("task").build());
        callable.setTtlAttachment(ScopedCallable.KEY_CANCELLATION_TOKEN, CancellationToken.create());

        callable.call();

        assertEquals(1, events.size());
        TaskEvent event = events.get(0);
        assertEquals("myTask", event.getTaskName());
        assertNull(event.getException());
        assertTrue(event.executionTimeNanos() >= 0);
    }

    @Test
    public void testListener_notifiedOnFailure() {
        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        config.addTaskListener(events::add);

        RuntimeException error = new RuntimeException("test error");
        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> {
            throw error;
        }, config);
        callable.setTtlAttachment(ScopedCallable.KEY_PARALLEL_OPTIONS, ParOptions.of("task").build());
        callable.setTtlAttachment(ScopedCallable.KEY_CANCELLATION_TOKEN, CancellationToken.create());

        assertThrows(RuntimeException.class, callable::call);

        assertEquals(1, events.size());
        assertSame(error, events.get(0).getException());
    }

    @Test
    public void testListenerException_swallowed() throws Exception {
        config.addTaskListener(event -> {
            throw new RuntimeException("listener boom");
        });

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok", config);
        callable.setTtlAttachment(ScopedCallable.KEY_PARALLEL_OPTIONS, ParOptions.of("task").build());
        callable.setTtlAttachment(ScopedCallable.KEY_CANCELLATION_TOKEN, CancellationToken.create());

        // Should not throw even though listener throws
        String result = callable.call();
        assertEquals("ok", result);
    }
}

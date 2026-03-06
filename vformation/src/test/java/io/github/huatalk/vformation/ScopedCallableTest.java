package io.github.huatalk.vformation;

import com.google.common.base.Ticker;
import io.github.huatalk.vformation.spi.TaskListener;
import io.github.huatalk.vformation.spi.TaskListener.TaskEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScopedCallable lifecycle, timing, and SPI callbacks.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ScopedCallableTest {

    private ParConfig config;

    @BeforeEach
    public void setUp() {
        config = ParConfig.builder().build();
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
        }, config, options, token, "test-pool");

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

        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        ParConfig listenerConfig = ParConfig.builder()
                .taskListener(events::add)
                .build();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            // Simulate 5ms execution
            nanos.addAndGet(5_000_000);
            return "ok";
        }, listenerConfig, fakeTicker, ParOptions.of("task").build(), CancellationToken.create(), "NA");

        // Simulate 2ms queue wait
        nanos.addAndGet(2_000_000);

        callable.call();

        // Verify timing through TaskEvent SPI (timing methods are package-private)
        assertEquals(1, events.size());
        TaskEvent event = events.get(0);
        assertEquals(5_000_000, event.executionTimeNanos());
        assertEquals(2_000_000, event.waitTimeNanos());
        assertEquals(7_000_000, event.totalTimeNanos());
    }

    @Test
    public void testListener_notifiedOnSuccess() throws Exception {
        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        ParConfig listenerConfig = ParConfig.builder()
                .taskListener(events::add)
                .build();

        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> "ok", listenerConfig,
                ParOptions.of("task").build(), CancellationToken.create());

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
        ParConfig listenerConfig = ParConfig.builder()
                .taskListener(events::add)
                .build();

        RuntimeException error = new RuntimeException("test error");
        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> {
            throw error;
        }, listenerConfig, ParOptions.of("task").build(), CancellationToken.create());

        assertThrows(RuntimeException.class, callable::call);

        assertEquals(1, events.size());
        assertSame(error, events.get(0).getException());
    }

    @Test
    public void testListenerException_swallowed() throws Exception {
        ParConfig listenerConfig = ParConfig.builder()
                .taskListener(event -> {
                    throw new RuntimeException("listener boom");
                })
                .build();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok", listenerConfig,
                ParOptions.of("task").build(), CancellationToken.create());

        // Should not throw even though listener throws
        String result = callable.call();
        assertEquals("ok", result);
    }

    @Test
    public void testCurrent_availableDuringExecution() throws Exception {
        AtomicReference<ScopedCallable<?>> captured = new AtomicReference<>();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            captured.set(ScopedCallable.current());
            return "ok";
        }, config, ParOptions.of("task").build(), CancellationToken.create());

        callable.call();

        assertSame(callable, captured.get());
    }

    @Test
    public void testCurrent_nullOutsideExecution() {
        assertNull(ScopedCallable.current());
    }

    @Test
    public void testCurrent_cleanedUpAfterException() {
        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            throw new RuntimeException("boom");
        }, config, ParOptions.of("task").build(), CancellationToken.create());

        assertThrows(RuntimeException.class, callable::call);
        assertNull(ScopedCallable.current());
    }
}

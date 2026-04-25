package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import com.google.common.base.Ticker;
import io.github.huatalk.parallelinscope.spi.TaskListener;
import io.github.huatalk.parallelinscope.spi.TaskListener.TaskEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

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
            assertThat(TaskScopeTl.getCancellationToken()).isNotNull();
            assertThat(TaskScopeTl.getParallelOptions()).isNotNull();
            return "result";
        }, config, options, token, "test-pool");

        String result = callable.call();
        assertThat(result).isEqualTo("result");

        // After call, TaskScopeTl should be cleaned up
        assertThat(TaskScopeTl.getCancellationToken()).isNull();
        assertThat(TaskScopeTl.getParallelOptions()).isNull();
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
        assertThat(events).hasSize(1);
        TaskEvent event = events.get(0);
        assertThat(event.executionTimeNanos()).isEqualTo(5_000_000);
        assertThat(event.waitTimeNanos()).isEqualTo(2_000_000);
        assertThat(event.totalTimeNanos()).isEqualTo(7_000_000);
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

        assertThat(events).hasSize(1);
        TaskEvent event = events.get(0);
        assertThat(event.getTaskName()).isEqualTo("myTask");
        assertThat(event.getException()).isNull();
        assertThat(event.executionTimeNanos()).isNotNegative();
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

        assertThatThrownBy(callable::call)
                .isInstanceOf(RuntimeException.class);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getException()).isSameAs(error);
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
        assertThat(result).isEqualTo("ok");
    }

    @Test
    public void testCurrent_availableDuringExecution() throws Exception {
        AtomicReference<ScopedCallable<?>> captured = new AtomicReference<>();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            captured.set(ScopedCallable.current());
            return "ok";
        }, config, ParOptions.of("task").build(), CancellationToken.create());

        callable.call();

        assertThat(captured.get()).isSameAs(callable);
    }

    @Test
    public void testCurrent_nullOutsideExecution() {
        assertThat(ScopedCallable.current()).isNull();
    }

    @Test
    public void testCurrent_cleanedUpAfterException() {
        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> {
            throw new RuntimeException("boom");
        }, config, ParOptions.of("task").build(), CancellationToken.create());

        assertThatThrownBy(callable::call)
                .isInstanceOf(RuntimeException.class);
        assertThat(ScopedCallable.current()).isNull();
    }
}

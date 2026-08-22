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
        // call() leaves ThreadRelay's current maps populated; clear them so
        // later tests (e.g. ThreadRelayTest) observe a clean thread state.
        ThreadRelay.setCurrentTaskName(null);
        ThreadRelay.setCurrentExecutorName(null);
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
        assertThat(event.executionTime().toNanos()).isEqualTo(5_000_000);
        assertThat(event.waitTime().toNanos()).isEqualTo(2_000_000);
        assertThat(event.totalTime().toNanos()).isEqualTo(7_000_000);
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
        assertThat(event.executionTime().toNanos()).isNotNegative();
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

    // ==================== executor name & toString ====================

    @Test
    public void testGetExecutorName_preservesExplicitName() {
        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok", config,
                ParOptions.of("task").build(), CancellationToken.create(), "query-pool");
        assertThat(callable.getExecutorName()).isEqualTo("query-pool");
    }

    @Test
    public void testGetExecutorName_nullFallsBackToNA() {
        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok", config,
                ParOptions.of("task").build(), CancellationToken.create());
        assertThat(callable.getExecutorName()).isEqualTo("NA");
    }

    @Test
    public void testToString_containsTaskMetadata() {
        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> "ok", config,
                ParOptions.of("task").build(), CancellationToken.create(), "query-pool");
        assertThat(callable.toString())
                .contains("myTask")
                .contains("ScopedCallable{");
    }

    // ==================== ThreadRelay context during call ====================

    @Test
    public void testThreadRelay_contextFilledDuringCall() throws Exception {
        AtomicReference<String> taskNameSeen = new AtomicReference<>();
        AtomicReference<String> executorNameSeen = new AtomicReference<>();

        CancellationToken token = CancellationToken.create();
        ParOptions options = ParOptions.of("nested").build();

        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> {
            taskNameSeen.set(ThreadRelay.getCurrentTaskName());
            executorNameSeen.set(ThreadRelay.getCurrentExecutorName());
            return "ok";
        }, config, options, token, "query-pool");

        callable.call();

        assertThat(taskNameSeen.get()).isEqualTo("myTask");
        assertThat(executorNameSeen.get()).isEqualTo("query-pool");
    }

    @Test
    public void testCancelledToken_checkpointThrowsLeanCancellation() {
        CancellationToken token = CancellationToken.create();
        token.cancel(false);

        // The checkpoint only fires when the callable's taskName matches the current options'
        // task name, mirroring the "named task" cancellation contract.
        ScopedCallable<String> callable = new ScopedCallable<>("myTask", () -> "ok", config,
                ParOptions.of("myTask").build(), token, "query-pool");

        assertThatThrownBy(callable::call)
                .isInstanceOf(LeanCancellationException.class);
    }

    // ==================== enqueued classification ====================

    @Test
    public void testEnqueuedFlag_waitedTask_classifiedAsEnqueued() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000); // submit at 1ms
        Ticker fakeTicker = ticker(nanos);

        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        ParConfig listenerConfig = ParConfig.builder().taskListener(events::add).build();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok",
                listenerConfig, fakeTicker, ParOptions.of("task").build(), CancellationToken.create(), "NA");

        nanos.addAndGet(4_000_000); // wait 4ms > 3ms threshold -> enqueued
        callable.call();

        assertThat(events.get(0).isEnqueued()).isTrue();
    }

    @Test
    public void testEnqueuedFlag_fastTask_notClassifiedAsEnqueued() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000);
        Ticker fakeTicker = ticker(nanos);

        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        ParConfig listenerConfig = ParConfig.builder().taskListener(events::add).build();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok",
                listenerConfig, fakeTicker, ParOptions.of("task").build(), CancellationToken.create(), "NA");

        nanos.addAndGet(2_000_000); // wait 2ms <= 3ms threshold -> not enqueued
        callable.call();

        assertThat(events.get(0).isEnqueued()).isFalse();
    }

    @Test
    public void testEnqueuedFlag_fractionalWaitNotEnqueued() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000);
        Ticker fakeTicker = ticker(nanos);

        CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
        ParConfig listenerConfig = ParConfig.builder().taskListener(events::add).build();

        ScopedCallable<String> callable = new ScopedCallable<>("task", () -> "ok",
                listenerConfig, fakeTicker, ParOptions.of("task").build(), CancellationToken.create(), "NA");

        nanos.addAndGet(3_500_000); // wait 3.5ms -> waitMs = 3 (integer division) -> not enqueued
        callable.call();

        assertThat(events.get(0).isEnqueued()).isFalse();
    }

    private static Ticker ticker(AtomicLong nanos) {
        return new Ticker() {
            @Override
            public long read() {
                return nanos.get();
            }
        };
    }
}

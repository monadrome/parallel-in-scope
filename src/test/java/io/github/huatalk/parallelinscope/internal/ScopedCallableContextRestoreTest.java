package io.github.huatalk.parallelinscope.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.context.ThreadRelay;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.BatchExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import io.github.huatalk.parallelinscope.spi.TaskListener.TaskEvent;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScopedCallableContextRestoreTest {
    @Test
    void listenerReceivesSuccessfulTaskTimingAndMetadata() throws Exception {
        BatchExecutionContext context = context("listener");
        AtomicReference<TaskEvent> captured = new AtomicReference<>();
        ScopedCallable<String> callable =
                new ScopedCallable<>(task(context, 0), () -> "value", Collections.singletonList(captured::set));

        assertThat(callable.call()).isEqualTo("value");
        TaskEvent event = captured.get();
        assertThat(event).isNotNull();
        assertThat(event.getTaskName()).isEqualTo("listener");
        assertThat(event.getException()).isNull();
        assertThat(event.getEndTimeNanos()).isGreaterThanOrEqualTo(event.getStartTimeNanos());
        assertThat(callable.executionTime()).isGreaterThanOrEqualTo(0L);
        assertThat(callable.waitTime()).isGreaterThanOrEqualTo(0L);
        assertThat(callable.totalTime()).isGreaterThanOrEqualTo(callable.executionTime());
        assertThat(callable.getCancellationToken()).isSameAs(context.cancellationToken());
        assertThat(callable.getExecutorName()).isEqualTo("NA");
        assertThat(callable.toString()).contains("listener", "submitTime", "startTime", "endTime");
    }

    @Test
    void listenerReceivesFailureWhileOriginalFailureEscapes() {
        BatchExecutionContext context = context("failed");
        AtomicReference<TaskEvent> captured = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("boom");
        ScopedCallable<String> callable = new ScopedCallable<>(
                task(context, 0),
                () -> {
                    throw failure;
                },
                Collections.singletonList(captured::set));

        org.assertj.core.api.Assertions.assertThatThrownBy(callable::call).isSameAs(failure);
        assertThat(captured.get().getException()).isSameAs(failure);
    }

    @Test
    void nestedCallRestoresOuterBatchRelayAndCurrentCallable() throws Exception {
        ThreadRelay.clearCurrent();
        try {
            BatchExecutionContext outer = context("same-name");
            BatchExecutionContext inner = BatchExecutionContext.resolve(
                    GlobalExecutionPolicy.builder().build(),
                    BatchExecutionOptions.of("same-name").build(),
                    1,
                    outer);
            AtomicReference<ScopedCallable<String>> outerReference = new AtomicReference<>();
            ScopedCallable<String> outerCallable = new ScopedCallable<>(
                    task(outer, 0),
                    () -> {
                        assertThat(TaskScopeTl.getBatchExecutionContext()).isSameAs(outer);
                        assertThat(ThreadRelay.getCurrentCancellationToken()).isSameAs(outer.cancellationToken());
                        assertThat(ScopedCallable.current()).isSameAs(outerReference.get());

                        ScopedCallable<String> innerCallable = new ScopedCallable<>(
                                task(inner, 0),
                                () -> {
                                    assertThat(TaskScopeTl.getBatchExecutionContext())
                                            .isSameAs(inner);
                                    assertThat(ScopedCallable.current()).isNotSameAs(outerReference.get());
                                    return "inner";
                                },
                                null);
                        assertThat(innerCallable.call()).isEqualTo("inner");

                        assertThat(TaskScopeTl.getBatchExecutionContext()).isSameAs(outer);
                        assertThat(ThreadRelay.getCurrentCancellationToken()).isSameAs(outer.cancellationToken());
                        assertThat(ScopedCallable.current()).isSameAs(outerReference.get());
                        return "outer";
                    },
                    null);
            outerReference.set(outerCallable);

            assertThat(outerCallable.call()).isEqualTo("outer");
            assertThat(TaskScopeTl.getBatchExecutionContext()).isNull();
            assertThat(ThreadRelay.getCurrentCancellationToken()).isNull();
            assertThat(ScopedCallable.current()).isNull();
        } finally {
            ThreadRelay.clearCurrent();
        }
    }

    private static BatchExecutionContext context(String name) {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().build(),
                BatchExecutionOptions.of(name).build(),
                1,
                null);
    }

    private static TaskExecutionContext task(BatchExecutionContext context, int index) {
        return new TaskExecutionContext(
                context, index, com.google.common.base.Ticker.systemTicker().read());
    }
}

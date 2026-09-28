package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScopedCallableContextRestoreTest {
    @Test
    void observationReceivesSuccessfulTaskTimingAndMetadata() throws Exception {
        MultiTaskContext context = context("observed");
        TaskExecutionContext taskContext = task(context, 0);
        ExecutionPhaseHintFuture<String> future = TaskSubmissions.prepare(taskContext, () -> "value", phase -> {});

        future.run();

        TaskCompletion<String> event =
                Objects.requireNonNull(future.observation()).view().get(2, TimeUnit.SECONDS);
        assertThat(event.unitId()).isEqualTo(context.unitId());
        assertThat(event.taskIndex()).isEqualTo(0);
        assertThat(event.submitTimeNanos()).isEqualTo(taskContext.submitTimeNanos());
        assertThat(event.successful()).isTrue();
        assertThat(event.result()).isEqualTo("value");
        assertThat(event.taskName()).isEqualTo("observed");
        assertThat(event.failure()).isNull();
        assertThat(event.endTimeNanos()).isGreaterThanOrEqualTo(event.startTimeNanos());
        assertThat(taskContext.executionTimeNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(taskContext.waitTimeNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(taskContext.totalTimeNanos()).isGreaterThanOrEqualTo(taskContext.executionTimeNanos());
        assertThat(context.cancellationToken()).isNotNull();
    }

    @Test
    void observationReceivesFailureWhileOriginalFailureTerminatesTheFuture() throws Exception {
        MultiTaskContext context = context("failed");
        IllegalStateException failure = new IllegalStateException("boom");
        ExecutionPhaseHintFuture<String> future = TaskSubmissions.prepare(
                task(context, 0),
                () -> {
                    throw failure;
                },
                phase -> {});

        future.run();

        assertThat(future.isDone()).isTrue();
        TaskCompletion<String> event =
                Objects.requireNonNull(future.observation()).view().get(2, TimeUnit.SECONDS);
        assertThat(event.failure()).isSameAs(failure);
        assertThat(event.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(event.successful()).isFalse();
        assertThat(event.result()).isNull();
    }

    @Test
    void nestedCallRestoresOuterCurrentTask() throws Exception {
        MultiTaskContext outer = context("same-name");
        MultiTaskContext inner = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("same-name", Duration.ofSeconds(30))
                                .spec(),
                        1)
                .structuralParent(outer));
        TaskExecutionContext outerTask = task(outer, 0);
        ScopedCallable<String> outerCallable = new ScopedCallable<>(outerTask, () -> {
            assertThat(TaskExecutionContext.current()).isSameAs(outerTask);

            TaskExecutionContext innerTask = task(inner, 0);
            ScopedCallable<String> innerCallable = new ScopedCallable<>(innerTask, () -> {
                assertThat(TaskExecutionContext.current()).isSameAs(innerTask);
                return "inner";
            });
            assertThat(innerCallable.call()).isEqualTo("inner");

            assertThat(TaskExecutionContext.current()).isSameAs(outerTask);
            return "outer";
        });
        assertThat(outerCallable.call()).isEqualTo("outer");
        assertThat(TaskExecutionContext.current()).isNull();
    }

    private static MultiTaskContext context(String name) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout(name, Duration.ofSeconds(30)).spec(), 1));
    }

    private static TaskExecutionContext task(MultiTaskContext context, int index) {
        return new TaskExecutionContext(context, index, Ticker.systemTicker().read());
    }
}

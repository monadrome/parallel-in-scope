package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MultiTaskContextTest {
    @Test
    void childDeadlineCannotOutliveParentDeadline() {
        MultiTaskContext parent = MultiTaskContext.resolve(
                BatchOptions.timeout("outer", Duration.ofMillis(100)).spec(), 1, null);
        MultiTaskContext child = MultiTaskContext.resolve(
                BatchOptions.timeout("inner", Duration.ofSeconds(10)).spec(), 1, parent);

        assertThat(child.deadlineNanos()).isLessThanOrEqualTo(parent.deadlineNanos());
        assertThat(child.cancellationToken()).isNotNull();
    }

    @Test
    void resolvesParallelismAndRuntimeMetadata() {
        BatchOptions options = BatchOptions.timeout("io", Duration.ofSeconds(30))
                .parallelism(99)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false);
        ExecutorServiceStub executor = new ExecutorServiceStub();
        ExecutorIdentity identity = new ExecutorIdentity(executor);
        MultiTaskContext context = MultiTaskContext.resolve(options.spec(), 3, null, null, identity, "http");

        assertThat(context.effectiveParallelism()).isEqualTo(3);
        assertThat(context.executorIdentity()).isSameAs(identity);
        assertThat(context.executorLabel()).isEqualTo("http");
        assertThat(context.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(context.rejectEnqueue()).isFalse();
        assertThat(context.remaining().isNegative()).isFalse();
    }

    @Test
    void rejectsNegativeTaskCount() {
        assertThatThrownBy(() -> MultiTaskContext.resolve(
                        BatchOptions.timeout("x", Duration.ofSeconds(30)).spec(), -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remainingWithoutDeadlineIsExactlyTheMaxValueSentinel() {
        // inheritTimeout against a "no enclosing deadline" ceiling resolves to the sentinel; the
        // sentinel must survive remaining() verbatim instead of being eroded by nanoTime.
        MultiTaskContext unit = MultiTaskContext.resolve(
                BatchOptions.inheritTimeout("no-deadline").spec(),
                1,
                null,
                null,
                Long.MAX_VALUE,
                System.nanoTime(),
                null,
                null,
                null);

        assertThat(unit.deadlineNanos()).isEqualTo(Long.MAX_VALUE);
        assertThat(unit.remaining()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
    }

    @Test
    void remainingWithExpiredDeadlineIsZero() {
        // Resolution happened a minute ago with a 30-second timeout, so the deadline is long
        // past: remaining() must report exactly zero, never a wrapped or negative value.
        MultiTaskContext unit = MultiTaskContext.resolve(
                BatchOptions.timeout("expired", Duration.ofSeconds(30)).spec(),
                1,
                null,
                null,
                Long.MAX_VALUE,
                System.nanoTime() - TimeUnit.MINUTES.toNanos(1),
                null,
                null,
                null);

        assertThat(unit.deadlineNanos()).isLessThan(System.nanoTime());
        assertThat(unit.remaining().isNegative()).isFalse();
        assertThat(unit.remaining().isZero()).isTrue();
    }

    @Test
    void remainingWithUnexpiredDeadlineStaysPositiveAndBounded() {
        MultiTaskContext unit = MultiTaskContext.resolve(
                BatchOptions.timeout("live", Duration.ofSeconds(30)).spec(),
                1,
                null,
                null,
                Long.MAX_VALUE,
                System.nanoTime(),
                null,
                null,
                null);

        assertThat(unit.remaining().isNegative()).isFalse();
        assertThat(unit.remaining().toNanos())
                .isGreaterThan(TimeUnit.SECONDS.toNanos(29))
                .isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(30));
    }

    @Test
    void explicitTimeoutSurvivesAResolutionClockFarInTheNegative() {
        // A platform whose nanoTime() origin sits centuries in the past: the deadline sum used to
        // wrap negative, so the explicit timeout was silently resolved as "no deadline" instead of
        // as a live deadline 30 seconds out.
        long now = -100_000L * TimeUnit.DAYS.toNanos(1);
        Duration timeout = Duration.ofSeconds(30);

        long deadline = MultiTaskContext.resolveDeadlineNanos(Optional.of(timeout), Long.MAX_VALUE, now);

        assertThat(deadline).isEqualTo(now + timeout.toNanos());
        assertThat(deadline).isLessThan(Long.MAX_VALUE);
    }

    @Test
    void inheritsParentObservationAndCreatesLinkedCancellationToken() {
        ParRuntime global = ParRuntime.builder().build();
        TaskGraphObservationScope observation = global.openTaskGraphObservation();
        try {
            MultiTaskContext parent = MultiTaskContext.resolve(
                    BatchOptions.timeout("parent", Duration.ofSeconds(30)).spec(), 2, null, observation);
            MultiTaskContext child = MultiTaskContext.resolve(
                    BatchOptions.timeout("child", Duration.ofSeconds(30)).spec(), 1, parent);

            assertThat(parent.name()).isEqualTo("parent");
            assertThat(parent.taskCount()).isEqualTo(2);
            assertThat(parent.structuralParent()).isNull();
            assertThat(child.structuralParent()).isSameAs(parent);
            assertThat(child.taskGraphObservationScope()).isSameAs(observation);
            assertThat(child.cancellationToken()).isNotSameAs(parent.cancellationToken());
            assertThat(child.executorIdentity()).isNull();
            assertThat(child.executorLabel()).isNull();
        } finally {
            observation.close();
            global.close();
        }
    }

    /** Minimal executor identity object; no tasks are submitted by this test. */
    private static final class ExecutorServiceStub extends java.util.concurrent.AbstractExecutorService {
        public void shutdown() {}

        public java.util.List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }
}

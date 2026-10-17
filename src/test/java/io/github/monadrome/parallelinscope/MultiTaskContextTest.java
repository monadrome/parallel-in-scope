package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MultiTaskContextTest {
    @Test
    void childDeadlineCannotOutliveParentDeadline() {
        MultiTaskContext parent = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("outer", Duration.ofMillis(100)).spec(), 1));
        MultiTaskContext child = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("inner", Duration.ofSeconds(10)).spec(), 1)
                .structuralParent(parent));

        assertThat(child.deadlineNanos()).isLessThanOrEqualTo(parent.deadlineNanos());
        assertThat(child.cancellationToken()).isNotNull();
    }

    @Test
    void resolvesParallelismAndRuntimeMetadata() {
        BatchOptions options = BatchOptions.timeout("io", Duration.ofSeconds(30))
                .parallelism(99)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false);
        ExecutorRuntime executor = new ExecutorRuntime(new ExecutorServiceStub());
        MultiTaskContext context = MultiTaskContext.resolve(
                MultiTaskContext.resolution(options.spec(), 3).executor(executor, ParId.of("http")));

        assertThat(context.effectiveParallelism()).isEqualTo(3);
        assertThat(context.node().executor()).isSameAs(executor.identity());
        assertThat(context.node().executorLabel()).isEqualTo("http");
        assertThat(context.node().starvationProne()).isEqualTo(executor.starvationProne());
        assertThat(context.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(context.rejectEnqueue()).isFalse();
        assertThat(context.remaining().isNegative()).isFalse();
    }

    @Test
    void theUnitResolvesExactlyOneNodeCarryingItsOwnFacts() {
        ManualClock clock = new ManualClock();
        MultiTaskContext parent = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("outer", Duration.ofSeconds(30)).spec(), 1)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler()));
        clock.advance(Duration.ofSeconds(10));
        MultiTaskContext child = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("inner", Duration.ofSeconds(60))
                                .parallelism(4)
                                .taskType(TaskType.CPU_BOUND)
                                .spec(),
                        6)
                .structuralParent(parent));

        TaskNode node = child.node();
        assertThat(child.node()).isSameAs(node);
        assertThat(node.id()).isEqualTo(child.unitId());
        assertThat(node.name()).isEqualTo("inner");
        assertThat(node.taskCount()).isEqualTo(6);
        assertThat(node.parallelism()).isEqualTo(4);
        assertThat(node.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(node.budget()).isEqualTo(Duration.ofSeconds(20));
        assertThat(node.executor()).isNull();
        assertThat(node.starvationProne()).isFalse();
        assertThat(child.structuralParentNode()).isSameAs(parent.node());
        assertThat(parent.structuralParentNode()).isNull();
        assertThat(node).isNotEqualTo(parent.node());
    }

    @Test
    void rejectsNegativeTaskCount() {
        assertThatThrownBy(() -> MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("x", Duration.ofSeconds(30)).spec(), -1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remainingWithoutDeadlineIsExactlyTheMaxValueSentinel() {
        // inheritTimeout against a "no enclosing deadline" ceiling resolves to the sentinel; the
        // sentinel must survive remaining() verbatim instead of being eroded by nanoTime.
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.inheritTimeout("no-deadline").spec(), 1)
                .deadlineCeilingNanos(Long.MAX_VALUE));

        assertThat(unit.deadlineNanos()).isEqualTo(Long.MAX_VALUE);
        assertThat(unit.remaining()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
    }

    @Test
    void remainingWithExpiredDeadlineIsZero() {
        // Resolution happened a minute ago with a 30-second timeout, so the deadline is long
        // past: remaining() must report exactly zero, never a wrapped or negative value.
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("expired", Duration.ofSeconds(30)).spec(), 1)
                .deadlineCeilingNanos(Long.MAX_VALUE)
                .resolutionTimeNanos(System.nanoTime() - TimeUnit.MINUTES.toNanos(1)));

        assertThat(unit.deadlineNanos()).isLessThan(System.nanoTime());
        assertThat(unit.remaining().isNegative()).isFalse();
        assertThat(unit.remaining().isZero()).isTrue();
    }

    @Test
    void remainingWithUnexpiredDeadlineStaysPositiveAndBounded() {
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("live", Duration.ofSeconds(30)).spec(), 1)
                .deadlineCeilingNanos(Long.MAX_VALUE));

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

        long deadline = MultiTaskContext.resolveDeadlineNanos(timeout, Long.MAX_VALUE, now);

        assertThat(deadline).isEqualTo(now + timeout.toNanos());
        assertThat(deadline).isLessThan(Long.MAX_VALUE);
    }

    @Test
    void childLinksToTheParentAndCreatesItsOwnCancellationToken() {
        MultiTaskContext parent = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("parent", Duration.ofSeconds(30)).spec(), 2));
        MultiTaskContext child = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("child", Duration.ofSeconds(30)).spec(), 1)
                .structuralParent(parent));

        assertThat(parent.name()).isEqualTo("parent");
        assertThat(parent.taskCount()).isEqualTo(2);
        assertThat(parent.structuralParent()).isNull();
        assertThat(child.structuralParent()).isSameAs(parent);
        assertThat(child.structuralParentNode()).isEqualTo(parent.node());
        assertThat(child.cancellationToken()).isNotSameAs(parent.cancellationToken());
        assertThat(child.node().executor()).isNull();
        assertThat(child.node().executorLabel()).isNull();
    }

    /** Minimal executor identity object; no tasks are submitted by this test. */
    private static final class ExecutorServiceStub extends AbstractExecutorService {
        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }
}

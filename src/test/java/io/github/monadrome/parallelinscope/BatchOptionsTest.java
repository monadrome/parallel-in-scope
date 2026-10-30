package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Contract pins for {@link BatchOptions}: the batch identity, the requested parallelism, the
 * per-batch execution policy, and how those resolve into a {@link MultiTaskContext}.
 */
class BatchOptionsTest {

    @Test
    void defaultsAreUncappedUndeclaredAndEnqueueing() {
        BatchOptions options = BatchOptions.timeout("load", Duration.ofSeconds(30));

        assertThat(options.name()).isEqualTo("load");
        assertThat(options.parallelism()).isEqualTo(Integer.MAX_VALUE);
        assertThat(options.taskType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(options.rejectEnqueue()).isFalse();
    }

    @Test
    void withersRoundTripEveryFieldWithoutMutatingTheOriginal() {
        BatchOptions base = BatchOptions.inheritTimeout("load");

        BatchOptions derived = base.parallelism(3)
                .taskType(TaskType.COMPUTE)
                .rejectEnqueue(true)
                .closeGrace(Duration.ofMillis(200));

        assertThat(base.parallelism()).isEqualTo(Integer.MAX_VALUE);
        assertThat(base.taskType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(base.rejectEnqueue()).isFalse();
        assertThat(base.closeGrace()).isEmpty();
        assertThat(derived.parallelism()).isEqualTo(3);
        assertThat(derived.taskType()).isEqualTo(TaskType.COMPUTE);
        assertThat(derived.rejectEnqueue()).isTrue();
        assertThat(derived.closeGrace()).contains(Duration.ofMillis(200));
        assertThat(derived.timeout()).isEmpty();
    }

    @SuppressWarnings("NullAway")
    @Test
    void nameIsValidatedOnceAndPreservedVerbatim() {
        String longName = "order-pipeline-stage-7";

        assertThat(BatchOptions.timeout(longName, Duration.ofSeconds(30)).name())
                .isEqualTo(longName);
        assertThatThrownBy(() -> BatchOptions.inheritTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BatchOptions.inheritTimeout("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("NullAway")
    @Test
    void rejectsNonPositiveExplicitTimeouts() {
        assertThatThrownBy(() -> BatchOptions.timeout("load", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchOptions.timeout("load", Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchOptions.timeout("load", null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * The close grace is a cleanup budget rather than an execution timeout, so its validation
     * differs from the deadline above: {@link Duration#ZERO} is meaningful here, because it makes
     * the close cancel-only, while a negative grace is not a budget at all. Both the zero and the
     * negative path are pinned at the declaration site, not only on {@link Validation}.
     */
    @SuppressWarnings("NullAway")
    @Test
    void closeGraceAcceptsZeroAndRejectsNegativeOrNull() {
        BatchOptions base = BatchOptions.timeout("load", Duration.ofSeconds(30));

        assertThat(base.closeGrace(Duration.ZERO).closeGrace()).contains(Duration.ZERO);
        assertThatThrownBy(() -> base.closeGrace(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closeGrace must not be negative");
        assertThatThrownBy(() -> base.closeGrace(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void explicitTimeoutAndParallelismResolveIntoTheBatchContext() {
        MultiTaskContext context = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("write", Duration.ofSeconds(3))
                        .parallelism(8)
                        .taskType(TaskType.BLOCKING)
                        .rejectEnqueue(false)
                        .spec(),
                2));

        assertThat(context.name()).isEqualTo("write");
        assertThat(context.taskCount()).isEqualTo(2);
        assertThat(context.effectiveParallelism()).isEqualTo(2);
        assertThat(context.taskType()).isEqualTo(TaskType.BLOCKING);
        assertThat(context.rejectEnqueue()).isFalse();
        assertThat(context.remaining().toMillis()).isLessThanOrEqualTo(3_000);
    }

    @Test
    void defaultParallelismIsCappedByTaskCount() {
        MultiTaskContext context = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("read", Duration.ofSeconds(3)).spec(), 4));

        assertThat(context.effectiveParallelism()).isEqualTo(4);
    }

    @Test
    void rejectsNonPositiveParallelism() {
        assertThatThrownBy(() ->
                        BatchOptions.timeout("read", Duration.ofSeconds(3)).parallelism(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        BatchOptions.timeout("read", Duration.ofSeconds(3)).parallelism(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inheritedTimeoutWithoutParentIsRejectedAtResolution() {
        assertThatThrownBy(() -> MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.inheritTimeout("read").spec(), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no enclosing deadline to inherit");
    }

    @Test
    void inheritedTimeoutResolvesToTheParentDeadline() {
        MultiTaskContext parent = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("outer", Duration.ofMillis(100)).spec(), 1));
        MultiTaskContext child = MultiTaskContext.resolve(
                MultiTaskContext.resolution(BatchOptions.inheritTimeout("inner").spec(), 1)
                        .structuralParent(parent));

        assertThat(child.deadlineNanos()).isEqualTo(parent.deadlineNanos());
    }
}

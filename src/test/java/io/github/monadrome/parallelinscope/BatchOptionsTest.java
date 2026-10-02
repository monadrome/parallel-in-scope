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
    void defaultsAreUncappedIoBoundEnqueueingAndFailOnRejection() {
        BatchOptions options = BatchOptions.timeout("load", Duration.ofSeconds(30));

        assertThat(options.name()).isEqualTo("load");
        assertThat(options.parallelism()).isEqualTo(Integer.MAX_VALUE);
        // IO_BOUND and rejectEnqueue=false travel together: SmartBlockingQueue refuses an offer when
        // the type is CPU_BOUND OR the flag is set, so either default alone would make such a queue
        // refuse every task submitted with default options, leaving its capacity unused and sending
        // every task to the rejection handler. Refusing to enqueue is therefore opt-in.
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(options.rejectEnqueue()).isFalse();
    }

    @Test
    void withersRoundTripEveryFieldWithoutMutatingTheOriginal() {
        BatchOptions base = BatchOptions.inheritTimeout("load");

        BatchOptions derived = base.parallelism(3).taskType(TaskType.CPU_BOUND).rejectEnqueue(true);

        assertThat(base.parallelism()).isEqualTo(Integer.MAX_VALUE);
        assertThat(base.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(base.rejectEnqueue()).isFalse();
        assertThat(derived.parallelism()).isEqualTo(3);
        assertThat(derived.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(derived.rejectEnqueue()).isTrue();
        assertThat(derived.timeout()).isEmpty();
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void nameIsValidatedOnceAndPreservedVerbatim() {
        String longName = "order-pipeline-stage-7";

        assertThat(BatchOptions.timeout(longName, Duration.ofSeconds(30)).name())
                .isEqualTo(longName);
        assertThatThrownBy(() -> BatchOptions.inheritTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BatchOptions.inheritTimeout("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void rejectsNonPositiveExplicitTimeouts() {
        assertThatThrownBy(() -> BatchOptions.timeout("load", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchOptions.timeout("load", Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchOptions.timeout("load", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void explicitTimeoutAndParallelismResolveIntoTheBatchContext() {
        MultiTaskContext context = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("write", Duration.ofSeconds(3))
                        .parallelism(8)
                        .taskType(TaskType.IO_BOUND)
                        .rejectEnqueue(false)
                        .spec(),
                2));

        assertThat(context.name()).isEqualTo("write");
        assertThat(context.taskCount()).isEqualTo(2);
        assertThat(context.effectiveParallelism()).isEqualTo(2);
        assertThat(context.taskType()).isEqualTo(TaskType.IO_BOUND);
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

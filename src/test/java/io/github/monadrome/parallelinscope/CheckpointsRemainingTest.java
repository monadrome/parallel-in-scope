package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Checkpoints#remaining()} reads the running task's own token: empty outside a scoped task,
 * the resolved non-negative budget inside one, and the documented no-deadline sentinel when the
 * scope is unbounded. The query is read-only — it answers on a cancelled token and on an
 * interrupted thread without touching the interrupt flag.
 */
class CheckpointsRemainingTest {

    @AfterEach
    void clearContextAndInterrupt() {
        Thread.interrupted();
    }

    @Test
    void outsideAScopedTaskIsEmpty() {
        assertThat(Checkpoints.remaining()).isEmpty();
    }

    @Test
    void insideAScopedTaskReportsTheResolvedBudget() throws Exception {
        MultiTaskContext context = contextWithTimeout(Duration.ofSeconds(30));
        AtomicReference<Optional<Duration>> observed = new AtomicReference<>();

        runInTask(context, () -> observed.set(Checkpoints.remaining()));

        assertThat(captured(observed)).isPresent();
        assertThat(captured(observed).get().isNegative()).isFalse();
        assertThat(captured(observed).get()).isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(captured(observed).get()).isGreaterThan(Duration.ofSeconds(25));
    }

    @Test
    void expiredDeadlineReadsZero() {
        MultiTaskContext expired = contextWithTimeout(Duration.ofNanos(1));
        AtomicReference<Optional<Duration>> observed = new AtomicReference<>();
        // Install the context directly: ScopedCallable would checkpoint first and throw on the
        // expired deadline before the body could read the budget.
        TaskExecutionContext previous =
                TaskExecutionContext.install(new TaskExecutionContext(expired, 0, System.nanoTime()));
        try {
            observed.set(Checkpoints.remaining());
        } finally {
            TaskExecutionContext.restore(previous);
        }
        assertThat(captured(observed)).contains(Duration.ZERO);
    }

    @Test
    void unboundedScopeReportsTheDocumentedSentinel() throws Exception {
        MultiTaskContext unbounded = MultiTaskContext.resolve(
                MultiTaskContext.resolution(BatchOptions.inheritTimeout("task").spec(), 1)
                        .deadlineCeilingNanos(Long.MAX_VALUE));
        AtomicReference<Optional<Duration>> observed = new AtomicReference<>();

        runInTask(unbounded, () -> observed.set(Checkpoints.remaining()));

        assertThat(captured(observed)).contains(Duration.ofNanos(Long.MAX_VALUE));
    }

    @Test
    void nestedTaskReadsItsOwnTighterToken() throws Exception {
        MultiTaskContext outer = contextWithTimeout(Duration.ofSeconds(60));
        MultiTaskContext inner = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("inner", Duration.ofSeconds(30)).spec(), 1)
                .structuralParent(outer));
        AtomicReference<Optional<Duration>> outerBudget = new AtomicReference<>();
        AtomicReference<Optional<Duration>> innerBudget = new AtomicReference<>();

        runInTask(outer, () -> {
            outerBudget.set(Checkpoints.remaining());
            TaskExecutionContext previous =
                    TaskExecutionContext.install(new TaskExecutionContext(inner, 0, System.nanoTime()));
            try {
                innerBudget.set(Checkpoints.remaining());
            } finally {
                TaskExecutionContext.restore(previous);
            }
        });

        assertThat(captured(innerBudget)).isPresent();
        assertThat(captured(innerBudget).get()).isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(captured(outerBudget).get())
                .isGreaterThan(captured(innerBudget).get());
    }

    @Test
    void cancelledTokenStillAnswersWithoutThrowing() throws Exception {
        MultiTaskContext context = contextWithTimeout(Duration.ofSeconds(30));
        AtomicReference<Optional<Duration>> observed = new AtomicReference<>();

        TaskExecutionContext previous =
                TaskExecutionContext.install(new TaskExecutionContext(context, 0, System.nanoTime()));
        try {
            context.cancellationToken().cancel(false);
            observed.set(Checkpoints.remaining());
        } finally {
            TaskExecutionContext.restore(previous);
        }

        assertThat(captured(observed)).isPresent();
        assertThat(captured(observed).get()).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void interruptedCallerKeepsItsFlagAndGetsAnAnswer() {
        Thread.currentThread().interrupt();
        try {
            assertThat(Checkpoints.remaining()).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static Optional<Duration> captured(AtomicReference<Optional<Duration>> reference) {
        return java.util.Objects.requireNonNull(reference.get());
    }

    private static MultiTaskContext contextWithTimeout(Duration timeout) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", timeout).spec(), 1));
    }

    // NullAway: the action intentionally runs without producing a value
    @SuppressWarnings("NullAway")
    private static Void runInTask(MultiTaskContext context, Runnable action) throws Exception {
        return new ScopedCallable<Void>(new TaskExecutionContext(context, 0, System.nanoTime()), () -> {
                    action.run();
                    return null;
                })
                .call();
    }
}

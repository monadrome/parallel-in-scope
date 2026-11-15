package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The scoped wrapper's entry and exit contract: a task whose deadline has already expired never
 * enters its user task code, and an execution blocked at a cancellation checkpoint is rejected once the scope
 * is cancelled.
 */
class ScopedCallableEntryTest {

    @Test
    void anExpiredDeadlineRejectsTheExecutionBeforeItRuns() throws Exception {
        MultiTaskContext expired = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("expired", Duration.ofNanos(1)).spec(), 1));
        Thread.sleep(5L);
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThatThrownBy(
                        () -> new ScopedCallable<Void>(new TaskExecutionContext(expired, 0, System.nanoTime()), () -> {
                                    ran.set(true);
                                    return null;
                                })
                                .call())
                .isInstanceOf(LeanCancellationException.class);
        assertThat(ran)
                .as("the execution is never entered past an expired deadline")
                .isFalse();
    }

    @Test
    void aCancelledScopeRejectsTheExecutionBeforeItRuns() throws Exception {
        MultiTaskContext cancelled = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", Duration.ofSeconds(30)).spec(), 1));
        cancelled.cancellationToken().cancel();
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThatThrownBy(() -> new ScopedCallable<Void>(
                                new TaskExecutionContext(cancelled, 0, System.nanoTime()), () -> {
                                    ran.set(true);
                                    return null;
                                })
                        .call())
                .isInstanceOf(LeanCancellationException.class);
        assertThat(ran).as("a cancelled scope's execution is never entered").isFalse();
    }

    @Test
    void theExecutionExitIsPublishedBeforeTheFutureSettles() throws Exception {
        // The tracker's signal completes inside the scoped wrapper's finally, before the future's
        // own settlement listeners can run: a listener that observes settlement already observes a
        // released execution slot.
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", Duration.ofSeconds(30)).spec(), 1));
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(unit);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean exitPublishedAtSettlement = new AtomicBoolean(false);

        io.github.monadrome.parallelinscope.ExecutionPhaseHintFuture<String> future =
                TaskSubmissions.prepare(new TaskExecutionContext(unit, 0, System.nanoTime(), slot), () -> "value");
        future.addListener(
                () -> {
                    exitPublishedAtSettlement.set(tracker.isDone());
                    settled.countDown();
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor());
        future.run();

        assertThat(settled.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(com.google.common.util.concurrent.Futures.getDone(future)).isEqualTo("value");
        assertThat(exitPublishedAtSettlement.get())
                .as("the execution slot is released before settlement listeners observe the future")
                .isTrue();
    }
}

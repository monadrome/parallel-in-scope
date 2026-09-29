package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * An interrupt inside the observation-publication barrier is an interrupt, not an elapsed budget.
 *
 * <p>{@code TaskGroup.awaitBodyCompletion} and {@code TaskBatchResult.awaitBodyCompletion} run three
 * waits under one budget: the body-exit signal, then each future's settlement, then the observation
 * publication. Both declare {@code throws InterruptedException} and both define {@code false} as
 * "the budget elapsed". Those two statements together mean the later phases may not answer an
 * interrupt with {@code false} — a caller that reads {@code false} as "not done yet, wait longer"
 * would otherwise spin on a thread that has been asked to stop.
 */
class BodyCompletionBarrierInterruptTest {

    @Test
    void anInterruptDuringTheBarrierWaitThrowsRatherThanReportingAnElapsedBudget() {
        SettableFuture<String> neverSettles = SettableFuture.create();
        long generousBudget = TimeUnit.SECONDS.toNanos(30);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() ->
                            BodyCompletionTracker.awaitSettled(neverSettles, generousBudget, System.nanoTime(), null))
                    .as("an interrupt must not be reported as a budget that elapsed")
                    .isInstanceOf(InterruptedException.class);
        } finally {
            // get() clears the flag when it throws; make sure nothing leaks to the next test either
            // way.
            Thread.interrupted();
        }
    }

    @Test
    void anUninterruptedBarrierWaitStillReportsAnElapsedBudgetAsFalse() throws Exception {
        SettableFuture<String> neverSettles = SettableFuture.create();

        // A budget that is already spent: the wait has nothing left, which is the real false case.
        assertThat(BodyCompletionTracker.awaitSettled(neverSettles, 0L, System.nanoTime(), null))
                .isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anAlreadySettledFutureNeedsNoWaitAndNoBudget() throws Exception {
        SettableFuture<String> settled = SettableFuture.create();
        settled.set("done");

        assertThat(BodyCompletionTracker.awaitSettled(settled, 0L, System.nanoTime(), null))
                .isTrue();
    }

    @Test
    void aFailureOnAFutureDeclaredIncapableOfFailingIsAnImplementationDefect() {
        // The failure has to arrive while the wait is in progress: a future that is already done
        // takes the isDone() fast path, which reports "settled" without inspecting how.
        SettableFuture<String> failsDuringTheWait = SettableFuture.create();
        failLater(failsDuringTheWait);

        assertThatThrownBy(() -> BodyCompletionTracker.awaitSettled(
                        failsDuringTheWait, TimeUnit.SECONDS.toNanos(5), System.nanoTime(), "test observation signal"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("test observation signal cannot fail");
    }

    @Test
    void aFailureOnAnOrdinaryFutureCountsAsSettled() throws Exception {
        SettableFuture<String> failsDuringTheWait = SettableFuture.create();
        failLater(failsDuringTheWait);

        // An element future settling exceptionally is an outcome the report describes, not a defect.
        assertThat(BodyCompletionTracker.awaitSettled(
                        failsDuringTheWait, TimeUnit.SECONDS.toNanos(5), System.nanoTime(), null))
                .isTrue();
    }

    @Test
    void anAlreadyFailedFutureTakesTheDoneFastPathWithoutInspectingTheFailure() throws Exception {
        SettableFuture<String> alreadyFailed = SettableFuture.create();
        alreadyFailed.setException(new IllegalStateException("boom"));

        // Deliberate: the wait's question is "is this terminal", and a terminal future answers it
        // without a get(). The cannotFail label guards the waiting path, not a post-hoc audit of an
        // outcome that already landed.
        assertThat(BodyCompletionTracker.awaitSettled(alreadyFailed, 0L, System.nanoTime(), "test observation signal"))
                .isTrue();
    }

    /** Fails the future from another thread once the caller is plausibly inside its wait. */
    private static void failLater(SettableFuture<String> future) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            future.setException(new IllegalStateException("boom"));
        });
        thread.setDaemon(true);
        thread.start();
    }
}

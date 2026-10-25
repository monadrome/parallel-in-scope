package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Direct contract test of the per-task body lifecycle slot's state machine. */
class TaskBodyStateTest {

    @Test
    void claimRunningWinsExactlyOnce() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState slot = tracker.register(context());

        assertThat(slot.claimRunning()).isTrue();
        assertThat(slot.claimRunning()).isFalse();

        slot.exited();
        assertThat(slot.claimRunning()).isFalse();
    }

    @Test
    void skippedBeforeClaimPreventsRunningAndReleasesTheSlot() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState slot = tracker.register(context());

        slot.skipped();
        // The slot is terminal: a late executor invocation must not enter the body.
        assertThat(slot.claimRunning()).isFalse();
        // A second skip is a no-op; the tracker was released exactly once either way.
        slot.skipped();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void exitedIsIdempotentAndReleasesOnce() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState slot = tracker.register(context());

        assertThat(slot.claimRunning()).isTrue();
        slot.exited();
        slot.exited();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void terminalSignalCompletesWithTheWinningTransition() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState slot = tracker.register(context());

        assertThat(slot.terminal().isDone()).isFalse();
        slot.claimRunning();
        slot.exited();
        assertThat(slot.terminal().isDone()).isTrue();
    }

    @Test
    void isOutstandingTracksPendingRunningAndTerminalStates() {
        BodyCompletionTracker tracker = BodyCompletionTracker.create(1);
        TaskBodyState slot = tracker.register(context());

        assertThat(slot.isOutstanding()).isTrue();
        slot.claimRunning();
        assertThat(slot.isOutstanding()).isTrue();
        slot.exited();
        assertThat(slot.isOutstanding()).isFalse();
    }

    private static MultiTaskContext context() {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", java.time.Duration.ofSeconds(30)).spec(), 1));
    }
}

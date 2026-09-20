package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Tests for CancellationToken and cooperative cancellation.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class CancellationTokenTest {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();

    private static CancellationToken withDeadlineAfter(long millis) {
        return new CancellationToken(null, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /** Records the interrupt flag of the first cancellation request that reached it. */
    private static final class InterruptRecordingFuture
            extends com.google.common.util.concurrent.AbstractFuture<String> {
        private final AtomicReference<Boolean> cancelInterrupt = new AtomicReference<>();

        void complete(String value) {
            set(value);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelInterrupt.compareAndSet(null, mayInterruptIfRunning);
            return super.cancel(mayInterruptIfRunning);
        }
    }

    /**
     * Captures the scheduled timeout action without running it. The returned handle refuses
     * cancellation, modelling a timer task the scheduler thread already dispatched: recalling it
     * with {@code cancel(false)} is impossible, so the action still runs after losing the race.
     */
    private static class AlreadyDispatchedTimer extends AbstractExecutorService implements ScheduledExecutorService {
        private final AtomicReference<Runnable> dispatched = new AtomicReference<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            dispatched.set(command);
            return new ScheduledFuture<Object>() {
                @Override
                public long getDelay(TimeUnit timeUnit) {
                    return 0;
                }

                @Override
                public int compareTo(Delayed other) {
                    return 0;
                }

                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return false;
                }

                @Override
                public Object get() {
                    throw new UnsupportedOperationException("a dispatched handle carries no value");
                }

                @Override
                public Object get(long timeout, TimeUnit timeUnit) {
                    throw new UnsupportedOperationException("a dispatched handle carries no value");
                }
            };
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("only Runnable deadlines are supported");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("no periodic tasks");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("no periodic tasks");
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException("only schedule() is supported");
        }

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
    }

    /**
     * A scheduler that cancels the token and then rejects, standing in for a cancellation that
     * lands between bind's liveness guard and the arming while a closing runtime retires the
     * scheduler — the check-then-act interval of the deadline arming.
     */
    private static final class CancelThenRejectScheduler extends AlreadyDispatchedTimer {
        private final CancellationToken token;

        CancelThenRejectScheduler(CancellationToken token) {
            this.token = token;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            token.cancel(true);
            throw new java.util.concurrent.RejectedExecutionException("retired");
        }
    }

    @Test
    public void testInitialState() {
        CancellationToken token = CancellationToken.create();
        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(token.deadlineNanos()).isEqualTo(Long.MAX_VALUE);
        assertThat(token.remaining().toNanos()).isGreaterThan(TimeUnit.HOURS.toNanos(1));
    }

    @Test
    public void testManualCancel() {
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(token.state().shouldInterruptCurrentThread()).isTrue();
    }

    @Test
    public void testParentChildChain() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);

        assertThat(parent.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(child.state()).isEqualTo(CancellationToken.State.RUNNING);

        parent.cancel(false);
        assertThat(parent.state()).isEqualTo(CancellationToken.State.CANCELLED);
    }

    @Test
    public void testStateInterruptionSemantics() {
        assertThat(CancellationToken.State.RUNNING.shouldInterruptCurrentThread())
                .isFalse();
        assertThat(CancellationToken.State.SUCCESS.shouldInterruptCurrentThread())
                .isFalse();
        assertThat(CancellationToken.State.FAIL_FAST.shouldInterruptCurrentThread())
                .isTrue();
        assertThat(CancellationToken.State.TIMEOUT.shouldInterruptCurrentThread())
                .isTrue();
        assertThat(CancellationToken.State.CANCELLED.shouldInterruptCurrentThread())
                .isTrue();
        assertThat(CancellationToken.State.PROPAGATED_CANCELLED.shouldInterruptCurrentThread())
                .isTrue();
    }

    // ==================== deadline ====================

    @Test
    public void deadlineIsCappedByParentDeadline() {
        long later = System.nanoTime() + TimeUnit.HOURS.toNanos(1);
        long earlier = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);

        CancellationToken parent = new CancellationToken(null, earlier);
        CancellationToken childRequestingLater = new CancellationToken(parent, later);
        CancellationToken childRequestingEarlier = new CancellationToken(parent, earlier - 1);

        assertThat(childRequestingLater.deadlineNanos()).isEqualTo(parent.deadlineNanos());
        assertThat(childRequestingEarlier.deadlineNanos()).isEqualTo(earlier - 1);
    }

    @Test
    public void expiredDeadlineCommitsTimeoutSynchronouslyDuringBind() throws Exception {
        CancellationToken token = withDeadlineAfter(-1000);

        SettableFuture<String> pending = SettableFuture.create();
        SettableFuture<String> alreadySucceeded = SettableFuture.create();
        alreadySucceeded.set("kept");
        SettableFuture<Void> submitCanceller = SettableFuture.create();

        ListenableFuture<?> aggregate = token.bind(Arrays.asList(pending, alreadySucceeded), submitCanceller, TIMER);

        // The commit is synchronous: bind returns with the token already TIMEOUT and the pending
        // work already cancelled, so no submitted task can still enter user code in the window a
        // zero-delay timer would leave open.
        assertThat(token.state()).isEqualTo(CancellationToken.State.TIMEOUT);
        assertThat(aggregate.isDone()).isTrue();
        assertThat(pending).isCancelled();
        assertThat(submitCanceller).isCancelled();
        assertThat(alreadySucceeded).isNotCancelled();
        assertThat(alreadySucceeded.get()).isEqualTo("kept");
    }

    @Test
    public void bindWithoutDeadlineNeverArmsATimeout() throws Exception {
        // Long.MAX_VALUE means no deadline; the subtraction in bind would overflow against a
        // negative nanoTime() and fire immediately if a timeout were scheduled unconditionally.
        CancellationToken token = new CancellationToken(null, Long.MAX_VALUE);

        SettableFuture<String> pending = SettableFuture.create();
        token.bind(ImmutableList.of(pending), Futures.immediateVoidFuture(), TIMER);

        Thread.sleep(100);
        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(pending.isDone()).isFalse();

        pending.set("done");
        await().untilAsserted(() -> assertThat(token.state()).isEqualTo(CancellationToken.State.SUCCESS));
    }

    // ==================== bind state transition tests ====================

    @Test
    public void testBind_success_allFuturesComplete() throws Exception {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> f1 = SettableFuture.create();
        SettableFuture<String> f2 = SettableFuture.create();
        SettableFuture<String> f3 = SettableFuture.create();
        List<ListenableFuture<String>> futures = Arrays.asList(f1, f2, f3);

        token.bind(futures, Futures.immediateVoidFuture(), TIMER);

        f1.set("a");
        f2.set("b");
        f3.set("c");

        // Allow callback propagation
        Thread.sleep(50);
        assertThat(token.state()).isEqualTo(CancellationToken.State.SUCCESS);
    }

    @Test
    public void testBind_timeout_stateTransitionsToTimeoutCancelled() throws Exception {
        CancellationToken token = withDeadlineAfter(100);

        SettableFuture<String> f1 = SettableFuture.create(); // never completed

        token.bind(ImmutableList.of(f1), Futures.immediateVoidFuture(), TIMER);

        // Wait for timeout to fire
        Thread.sleep(300);
        assertThat(token.state()).isEqualTo(CancellationToken.State.TIMEOUT);
    }

    @Test
    public void testBind_failFast_oneFailsOthersCancelled() throws Exception {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> f1 = SettableFuture.create();
        SettableFuture<String> f2 = SettableFuture.create();
        List<ListenableFuture<String>> futures = Arrays.asList(f1, f2);

        // Priority 7: a failed future must transition the shared token into fail-fast cancellation.
        // This is the low-level state change that lets higher-level map calls stop sibling tasks.
        token.bind(futures, Futures.immediateVoidFuture(), TIMER);

        f1.setException(new RuntimeException("boom"));

        // Allow callback propagation
        Thread.sleep(50);
        assertThat(token.state()).isEqualTo(CancellationToken.State.FAIL_FAST);
    }

    @Test
    public void businessTimeoutExceptionIsFailFastNotFrameworkTimeout() {
        // A member body may legitimately throw java.util.concurrent.TimeoutException; the token
        // must classify by event source, not by exception type: only the armed deadline produces
        // TIMEOUT. Here the 60s deadline cannot have expired, so the failure is a business
        // failure and the token must commit FAIL_FAST.
        CancellationToken token = withDeadlineAfter(60_000);

        SettableFuture<String> failing = SettableFuture.create();
        SettableFuture<String> sibling = SettableFuture.create();
        token.bind(Arrays.asList(failing, sibling), Futures.immediateVoidFuture(), TIMER);

        failing.setException(new java.util.concurrent.TimeoutException("business-level timeout"));

        await().untilAsserted(() -> {
            assertThat(token.state()).isEqualTo(CancellationToken.State.FAIL_FAST);
            assertThat(sibling).isCancelled();
        });
    }

    @Test
    public void testBind_failFast_cancelsSiblingAndSubmitCanceller() {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> failed = SettableFuture.create();
        SettableFuture<String> sibling = SettableFuture.create();
        SettableFuture<Void> submitCanceller = SettableFuture.create();

        token.bind(Arrays.asList(failed, sibling), submitCanceller, TIMER);

        failed.setException(new RuntimeException("boom"));

        await().untilAsserted(() -> {
            assertThat(token.state()).isEqualTo(CancellationToken.State.FAIL_FAST);
            assertThat(sibling).isCancelled();
            assertThat(submitCanceller).isCancelled();
        });
    }

    @Test
    public void bindReturnsAggregateTrackingAllFuturesAndTheSubmitter() {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> task = SettableFuture.create();
        SettableFuture<Void> submitCanceller = SettableFuture.create();
        ListenableFuture<?> aggregate = token.bind(ImmutableList.of(task), submitCanceller, TIMER);

        assertThat(aggregate.isDone()).isFalse();
        task.set("a");
        assertThat(aggregate.isDone()).isFalse();
        submitCanceller.set(null);
        assertThat(aggregate.isDone()).isTrue();
    }

    @Test
    public void testBind_manualCancel_cancelsBoundWorkAndSubmitCanceller() {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> task = SettableFuture.create();
        SettableFuture<Void> submitCanceller = SettableFuture.create();

        token.bind(ImmutableList.of(task), submitCanceller, TIMER);

        token.cancel(true);

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(task).isCancelled();
        assertThat(submitCanceller).isCancelled();
    }

    @Test
    public void testBind_cancelAfterSuccess_keepsRecordedResult() throws Exception {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> task = SettableFuture.create();
        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), TIMER);
        task.set("done");

        await().until(() -> token.state() == CancellationToken.State.SUCCESS);

        token.cancel(true);

        assertThat(task.isDone()).isTrue();
        assertThat(task.get()).isEqualTo("done");
        assertThat(token.state()).isEqualTo(CancellationToken.State.SUCCESS);
    }

    @Test
    public void parentCancelWithoutInterruptPropagatesWithoutInterrupt() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);
        InterruptRecordingFuture childWork = new InterruptRecordingFuture();
        child.bind(ImmutableList.of(childWork), Futures.immediateVoidFuture(), TIMER);

        parent.cancel(false);

        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(childWork.isCancelled()).isTrue();
        assertThat(childWork.cancelInterrupt)
                .as("cancel(false) keeps its no-interrupt policy across the parent link")
                .hasValue(false);
    }

    @Test
    public void parentCancelWithInterruptPropagatesWithInterrupt() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);
        InterruptRecordingFuture childWork = new InterruptRecordingFuture();
        child.bind(ImmutableList.of(childWork), Futures.immediateVoidFuture(), TIMER);

        parent.cancel(true);

        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(childWork.cancelInterrupt).hasValue(true);
    }

    @Test
    public void nonInterruptingCancelPropagatesAcrossMultipleGenerations() {
        CancellationToken root = CancellationToken.create();
        CancellationToken child = new CancellationToken(root);
        CancellationToken grandchild = new CancellationToken(child);
        InterruptRecordingFuture grandchildWork = new InterruptRecordingFuture();
        grandchild.bind(ImmutableList.of(grandchildWork), Futures.immediateVoidFuture(), TIMER);

        root.cancel(false);

        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(grandchild.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(grandchildWork.cancelInterrupt)
                .as("the interrupt intent survives every intermediate generation")
                .hasValue(false);
    }

    @Test
    public void propagatedDeadlineAndFailFastStillInterrupt() {
        // Only an explicit cancel(false) relaxes interruption; deadline expiry and fail-fast keep
        // the interrupting cascade, because their liveness depends on it.
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);
        InterruptRecordingFuture childWork = new InterruptRecordingFuture();
        child.bind(ImmutableList.of(childWork), Futures.immediateVoidFuture(), TIMER);

        parent.timeoutCancel();

        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(childWork.cancelInterrupt).hasValue(true);
    }

    @Test
    public void directBoundCancelWithoutInterruptDoesNotUpgradeDuringCascade() {
        CancellationToken token = CancellationToken.create();
        InterruptRecordingFuture work = new InterruptRecordingFuture();
        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), TIMER);

        token.cancel(false);

        assertThat(work.cancelInterrupt)
                .as("direct cancel(false) must reach bound work without an interrupt")
                .hasValue(false);
    }

    @Test
    public void cancelWithoutInterruptBeforeBindStaysNonInterrupting() {
        CancellationToken token = CancellationToken.create();
        InterruptRecordingFuture work = new InterruptRecordingFuture();

        token.cancel(false);
        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), TIMER);

        assertThat(work.cancelInterrupt)
                .as("pre-bind cancel(false) must retain its intent when binding later")
                .hasValue(false);
    }

    @Test
    public void expiredDeadlineDoesNotUpgradeEarlierCancelWithoutInterrupt() {
        CancellationToken token = new CancellationToken(null, System.nanoTime() - 1L);
        InterruptRecordingFuture work = new InterruptRecordingFuture();

        token.cancel(false);
        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), TIMER);

        assertThat(work.cancelInterrupt)
                .as("an already-cancelled token must retain cancel(false) at an expired bind")
                .hasValue(false);
    }

    @Test
    public void alreadyDispatchedTimerActionRetainsCancelFalseIntent() {
        ManualClock clock = new ManualClock();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker());
        SettableFuture<String> work = SettableFuture.create();
        InterruptRecordingFuture submission = new InterruptRecordingFuture();
        AlreadyDispatchedTimer timer = new AlreadyDispatchedTimer();
        token.bind(ImmutableList.of(work), submission, timer);
        Runnable timerAction = timer.dispatched.get();
        assertThat(timerAction).isNotNull();

        // The timer thread already dispatched the timeout action when the user thread commits
        // cancel(false): the action runs between the winning transition and the cancellation
        // cascade, loses the first-wins race, and must cascade with the committed intent — not
        // with its own TIMEOUT interrupting policy. The state listener stands in for that
        // interleaving deterministically; at that point the winner's futureToken cascade has not
        // run yet, so the losing action is the first canceller to reach the submission handle.
        token.addStateListener(state -> timerAction.run());
        token.cancel(false);

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(submission.cancelInterrupt)
                .as("an already-dispatched timer loser must cascade with the winning cancel(false) intent")
                .hasValue(false);
    }

    @Test
    public void directCancelFalsePublishesIntentBeforeStateListenersRun() {
        CancellationToken token = CancellationToken.create();
        SettableFuture<String> trigger = SettableFuture.create();
        InterruptRecordingFuture victim = new InterruptRecordingFuture();
        token.bind(ImmutableList.of(trigger, victim), Futures.immediateVoidFuture(), TIMER);
        token.addStateListener(state -> {
            if (state == CancellationToken.State.CANCELLED) {
                trigger.setException(new RuntimeException("listener failure"));
            }
        });

        token.cancel(false);

        assertThat(victim.cancelInterrupt)
                .as("a reentrant business failure must observe cancel(false)'s intent")
                .hasValue(false);
    }

    @Test
    public void propagatedCancelFalsePublishesIntentBeforeChildListenersRun() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);
        SettableFuture<String> trigger = SettableFuture.create();
        InterruptRecordingFuture victim = new InterruptRecordingFuture();
        child.bind(ImmutableList.of(trigger, victim), Futures.immediateVoidFuture(), TIMER);
        child.addStateListener(state -> {
            if (state == CancellationToken.State.PROPAGATED_CANCELLED) {
                trigger.setException(new RuntimeException("listener failure"));
            }
        });

        parent.cancel(false);

        assertThat(victim.cancelInterrupt)
                .as("a reentrant child failure must retain the propagated non-interrupting intent")
                .hasValue(false);
    }

    @Test
    public void losingReentrantTransitionDoesNotEmitItsOwnCancellation() {
        CancellationToken token = CancellationToken.create();
        InterruptRecordingFuture work = new InterruptRecordingFuture();
        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), TIMER);
        // A reentrant losing transition inside the winner's listener window must not cancel
        // futureToken with its own intent: the winner's cascade runs after the listeners and is
        // the only cancellation this token emits.
        token.addStateListener(state -> {
            if (state == CancellationToken.State.CANCELLED) {
                token.timeoutCancel();
            }
        });

        token.cancel(false);

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(work.cancelInterrupt)
                .as("a losing reentrant transition must not cancel bound work with its own intent")
                .hasValue(false);
    }

    @Test
    public void noArgCancelDelegatesToInterruptingCancel() {
        CancellationToken token = CancellationToken.create();
        InterruptRecordingFuture work = new InterruptRecordingFuture();
        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), TIMER);

        token.cancel();

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(work.cancelInterrupt).hasValue(true);
    }

    @Test
    public void failFastCancelTransitionsAndCancelsBoundWork() {
        CancellationToken token = CancellationToken.create();
        SettableFuture<String> task = SettableFuture.create();
        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), TIMER);

        token.failFastCancel();

        assertThat(token.state()).isEqualTo(CancellationToken.State.FAIL_FAST);
        await().until(task::isCancelled);
    }

    @Test
    public void retiredDomainSchedulerFallsBackToTheBindParameter() {
        // The token tree's scheduler belongs to the ancestor runtime, which may already be closed:
        // a cancelled body that is still running can nest new work into another runtime, and the
        // nested token inherits the retired scheduler. Rejection there must arm the deadline on the
        // caller's live scheduler instead of failing the bind.
        ManualClock clock = new ManualClock();
        ScheduledExecutorService retired = Executors.newSingleThreadScheduledExecutor();
        retired.shutdown();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker(), retired);
        SettableFuture<String> pending = SettableFuture.create();

        token.bind(ImmutableList.of(pending), Futures.immediateVoidFuture(), clock.scheduler());
        clock.advance(Duration.ofMinutes(1));

        assertThat(token.state()).isEqualTo(CancellationToken.State.TIMEOUT);
        assertThat(pending).isCancelled();
    }

    @Test
    public void retiredSchedulerFallbackHandleIsReleasedOnSettle() {
        // The fallback path must return the real handle: the futureToken listener cancels it when
        // the token settles, keeping the live scheduler free of a settled token's deadline.
        ManualClock clock = new ManualClock();
        ScheduledExecutorService retired = Executors.newSingleThreadScheduledExecutor();
        retired.shutdown();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(10), clock.ticker(), retired);
        SettableFuture<String> task = SettableFuture.create();
        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), clock.scheduler());
        assertThat(clock.pendingHandles()).isEqualTo(1);

        task.set("done");

        assertThat(clock.pendingHandles()).isZero();
    }

    @Test
    public void terminalTokenDoesNotArmADeadlineTimer() {
        CancellationToken token = new CancellationToken(null, System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
        SettableFuture<String> work = SettableFuture.create();
        SettableFuture<Void> submission = SettableFuture.create();
        token.cancel(false);
        AlreadyDispatchedTimer timer = new AlreadyDispatchedTimer();

        token.bind(ImmutableList.of(work), submission, timer);

        assertThat(timer.dispatched)
                .as("a terminal token has no live deadline to enforce; the scheduler is never asked")
                .hasValue(null);
        assertThat(work).isCancelled();
        assertThat(submission).isCancelled();
    }

    @Test
    public void terminalTokenBindDoesNotTouchARetiredScheduler() {
        // Group startup can bind a member token that inherited cancellation while the runtime was
        // closing underneath: both the domain scheduler and the caller's are already retired. A
        // terminal token must skip arming instead of throwing out of bind.
        ManualClock clock = new ManualClock();
        ScheduledExecutorService retired = Executors.newSingleThreadScheduledExecutor();
        retired.shutdown();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker(), retired);
        SettableFuture<String> work = SettableFuture.create();
        token.cancel(true);

        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), retired);

        assertThat(work).isCancelled();
    }

    @Test
    public void cancellationBetweenGuardAndArmingAbsorbsSchedulerRejection() {
        // The deterministic interval: the token is RUNNING at bind's liveness guard, then
        // cancellation lands and the scheduler is retired before the deadline is armed. The
        // terminal state already implies the cancellation of the bound work, so the rejection must
        // be absorbed — not thrown out of an accepted startup.
        ManualClock clock = new ManualClock();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker());
        SettableFuture<String> work = SettableFuture.create();
        SettableFuture<Void> submission = SettableFuture.create();

        token.bind(ImmutableList.of(work), submission, new CancelThenRejectScheduler(token));

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(work).isCancelled();
        assertThat(submission).isCancelled();
    }

    @Test
    public void retiredDomainSchedulerAndConcurrentCancelAbsorbRejectionOnFallback() {
        // Same interval with both schedulers involved: the inherited domain scheduler is already
        // retired, and the caller's scheduler rejects after the concurrent cancellation lands.
        ManualClock clock = new ManualClock();
        ScheduledExecutorService retired = Executors.newSingleThreadScheduledExecutor();
        retired.shutdown();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker(), retired);
        SettableFuture<String> work = SettableFuture.create();

        token.bind(ImmutableList.of(work), Futures.immediateVoidFuture(), new CancelThenRejectScheduler(token));

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(work).isCancelled();
    }

    @Test
    public void runningTokenRejectedByEverySchedulerThrows() {
        // A still-RUNNING token whose deadline cannot be armed must fail loudly: proceeding
        // without deadline enforcement would silently break the timeout contract.
        ScheduledExecutorService retired = Executors.newSingleThreadScheduledExecutor();
        retired.shutdown();
        CancellationToken token = new CancellationToken(null, System.nanoTime() + TimeUnit.MINUTES.toNanos(1));

        assertThatThrownBy(() ->
                        token.bind(ImmutableList.of(SettableFuture.create()), Futures.immediateVoidFuture(), retired))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    @Test
    public void runningTokenRejectedByBothDistinctSchedulersThrows() {
        // The domain scheduler and the caller's fallback are different retired schedulers: the
        // fallback rejection must also reach the caller for a still-RUNNING token — absorbing is
        // reserved for tokens the concurrent cancellation already terminated.
        ManualClock clock = new ManualClock();
        ScheduledExecutorService retiredDomain = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService retiredFallback = Executors.newSingleThreadScheduledExecutor();
        retiredDomain.shutdown();
        retiredFallback.shutdown();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker(), retiredDomain);

        assertThatThrownBy(() -> token.bind(
                        ImmutableList.of(SettableFuture.create()), Futures.immediateVoidFuture(), retiredFallback))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    @Test
    public void childCancelledBeforeParentKeepsItsOwnOutcome() {
        // First-wins: a child that already committed SUCCESS is severed from the parent link, and a
        // later parent cancel(false) neither rewrites its state nor cancels its recorded work.
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);
        InterruptRecordingFuture childWork = new InterruptRecordingFuture();
        child.bind(ImmutableList.of(childWork), Futures.immediateVoidFuture(), TIMER);
        childWork.complete("done");
        await().until(() -> child.state() == CancellationToken.State.SUCCESS);

        parent.cancel(false);

        assertThat(child.state()).isEqualTo(CancellationToken.State.SUCCESS);
        assertThat(childWork.isCancelled()).isFalse();
        assertThat(childWork.cancelInterrupt).hasValue(null);
    }

    @Test
    public void testBind_parentCancelled_childPropagates() throws Exception {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);

        SettableFuture<String> f1 = SettableFuture.create();

        // Priority 9: nested scopes inherit cancellation from their parent.
        // Parent cancellation should mark the child as propagating cancellation even if its own
        // future has not completed yet.
        child.bind(ImmutableList.of(f1), Futures.immediateVoidFuture(), TIMER);

        parent.cancel(true);

        // Allow callback propagation
        Thread.sleep(50);
        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
    }

    @Test
    public void testBind_parentAlreadyCancelled_childImmediatelyCancelled() {
        CancellationToken parent = CancellationToken.create();
        parent.cancel(true);
        assertThat(parent.state().shouldInterruptCurrentThread()).isTrue();

        CancellationToken child = new CancellationToken(parent);

        SettableFuture<String> f1 = SettableFuture.create();
        child.bind(ImmutableList.of(f1), Futures.immediateVoidFuture(), TIMER);

        // The future should be cancelled immediately because parent is already cancelled
        assertThat(f1).isCancelled();
    }

    @Test
    public void timeoutCancelTransitionsAndCancelsBoundWork() {
        CancellationToken token = CancellationToken.create();
        SettableFuture<String> task = SettableFuture.create();
        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), TIMER);

        token.timeoutCancel();

        assertThat(token.state()).isEqualTo(CancellationToken.State.TIMEOUT);
        await().until(task::isCancelled);
    }

    @Test
    public void losingStateTransitionDoesNotNotifyListeners() {
        CancellationToken token = CancellationToken.create();
        AtomicInteger notifications = new AtomicInteger();
        token.addStateListener(state -> notifications.incrementAndGet());

        token.cancel(true);
        token.timeoutCancel(); // loses the CAS: already CANCELLED
        token.cancel(true); // loses again

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(notifications).hasValue(1);
    }

    @Test
    public void stateListenerRunsAfterTransitionBeforeCancellation() {
        CancellationToken token = CancellationToken.create();
        SettableFuture<String> task = SettableFuture.create();
        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), TIMER);

        AtomicReference<CancellationToken.State> observed = new AtomicReference<>();
        AtomicBoolean taskStillPending = new AtomicBoolean();
        token.addStateListener(state -> {
            observed.set(state);
            taskStillPending.set(!task.isDone());
        });

        token.cancel(true);

        assertThat(observed.get()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(taskStillPending).isTrue();
        assertThat(task).isCancelled();
    }

    @Test
    public void brokenLogHandlerDoesNotSkipCancellationCascade() {
        CancellationToken token = CancellationToken.create();
        SettableFuture<String> task = SettableFuture.create();
        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), TIMER);
        token.addStateListener(state -> {
            throw new IllegalStateException("listener boom");
        });
        Logger logger = Logger.getLogger(CancellationToken.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("handler boom");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        try {
            token.cancel(true);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(task)
                .as("a failing listener diagnostic must not skip the futureToken cancellation cascade")
                .isCancelled();
    }

    // ==================== originState ====================
    @Test
    public void originStateResolvesThroughPropagationChain() {
        CancellationToken grandparent = CancellationToken.create();
        CancellationToken parent = new CancellationToken(grandparent);
        CancellationToken child = new CancellationToken(parent);

        grandparent.timeoutCancel();

        assertThat(parent.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(child.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(child.originState()).isEqualTo(CancellationToken.State.TIMEOUT);
        assertThat(parent.originState()).isEqualTo(CancellationToken.State.TIMEOUT);
        assertThat(grandparent.originState()).isEqualTo(CancellationToken.State.TIMEOUT);
    }

    @Test
    public void originStateReportsMutualCancelAcrossPropagation() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);

        parent.cancel(true);

        assertThat(child.originState()).isEqualTo(CancellationToken.State.CANCELLED);
    }

    @Test
    public void originStateStopsAtTheOriginatingToken() {
        CancellationToken origin = CancellationToken.create();
        CancellationToken child = new CancellationToken(origin);
        CancellationToken grandchild = new CancellationToken(child);

        child.cancel(true);

        assertThat(grandchild.originState()).isEqualTo(CancellationToken.State.CANCELLED);
        assertThat(origin.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(origin.originState()).isEqualTo(CancellationToken.State.RUNNING);
    }

    // ==================== remaining ====================

    @Test
    public void remainingWithoutDeadlineIsExactlyTheMaxValueSentinel() {
        CancellationToken token = CancellationToken.create();

        // The sentinel stays a sentinel: remaining() reports Duration.ofNanos(Long.MAX_VALUE)
        // exactly, not a value eroded by subtracting the current nanoTime.
        assertThat(token.remaining()).isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
    }

    @Test
    public void remainingWithExpiredDeadlineIsZero() {
        CancellationToken token = withDeadlineAfter(-1000);

        assertThat(token.remaining().isNegative()).isFalse();
        assertThat(token.remaining().isZero()).isTrue();
    }

    @Test
    public void remainingWithUnexpiredDeadlineStaysPositiveAndBoundedByTheRequest() {
        CancellationToken token = withDeadlineAfter(60_000);

        assertThat(token.remaining().isNegative()).isFalse();
        assertThat(token.remaining().toNanos())
                .isGreaterThan(TimeUnit.SECONDS.toNanos(59))
                .isLessThanOrEqualTo(TimeUnit.MINUTES.toNanos(1));
    }

    @Test
    public void remainingWithNearMaxUnexpiredDeadlineIsNotReportedAsExpired() {
        // One nanosecond below the sentinel: an unexpired deadline keeps its remaining time
        // instead of collapsing to zero through saturated or wrapped subtraction.
        CancellationToken token = new CancellationToken(null, Long.MAX_VALUE - 1);

        assertThat(token.deadlineNanos()).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(token.remaining().toNanos()).isGreaterThan(TimeUnit.DAYS.toNanos(365 * 100));
    }
}

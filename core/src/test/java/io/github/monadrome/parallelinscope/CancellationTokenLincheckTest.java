package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lincheck exploration of the cancellation races that the repeated stress tests sample.
 *
 * <p>Each invocation builds a fresh token graph, starts the contending threads, and asserts the
 * invariant that must hold in every interleaving: the winning transition is exactly one terminal
 * state, bound work is cancelled either by the winning transition or by the bind callback, and a
 * state listener observes the terminal transition exactly once.
 */
@SuppressWarnings("FutureReturnValueIgnored")
@Timeout(120)
public class CancellationTokenLincheckTest {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();

    @AfterAll
    static void shutdownTimer() {
        TIMER.shutdownNow();
    }

    @Test
    void cancelRacingBindAlwaysCancelsBoundWork() {
        Lincheck.runConcurrentTest(10_000, () -> {
            CancellationToken token = CancellationToken.create();
            SettableFuture<Void> submission = SettableFuture.create();
            AtomicInteger notifications = new AtomicInteger();
            AtomicReference<SettableFuture<String>> bound = new AtomicReference<>();
            token.addStateListener(state -> notifications.incrementAndGet());

            Thread canceller = new Thread(() -> token.cancel());
            Thread binder = new Thread(() -> bound.set(PreparedStates.bindSingleton(token, submission, TIMER)));
            canceller.start();
            binder.start();
            join(canceller);
            join(binder);

            assertState("task", verifyNotNull(bound.get()).isCancelled());
            assertState("submission", submission.isCancelled());
            assertState("token state " + token.state(), token.state() == CancellationToken.State.CANCELLED);
            assertState("listener notifications " + notifications.get(), notifications.get() == 1);
        });
    }

    @Test
    void parentCancelRacingChildBindAlwaysCancelsChildWork() {
        Lincheck.runConcurrentTest(10_000, () -> {
            PreparedStates.TokenPair tokens = PreparedStates.parentChild();
            CancellationToken parent = tokens.parent();
            CancellationToken child = tokens.child();
            SettableFuture<Void> submission = SettableFuture.create();
            AtomicInteger notifications = new AtomicInteger();
            AtomicReference<SettableFuture<String>> bound = new AtomicReference<>();
            child.addStateListener(state -> notifications.incrementAndGet());

            Thread canceller = new Thread(() -> parent.cancel());
            Thread binder = new Thread(() -> bound.set(PreparedStates.bindSingleton(child, submission, TIMER)));
            canceller.start();
            binder.start();
            join(canceller);
            join(binder);

            assertState("parent state " + parent.state(), parent.state() == CancellationToken.State.CANCELLED);
            assertState("child state " + child.state(), child.state() == CancellationToken.State.PROPAGATED_CANCELLED);
            assertState("task", verifyNotNull(bound.get()).isCancelled());
            assertState("submission", submission.isCancelled());
            assertState("child listener notifications " + notifications.get(), notifications.get() == 1);
        });
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void assertState(String invariant, boolean holds) {
        if (!holds) {
            throw new AssertionError(invariant);
        }
    }
}

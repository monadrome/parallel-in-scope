package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the actual interaction between {@link CancellationToken#cancel(boolean)} and
 * {@link CancellationToken#bind}.
 *
 * <p>Guava's {@code SettableFuture.setFuture} returns {@code false} and cancels the supplied future
 * when the target is already cancelled. These tests also pin that {@code bind} is idempotent: a
 * second call is ignored, and its futures keep the state established by the first binding.
 */
@SuppressWarnings("FutureReturnValueIgnored")
class CancellationTokenBindRaceTest {

    private ScheduledExecutorService timer;

    @BeforeEach
    void setUp() {
        timer = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        timer.shutdownNow();
    }

    /**
     * When cancel() wins before bind(), {@code futureToken.setFuture(failFastFuture)} returns
     * false. Guava cancels the supplied {@code failFastFuture}, which propagates cancellation to
     * the actual task futures. The token remains in CANCELLED.
     */
    @Test
    void cancelBeforeBind_stillCancelsTasks() {
        CancellationToken token = CancellationToken.create();
        token.cancel(false);

        SettableFuture<String> task = SettableFuture.create();
        List<ListenableFuture<String>> futures = Collections.singletonList(task);

        token.bind(futures, Futures.immediateVoidFuture(), timer);

        assertThat(task).isCancelled();
        assertThat(token.state()).isEqualTo(CancellationToken.State.CANCELLED);
    }

    /**
     * A second bind is silently ignored: {@code futureToken} is already done (success), so
     * {@code setFuture} returns false and leaves the second futures untouched. The token state stays
     * tied to the first binding.
     */
    @Test
    void bindCalledTwice_secondBindingIsIgnored() throws InterruptedException {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> firstTask = SettableFuture.create();
        SettableFuture<String> secondTask = SettableFuture.create();

        token.bind(Collections.singletonList(firstTask), Futures.immediateVoidFuture(), timer);
        firstTask.set("ok");
        Thread.sleep(50);
        assertThat(token.state()).isEqualTo(CancellationToken.State.SUCCESS);

        token.bind(Collections.singletonList(secondTask), Futures.immediateVoidFuture(), timer);

        // The second futures are not tracked; the token stays SUCCESS and the second task is not
        // cancelled by the framework.
        assertThat(secondTask).isNotCancelled();
        assertThat(token.state()).isEqualTo(CancellationToken.State.SUCCESS);
    }

    /**
     * In a race, either cancel wins (and cancels failFastFuture) or bind wins (and futureToken
     * cancels failFastFuture later). In both cases the submitted task should end up cancelled.
     */
    @Test
    void concurrentCancelAndBind_cancelsTasksEitherWay() throws InterruptedException {
        int attempts = 1000;
        int notCancelled = 0;

        for (int i = 0; i < attempts; i++) {
            CancellationToken token = CancellationToken.create();
            SettableFuture<String> task = SettableFuture.create();
            List<ListenableFuture<String>> futures = Collections.singletonList(task);

            CountDownLatch start = new CountDownLatch(1);

            Thread canceller = new Thread(() -> {
                await(start);
                token.cancel(true);
            });
            Thread binder = new Thread(() -> {
                await(start);
                token.bind(futures, Futures.immediateVoidFuture(), timer);
            });

            canceller.start();
            binder.start();
            start.countDown();
            canceller.join();
            binder.join();

            if (!task.isCancelled()) {
                notCancelled++;
            }
        }

        assertThat(notCancelled)
                .withFailMessage(
                        "Expected all tasks to be cancelled in cancel/bind race, but %s out of %s were not",
                        notCancelled, attempts)
                .isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

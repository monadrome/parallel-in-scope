package io.github.huatalk.parallelinscope.internal;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the completion, cancellation, and queue-identity contracts of ListenableCompletionService. */
public class ListenableCompletionServiceTest {

    /** Verifies that cancellation is visible on the exact runnable held by the executor queue. */
    @Test
    public void submittedFutureIsTheQueuedRunnableAndCanBePurged() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ListeningExecutorService listeningPool = MoreExecutors.listeningDecorator(pool);
        ListenableCompletionService<Integer> service = new ListenableCompletionService<>(listeningPool);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            service.submit(() -> {
                workerStarted.countDown();
                releaseWorker.await();
                return 0;
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            ListenableFuture<Integer> queued = service.submit(() -> 1);

            assertThat(pool.getQueue()).containsExactly((Runnable) queued);
            assertThat(queued.cancel(false)).isTrue();
            assertThat(service.take()).isSameAs(queued);

            pool.purge();
            assertThat(pool.getQueue()).isEmpty();
        } finally {
            releaseWorker.countDown();
            pool.shutdownNow();
        }
    }

    /** Verifies both submission forms and all completion-queue retrieval methods. */
    @Test
    public void callableAndRunnableResultsEnterTheSuppliedCompletionQueue() throws Exception {
        LinkedBlockingQueue<ListenableFuture<Integer>> completions = new LinkedBlockingQueue<>();
        ListenableCompletionService<Integer> service =
                new ListenableCompletionService<>(Runnable::run, completions);

        ListenableFuture<Integer> callable = service.submit(() -> 42);
        ListenableFuture<Integer> runnable = service.submit(() -> { }, 7);
        ListenableFuture<Integer> nullResult = service.submit(() -> { }, null);

        assertThat(service.take()).isSameAs(callable);
        assertThat(service.poll()).isSameAs(runnable);
        assertThat(service.poll(10, TimeUnit.MILLISECONDS)).isSameAs(nullResult);
        assertThat(service.poll()).isNull();
        assertThat(callable.get()).isEqualTo(42);
        assertThat(runnable.get()).isEqualTo(7);
        assertThat(nullResult.get()).isNull();
        assertThat(completions).isEmpty();
    }

    /** Verifies that rejected tasks are not reported as completed. */
    @Test
    public void rejectedSubmissionDoesNotEnterTheCompletionQueue() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("rejected");
        };
        ListenableCompletionService<Integer> service =
                new ListenableCompletionService<>(rejectingExecutor);

        assertThatThrownBy(() -> service.submit(() -> 1))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("rejected");
        assertThat(service.poll()).isNull();
    }
}

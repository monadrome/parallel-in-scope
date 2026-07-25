package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests registration-time executor capability snapshots. */
public class ExecutorBindingTest {

    /** Verifies bounded worker pools are conservatively classified as deadlock-prone. */
    @Test
    public void boundedThreadPoolIsDeadlockProne() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            ExecutorBinding binding = bindingFor(executor);

            assertThat(binding.isDeadlockProne()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Verifies an elastic synchronous-queue pool is not classified as deadlock-prone. */
    @Test
    public void elasticThreadPoolIsNotDeadlockProne() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
        try {
            ExecutorBinding binding = bindingFor(executor);

            assertThat(binding.isDeadlockProne()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Verifies executors without inspectable pool capabilities remain conservatively risky. */
    @Test
    public void nonThreadPoolExecutorIsDeadlockProne() {
        ListeningExecutorService executor = MoreExecutors.newDirectExecutorService();
        try {
            ExecutorBinding binding = bindingFor(executor);

            assertThat(binding.isDeadlockProne()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Returns the immutable binding created through the production registry path. */
    private static ExecutorBinding bindingFor(ExecutorService executor) {
        return ParConfig.builder().executor("pool", executor).build().getExecutorBinding("pool");
    }
}

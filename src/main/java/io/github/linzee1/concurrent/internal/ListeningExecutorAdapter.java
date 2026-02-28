package io.github.linzee1.concurrent.internal;

import com.google.common.util.concurrent.ForwardingExecutorService;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Adapter utilities for bridging {@link ExecutorService} to Guava's
 * {@link ListeningExecutorService} and {@link ListeningScheduledExecutorService}.
 * <p>
 * Solves the problem where monitoring wrappers (decorator pattern) obscure
 * the original ListeningExecutorService interface.
 * <p>
 * Also provides JDK Future to ListenableFuture adaptation.
 *
 * @author linqh
 */
public final class ListeningExecutorAdapter {

    private ListeningExecutorAdapter() {
    }

    /**
     * Adapts an {@link ExecutorService} to a {@link ListeningExecutorService}.
     * If the input already implements ListeningExecutorService, returns it directly.
     *
     * @param executorService the executor service to adapt
     * @return a ListeningExecutorService
     */
    public static ListeningExecutorService adapt(ExecutorService executorService) {
        if (executorService instanceof ListeningExecutorService) {
            return (ListeningExecutorService) executorService;
        }
        return new ListeningExecutorServiceImpl(executorService);
    }

    /**
     * Adapts a {@link ScheduledExecutorService} to a {@link ListeningScheduledExecutorService}.
     *
     * @param executorService the scheduled executor service to adapt
     * @return a ListeningScheduledExecutorService
     */
    public static ListeningScheduledExecutorService adaptScheduled(ScheduledExecutorService executorService) {
        if (executorService instanceof ListeningScheduledExecutorService) {
            return (ListeningScheduledExecutorService) executorService;
        }
        return new ListeningScheduledExecutorImpl(executorService);
    }

    /**
     * Adapts a JDK {@link Future} to a Guava {@link ListenableFuture}.
     */
    public static <T> ListenableFuture<T> adaptFuture(Future<T> future) {
        return JdkFutureAdapters.listenInPoolThread(future);
    }

    /**
     * Adapts multiple JDK Futures to ListenableFutures.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> List<ListenableFuture<T>> adaptFutures(Future... futures) {
        return Arrays.stream(futures)
                .filter(Objects::nonNull)
                .map(x -> (Future<T>) x)
                .map(JdkFutureAdapters::listenInPoolThread)
                .collect(Collectors.toList());
    }

    // ==================== Inner Adapter Classes ====================

    @SuppressWarnings("all")
    static class ListeningExecutorServiceImpl extends ForwardingExecutorService implements ListeningExecutorService {
        private final ExecutorService delegate;

        ListeningExecutorServiceImpl(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        protected ExecutorService delegate() {
            return delegate;
        }

        @Override
        public <T> ListenableFuture<T> submit(Callable<T> task) {
            return (ListenableFuture<T>) super.submit(task);
        }

        @Override
        public ListenableFuture<?> submit(Runnable task) {
            return (ListenableFuture<?>) super.submit(task);
        }

        @Override
        public <T> ListenableFuture<T> submit(Runnable task, T result) {
            return (ListenableFuture<T>) super.submit(task, result);
        }
    }

    @SuppressWarnings("all")
    static class ListeningScheduledExecutorImpl extends ForwardingExecutorService implements ListeningScheduledExecutorService {
        private final ScheduledExecutorService delegate;

        ListeningScheduledExecutorImpl(ScheduledExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        protected ExecutorService delegate() {
            return delegate;
        }

        @Override
        public <T> ListenableFuture<T> submit(Callable<T> task) {
            return (ListenableFuture<T>) super.submit(task);
        }

        @Override
        public ListenableFuture<?> submit(Runnable task) {
            return (ListenableFuture<?>) super.submit(task);
        }

        @Override
        public <T> ListenableFuture<T> submit(Runnable task, T result) {
            return (ListenableFuture<T>) super.submit(task, result);
        }

        @Override
        public ListenableScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return (ListenableScheduledFuture<?>) delegate.schedule(command, delay, unit);
        }

        @Override
        public <V> ListenableScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return (ListenableScheduledFuture<V>) delegate.schedule(callable, delay, unit);
        }

        @Override
        public ListenableScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return (ListenableScheduledFuture<?>) delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override
        public ListenableScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return (ListenableScheduledFuture<?>) delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }
}

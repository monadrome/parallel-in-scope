package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Explicit observation scope for starvation signals produced by unstructured executor work.
 *
 * <p>The scope samples each distinct physical {@link ThreadPoolExecutor} registered with its
 * owning {@link ParRuntime}. It never wraps or submits user work. Close the scope after the
 * unstructured activity of interest, then read {@link #reportFuture()}.
 *
 * <p>A pool is considered potentially starved when it has no free worker, queued work remains, and
 * its completed-task count stays unchanged across sample pairs separated by the requested
 * interval. The evidence is sticky for the observation window. The condition is deliberately
 * bounded and conservative: a long CPU-bound task can produce the same signal, so the report does
 * not claim a dependency cycle.
 *
 * @since 0.4.0
 */
public final class ThreadPoolStarvationObservationScope implements AutoCloseable {

    private final ParRuntime owner;
    private final ImmutableList<ObservedExecutor> executors;
    private final Duration samplingInterval;
    private final long intervalNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicInteger samples = new AtomicInteger();
    private final SettableFuture<ThreadPoolStarvationReport> reportSink = SettableFuture.create();
    private final ListenableFuture<ThreadPoolStarvationReport> reportView = TaskObservation.readOnly(reportSink);
    private final AtomicReference<@Nullable ScheduledFuture<?>> sampler = new AtomicReference<>();
    private final AtomicReference<@Nullable Throwable> samplingFailure = new AtomicReference<>();
    private final AtomicReference<@Nullable SettableFuture<@Nullable Void>> samplingCompletion =
            new AtomicReference<>();

    ThreadPoolStarvationObservationScope(ParRuntime owner, Duration interval, Map<ParId, ExecutorRuntime> runtimes) {
        this.owner = checkNotNull(owner, "owner cannot be null");
        this.samplingInterval = Validation.requirePositive(interval, "sampling interval");
        this.intervalNanos = Deadlines.saturatedNanos(samplingInterval);
        this.executors = observedExecutors(runtimes);
        sampleNow();
        if (samplingFailure.get() == null) {
            ScheduledFuture<?> scheduled = owner.scheduleStarvationSampling(this::sampleNow, intervalNanos);
            sampler.set(scheduled);
            if (samplingFailure.get() != null) {
                scheduled.cancel(false);
            }
        }
    }

    /** Returns the immutable report published when this scope closes. */
    public ListenableFuture<ThreadPoolStarvationReport> reportFuture() {
        return reportView;
    }

    /** Returns whether close has begun; the report may still be publishing. */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Stops sampling, performs the final sample, and publishes the report. Repeated or concurrent
     * calls wait for the same publication; sampling failures are rethrown and recorded in the report
     * future.
     */
    @SuppressWarnings("ReferenceEquality")
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ScheduledFuture<?> scheduled = sampler.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            Error finalSamplingError = null;
            try {
                awaitSampling();
                try {
                    finalSample();
                } catch (Throwable failure) {
                    recordSamplingFailure(failure);
                    if (failure instanceof Error) {
                        finalSamplingError = (Error) failure;
                    }
                }
                Throwable failure = samplingFailure.get();
                Error samplingError = failure instanceof Error ? (Error) failure : finalSamplingError;
                try {
                    reportSink.set(report());
                } catch (RuntimeException | Error publicationFailure) {
                    if (samplingError == null) {
                        throw publicationFailure;
                    }
                    if (samplingError != publicationFailure) {
                        samplingError.addSuppressed(publicationFailure);
                    }
                }
                if (samplingError != null) {
                    throw samplingError;
                }
            } catch (RuntimeException | Error failure) {
                reportSink.setException(failure);
                throw failure;
            } finally {
                owner.starvationObservationClosed();
            }
        } else {
            awaitReportPublication();
        }
    }

    private void sampleNow() {
        if (closed.get() || samplingFailure.get() != null) {
            return;
        }
        if (!sampling.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!closed.get()) {
                sampleExecutors();
                samples.incrementAndGet();
            }
        } catch (Throwable failure) {
            recordSamplingFailure(failure);
        } finally {
            sampling.set(false);
            SettableFuture<@Nullable Void> completion = samplingCompletion.getAndSet(null);
            if (completion != null) {
                completion.set(null);
            }
        }
    }

    private void finalSample() {
        sampleExecutors();
        samples.incrementAndGet();
    }

    private void sampleExecutors() {
        for (ObservedExecutor executor : executors) {
            executor.sample(intervalNanos);
        }
    }

    private void recordSamplingFailure(Throwable failure) {
        samplingFailure.compareAndSet(null, failure);
        ScheduledFuture<?> scheduled = sampler.get();
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    private void awaitSampling() {
        if (!sampling.get()) {
            return;
        }
        SettableFuture<@Nullable Void> completion = SettableFuture.create();
        verify(samplingCompletion.compareAndSet(null, completion), "starvation sampling has multiple waiters");
        if (!sampling.get()) {
            completion.set(null);
        }
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    completion.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException impossible) {
                    verify(false, "starvation sampling completion failed");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ThreadPoolStarvationReport report() {
        List<ThreadPoolStarvationReport.Evidence> evidence = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (ObservedExecutor executor : executors) {
            ThreadPoolStarvationReport.Evidence issue = executor.issue.get();
            if (issue != null) {
                evidence.add(issue);
            }
            if (executor.pool == null) {
                unsupported.add(executor.label);
            }
        }
        Throwable failure = samplingFailure.get();
        return ThreadPoolStarvationReport.of(
                samples.get(),
                samplingInterval,
                evidence,
                unsupported,
                failure == null ? null : failureMessage(failure));
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getName() : message;
    }

    private void awaitReportPublication() {
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    reportSink.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException done) {
                    return;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ImmutableList<ObservedExecutor> observedExecutors(Map<ParId, ExecutorRuntime> runtimes) {
        IdentityHashMap<ExecutorService, Labels> distinct = new IdentityHashMap<>();
        for (Map.Entry<ParId, ExecutorRuntime> entry : runtimes.entrySet()) {
            ExecutorRuntime runtime = entry.getValue();
            ExecutorService executor = runtime.introspectableExecutor();
            Labels labels = distinct.get(executor);
            if (labels == null) {
                labels = new Labels(executor);
                distinct.put(executor, labels);
            }
            labels.values.add(entry.getKey().value());
        }
        List<ObservedExecutor> observed = new ArrayList<>();
        for (Labels labels : distinct.values()) {
            observed.add(new ObservedExecutor(String.join(",", labels.values), labels.executor));
        }
        return ImmutableList.copyOf(observed);
    }

    private static final class Labels {
        private final List<String> values = new ArrayList<>();
        private final ExecutorService executor;

        private Labels(ExecutorService executor) {
            this.executor = executor;
        }
    }

    private static final class ObservedExecutor {
        private final String label;
        private final @Nullable ThreadPoolExecutor pool;
        private final AtomicReference<@Nullable Sample> previous = new AtomicReference<>();
        private final AtomicInteger stagnantIntervals = new AtomicInteger();
        private final AtomicReference<ThreadPoolStarvationReport.@Nullable Evidence> issue = new AtomicReference<>();

        private ObservedExecutor(String label, ExecutorService executor) {
            this.label = label;
            this.pool = executor instanceof ThreadPoolExecutor ? (ThreadPoolExecutor) executor : null;
        }

        private void sample(long intervalNanos) {
            if (pool == null) {
                return;
            }
            Sample current = new Sample(
                    pool.getPoolSize(),
                    pool.getActiveCount(),
                    pool.getQueue().size(),
                    pool.getCompletedTaskCount(),
                    System.nanoTime());
            sample(current, intervalNanos);
        }

        private void sample(Sample current, long intervalNanos) {
            Sample prior = previous.getAndSet(current);
            if (prior != null
                    && prior.saturated()
                    && current.saturated()
                    && current.sampledAtNanos - prior.sampledAtNanos >= intervalNanos
                    && prior.completedTaskCount == current.completedTaskCount) {
                int stagnant = stagnantIntervals.incrementAndGet();
                issue.set(new ThreadPoolStarvationReport.Evidence(
                        label,
                        current.poolSize,
                        current.activeCount,
                        current.queueSize,
                        current.completedTaskCount,
                        stagnant));
            } else {
                stagnantIntervals.set(0);
            }
        }
    }

    private static final class Sample {
        private final long sampledAtNanos;
        private final int poolSize;
        private final int activeCount;
        private final int queueSize;
        private final long completedTaskCount;

        private Sample(int poolSize, int activeCount, int queueSize, long completedTaskCount, long sampledAtNanos) {
            this.sampledAtNanos = sampledAtNanos;
            this.poolSize = poolSize;
            this.activeCount = activeCount;
            this.queueSize = queueSize;
            this.completedTaskCount = completedTaskCount;
        }

        private boolean saturated() {
            return poolSize > 0 && activeCount >= poolSize && queueSize > 0;
        }
    }
}

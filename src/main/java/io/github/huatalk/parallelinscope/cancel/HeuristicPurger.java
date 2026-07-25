package io.github.huatalk.parallelinscope.cancel;

import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.parallelinscope.queue.SmartBlockingQueue;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coalesces cleanup of cancelled tasks retained by {@link SmartBlockingQueue} instances.
 * <p>
 * Queue pressure and cancelled-task ratio are concurrent snapshots used as advisory signals;
 * neither value is an exact queue accounting guarantee.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class HeuristicPurger {

    private static final Logger LOGGER = Logger.getLogger(HeuristicPurger.class.getName());
    private static final long CANCELLATION_QUIET_PERIOD_MILLIS = 50L;
    private static final Runnable NOOP = () -> { };

    private static final class PurgeExecutorHolder {
        private static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("ThreadPoolPurger-%d")
                        .build());
    }

    private final AtomicDouble queuePressureThreshold;
    private final AtomicDouble cancelledTaskRatioThreshold;
    private final ConcurrentHashMap<ThreadPoolExecutor, PoolState> states = new ConcurrentHashMap<>();

    /**
     * Creates a purger backed by atomically adjustable thresholds.
     *
     * @param queuePressureThreshold       minimum queue-size-to-capacity ratio
     * @param cancelledTaskRatioThreshold minimum estimated cancelled-task ratio
     */
    public HeuristicPurger(
            AtomicDouble queuePressureThreshold,
            AtomicDouble cancelledTaskRatioThreshold) {
        this.queuePressureThreshold = Objects.requireNonNull(queuePressureThreshold);
        this.cancelledTaskRatioThreshold = Objects.requireNonNull(cancelledTaskRatioThreshold);
    }

    /**
     * Returns the queued-cancellation callback bound to the submitted task's executor.
     * Unsupported queue implementations receive a static no-op callback.
     *
     * @param executor actual executor used to run the task
     * @return executor-bound cancellation callback
     */
    public Runnable cancellationObserverFor(ThreadPoolExecutor executor) {
        if (!(executor.getQueue() instanceof SmartBlockingQueue)) {
            return NOOP;
        }
        SmartBlockingQueue<?> queue = (SmartBlockingQueue<?>) executor.getQueue();
        PoolState state = states.computeIfAbsent(executor, ignored -> new PoolState(executor, queue));
        return state::onTaskCancelled;
    }

    private final class PoolState {

        private final ThreadPoolExecutor executor;
        private final SmartBlockingQueue<?> queue;
        private final AtomicLong pendingCancelled = new AtomicLong();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean forceNextPurge = new AtomicBoolean();

        /** Creates the cancellation accounting state for one actual executor. */
        private PoolState(ThreadPoolExecutor executor, SmartBlockingQueue<?> queue) {
            this.executor = executor;
            this.queue = queue;
        }

        /** Records one possible cancelled queue entry without scanning the queue. */
        private void onTaskCancelled() {
            long cancelled = pendingCancelled.incrementAndGet();
            if (isWorthwhile(cancelled)) {
                scheduleOnce(false);
            } else if (queue.isEmpty()) {
                pendingCancelled.compareAndSet(cancelled, 0);
            } else if (forceNextPurge.get()) {
                scheduleOnce(true);
            }
        }

        /** Evaluates advisory queue pressure and garbage ratio snapshots. */
        private boolean isWorthwhile(long cancelled) {
            int queueSize = queue.size();
            if (queueSize <= 0) {
                return false;
            }
            double queuePressure = (double) queueSize / queue.getCapacity();
            double cancelledRatio = (double) Math.min(cancelled, queueSize) / queueSize;
            return queuePressure >= queuePressureThreshold.get()
                    && cancelledRatio >= cancelledTaskRatioThreshold.get();
        }

        /** Coalesces concurrent cleanup demand into one background task. */
        private void scheduleOnce(boolean force) {
            if (force) {
                forceNextPurge.set(true);
            }
            if (!scheduled.compareAndSet(false, true)) {
                return;
            }
            scheduleAfterQuietPeriod(pendingCancelled.get());
        }

        /** Delays maintenance until the current cancellation burst becomes quiet. */
        private void scheduleAfterQuietPeriod(long observedCancelled) {
            try {
                PurgeExecutorHolder.INSTANCE.schedule(
                        () -> runAfterQuietPeriod(observedCancelled),
                        CANCELLATION_QUIET_PERIOD_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                scheduled.set(false);
                LOGGER.log(Level.WARNING, "Unable to schedule cancelled-task purge", e);
            }
        }

        /** Reschedules while cancellation signals are still arriving. */
        private void runAfterQuietPeriod(long observedCancelled) {
            long latestCancelled = pendingCancelled.get();
            if (latestCancelled != observedCancelled) {
                scheduleAfterQuietPeriod(latestCancelled);
                return;
            }
            runPurge();
        }

        /** Claims the current estimate and purges only if the latest snapshot still qualifies. */
        private void runPurge() {
            long claimed = pendingCancelled.getAndSet(0);
            boolean force = forceNextPurge.getAndSet(false);
            boolean failed = false;
            boolean purged = false;
            try {
                if (claimed > 0 && !queue.isEmpty() && (force || isWorthwhile(claimed))) {
                    executor.purge();
                    purged = true;
                }
            } catch (RuntimeException e) {
                pendingCancelled.addAndGet(claimed);
                forceNextPurge.set(force);
                failed = true;
                LOGGER.log(Level.WARNING, "Unable to purge cancelled tasks", e);
            } finally {
                scheduled.set(false);
            }
            long remaining = pendingCancelled.get();
            if (!failed && remaining > 0 && (purged || isWorthwhile(remaining))) {
                scheduleOnce(purged);
            }
        }
    }
}

package io.github.monadrome.parallelinscope;

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test clock: a manual ticker paired with a scheduler whose tasks fire only when the ticker
 * advances past their due time. Advancing the clock runs due tasks on the advancing thread; it
 * never runs task bodies, which still execute on real executor threads with real interruption.
 */
final class ManualClock {

    private final Object lock = new Object();
    private final PriorityQueue<Entry> queue = new PriorityQueue<>();
    private long nanos;

    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            synchronized (lock) {
                return nanos;
            }
        }
    };

    private final ScheduledExecutorService scheduler = new ManualScheduler();

    private final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Entry entry = new Entry(command, read() + unit.toNanos(delay));
            synchronized (lock) {
                queue.add(entry);
            }
            return entry;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("manual clock only supports Runnable deadlines");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("manual clock does not support periodic tasks");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("manual clock does not support periodic tasks");
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException("manual clock only supports schedule()");
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

    Ticker ticker() {
        return ticker;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    long read() {
        return ticker.read();
    }

    /** Handles still scheduled and neither run nor cancelled — the scheduler-retention probe. */
    int pendingHandles() {
        synchronized (lock) {
            int count = 0;
            for (Entry entry : queue) {
                if (!entry.isDone()) {
                    count++;
                }
            }
            return count;
        }
    }

    /** Advances the clock and runs every task whose deadline the advance crossed. */
    void advance(Duration duration) {
        synchronized (lock) {
            nanos += duration.toNanos();
        }
        for (; ; ) {
            Entry due;
            synchronized (lock) {
                Entry head = queue.peek();
                if (head == null) {
                    return;
                }
                if (head.cancelled) {
                    queue.poll();
                    continue;
                }
                if (head.dueNanos > nanos) {
                    return;
                }
                due = java.util.Objects.requireNonNull(queue.poll());
            }
            // Run outside the lock: a firing timeout cancels the token, whose completion listener
            // cancels the entry handle back into this clock.
            due.run();
        }
    }

    private final class Entry implements ScheduledFuture<Object>, Runnable {
        private final Runnable task;
        private final long dueNanos;
        private boolean cancelled;
        private boolean ran;

        Entry(Runnable task, long dueNanos) {
            this.task = task;
            this.dueNanos = dueNanos;
        }

        @Override
        public void run() {
            synchronized (lock) {
                if (cancelled || ran) {
                    return;
                }
                ran = true;
            }
            task.run();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lock) {
                if (ran || cancelled) {
                    return false;
                }
                cancelled = true;
                return true;
            }
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        @Override
        public boolean isDone() {
            synchronized (lock) {
                return ran || cancelled;
            }
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("a manual-clock handle carries no value");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("a manual-clock handle carries no value");
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueNanos - read(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(dueNanos, ((Entry) other).dueNanos);
        }
    }
}

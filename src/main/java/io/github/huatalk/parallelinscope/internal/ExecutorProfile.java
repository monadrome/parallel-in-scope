package io.github.huatalk.parallelinscope.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

/** Immutable submission-time snapshot of executor capabilities used by TaskGraph. */
public final class ExecutorProfile {

    private static final ExecutorProfile UNKNOWN = new ExecutorProfile(false, "unknown", -1, true);

    private final boolean threadPool;
    private final String queueType;
    private final int maximumPoolSize;
    private final boolean deadlockProne;

    /**
     * Captures the capabilities of an executor without retaining it.
     *
     * @param executor executor to inspect
     * @return immutable capability snapshot
     */
    public static ExecutorProfile capture(ExecutorService executor) {
        if (!(executor instanceof ThreadPoolExecutor)) {
            return UNKNOWN;
        }
        ThreadPoolExecutor threadPool = (ThreadPoolExecutor) executor;
        boolean elastic = threadPool.getQueue() instanceof SynchronousQueue
                || threadPool.getMaximumPoolSize() >= Integer.MAX_VALUE;
        return new ExecutorProfile(
                true,
                threadPool.getQueue().getClass().getName(),
                threadPool.getMaximumPoolSize(),
                !elastic);
    }

    /**
     * Returns the conservative profile used when executor capabilities are unavailable.
     *
     * @return shared unknown profile
     */
    public static ExecutorProfile unknown() {
        return UNKNOWN;
    }

    /** Creates an immutable capability snapshot. */
    private ExecutorProfile(
            boolean threadPool,
            String queueType,
            int maximumPoolSize,
            boolean deadlockProne) {
        this.threadPool = threadPool;
        this.queueType = queueType;
        this.maximumPoolSize = maximumPoolSize;
        this.deadlockProne = deadlockProne;
    }

    /**
     * Returns whether the registered executor was a ThreadPoolExecutor.
     *
     * @return {@code true} for a captured ThreadPoolExecutor
     */
    public boolean isThreadPool() {
        return threadPool;
    }

    /**
     * Returns the captured work-queue class name.
     *
     * @return queue class name, or {@code unknown}
     */
    public String getQueueType() {
        return queueType;
    }

    /**
     * Returns the captured maximum pool size, or {@code -1} when unknown.
     *
     * @return maximum worker count, or {@code -1}
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Returns whether nested blocking dependencies can conservatively deadlock this executor.
     *
     * @return {@code true} when the executor is bounded or unknown
     */
    public boolean isDeadlockProne() {
        return deadlockProne;
    }
}

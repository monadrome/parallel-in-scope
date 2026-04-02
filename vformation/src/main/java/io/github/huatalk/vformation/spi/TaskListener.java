package io.github.huatalk.vformation.spi;

/**
 * SPI: Task lifecycle listener for metrics collection and monitoring.
 * <p>
 * Implementations can record task execution times, queue wait times, etc.
 * Register via {@link io.github.huatalk.vformation.scope.ParConfig.Builder#taskListener(TaskListener)}.
 * <p>
 * All time values are in nanoseconds.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public interface TaskListener {

    /**
     * Called when a task completes execution (both success and failure).
     *
     * @param event task execution event containing timing and metadata
     */
    void onTaskComplete(TaskEvent event);

    /**
     * Task execution event, contains all timing and metadata information
     */
    class TaskEvent {
        private final String taskName;
        private final long submitTimeNanos;
        private final long startTimeNanos;
        private final long endTimeNanos;
        private final boolean enqueued;
        private final Throwable exception;

        public TaskEvent(String taskName, long submitTimeNanos, long startTimeNanos,
                         long endTimeNanos, boolean enqueued, Throwable exception) {
            this.taskName = taskName;
            this.submitTimeNanos = submitTimeNanos;
            this.startTimeNanos = startTimeNanos;
            this.endTimeNanos = endTimeNanos;
            this.enqueued = enqueued;
            this.exception = exception;
        }

        public String getTaskName() { return taskName; }
        public long getSubmitTimeNanos() { return submitTimeNanos; }
        public long getStartTimeNanos() { return startTimeNanos; }
        public long getEndTimeNanos() { return endTimeNanos; }
        public boolean isEnqueued() { return enqueued; }
        public Throwable getException() { return exception; }

        /** Execution duration in nanoseconds */
        public long executionTimeNanos() { return endTimeNanos - startTimeNanos; }

        /** Queue wait duration in nanoseconds */
        public long waitTimeNanos() { return startTimeNanos - submitTimeNanos; }

        /** Total duration from submit to completion in nanoseconds */
        public long totalTimeNanos() { return endTimeNanos - submitTimeNanos; }

        /** Execution duration in milliseconds */
        public long executionTimeMillis() { return executionTimeNanos() / 1_000_000L; }

        /** Queue wait duration in milliseconds */
        public long waitTimeMillis() { return waitTimeNanos() / 1_000_000L; }

        /** Total duration in milliseconds */
        public long totalTimeMillis() { return totalTimeNanos() / 1_000_000L; }
    }
}

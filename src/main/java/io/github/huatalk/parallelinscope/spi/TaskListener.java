package io.github.huatalk.parallelinscope.spi;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * SPI: Task lifecycle listener for metrics collection and monitoring.
 * <p>
 * Implementations can record task execution times, queue wait times, etc.
 * Register via {@link io.github.huatalk.parallelinscope.scope.ParConfig.Builder#taskListener(TaskListener)}.
 * <p>
 * Timing methods return {@link Duration}. Raw nanos timestamps are available via getters.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@FunctionalInterface
public interface TaskListener {

    /**
     * Called when a task completes execution (both success and failure).
     *
     * @param event task execution event containing timing and metadata
     */
    void onTaskComplete(TaskEvent event);

    /** Timing and outcome data for a completed task. */
    class TaskEvent {
        private final String taskName;
        private final long submitTimeNanos;
        private final long startTimeNanos;
        private final long endTimeNanos;
        private final boolean enqueued;
        private final Throwable exception;

        /**
         * Creates an immutable task lifecycle event.
         *
         * @param taskName       the logical task name
         * @param submitTimeNanos ticker reading when the task was submitted
         * @param startTimeNanos ticker reading when execution started
         * @param endTimeNanos   ticker reading when execution completed
         * @param enqueued       whether the task was classified as queued
         * @param exception      the task failure, or {@code null} on success
         */
        public TaskEvent(String taskName, long submitTimeNanos, long startTimeNanos,
                         long endTimeNanos, boolean enqueued, @Nullable Throwable exception) {
            this.taskName = taskName;
            this.submitTimeNanos = submitTimeNanos;
            this.startTimeNanos = startTimeNanos;
            this.endTimeNanos = endTimeNanos;
            this.enqueued = enqueued;
            this.exception = exception;
        }

        /**
         * Returns the logical task name.
         * @return the logical task name
         */
        public String getTaskName() { return taskName; }
        /**
         * Gets the ticker reading at submission.
         *
         * @return the ticker reading in nanoseconds
         */
        public long getSubmitTimeNanos() { return submitTimeNanos; }
        /**
         * Gets the ticker reading at execution start.
         *
         * @return the ticker reading in nanoseconds
         */
        public long getStartTimeNanos() { return startTimeNanos; }
        /**
         * Gets the ticker reading at completion.
         *
         * @return the ticker reading in nanoseconds
         */
        public long getEndTimeNanos() { return endTimeNanos; }
        /**
         * Checks whether the task was classified as queued.
         *
         * @return {@code true} if the measured queue wait exceeded the threshold
         */
        public boolean isEnqueued() { return enqueued; }
        /**
         * Gets the task failure.
         *
         * @return the failure, or {@code null} on success
         */
        @Nullable
        public Throwable getException() { return exception; }

        /**
         * Calculates the execution duration.
         *
         * @return the execution duration
         */
        public Duration executionTime() { return Duration.ofNanos(endTimeNanos - startTimeNanos); }

        /**
         * Calculates the queue wait duration.
         *
         * @return the queue wait duration
         */
        public Duration waitTime() { return Duration.ofNanos(startTimeNanos - submitTimeNanos); }

        /**
         * Calculates the total duration.
         *
         * @return the duration from submission to completion
         */
        public Duration totalTime() { return Duration.ofNanos(endTimeNanos - submitTimeNanos); }

    }
}

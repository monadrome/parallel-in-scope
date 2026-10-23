package io.github.monadrome.parallelinscope;

/**
 * Optional workload declaration shared by task units and registered {@link Par} entries.
 *
 * <p>On a unit ({@link BatchOptions#taskType(TaskType)} or {@link TaskOptions#taskType(TaskType)})
 * the type describes what the body does. On a registration ({@link
 * ParRuntime.Builder#register(ParId, java.util.concurrent.ExecutorService, TaskType)}) it describes
 * the work the entry is meant to carry. Both default to {@link #UNKNOWN}, and a nested unit never
 * inherits its parent's type.
 *
 * <p>The type is diagnostic only. It never changes admission, enqueueing, rejection, cancellation,
 * deadlines, attribution, or a task's outcome; enqueue refusal is requested only through {@code
 * rejectEnqueue}. Two consumers read it:
 *
 * <ul>
 *   <li>Submission compares a unit's type with its entry's type. When both are declared and
 *       differ, the library logs the mismatch once per entry and unit name.
 *   <li>The runtime always compares the thread CPU time of library-admitted bodies with their
 *       execution time where either side declares {@link #COMPUTE}, logs low CPU use once, and
 *       reports it in {@link ParRuntime#workloadFitReport()}.
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @since 0.3.0
 */
public enum TaskType {
    /** Mostly consumes CPU and should not hold its thread while waiting. */
    COMPUTE,
    /**
     * Blocks its thread at some point: I/O, locks, sleeping, joining, or waiting for other tasks,
     * including nested batches.
     */
    BLOCKING,
    /** Not declared. The default; never takes part in a mismatch or a low-CPU verdict. */
    UNKNOWN
}

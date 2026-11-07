package io.github.monadrome.parallelinscope;

import java.lang.management.ThreadMXBean;

/**
 * The clocks behind workload-fit screening: a monotonic wall clock and the calling thread's CPU
 * clock. Package-private seam so tests can script both; production uses {@link
 * SystemWorkloadClock#INSTANCE}.
 */
interface WorkloadClock {

    /** A monotonic wall-clock reading in nanoseconds. */
    long wallNanos();

    /**
     * The CPU time the calling thread has consumed in nanoseconds, or a negative value when it is
     * unsupported, disabled, or unreadable. Screening only reads this; the library never calls {@link
     * ThreadMXBean#setThreadCpuTimeEnabled(boolean)}.
     */
    long currentThreadCpuNanos();
}

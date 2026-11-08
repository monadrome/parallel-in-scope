package io.github.monadrome.parallelinscope;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.jspecify.annotations.Nullable;

/**
 * System wall and thread-CPU clocks for workload-fit screening. An unavailable management API is
 * reported as an unreadable CPU clock rather than a failure.
 */
enum SystemWorkloadClock implements WorkloadClock {
    /*
     * Resolve ThreadMXBean lazily once: the JDK 8 factory is synchronized, and a runtime without
     * java.management must remain usable without CPU screening.
     */

    INSTANCE;

    @Override
    public long wallNanos() {
        return System.nanoTime();
    }

    @Override
    public long currentThreadCpuNanos() {
        ThreadMXBean threads = Threads.BEAN;
        if (threads == null) {
            return -1L;
        }
        try {
            return threads.getCurrentThreadCpuTime();
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    private static final class Threads {
        static final @Nullable ThreadMXBean BEAN = load();

        private static @Nullable ThreadMXBean load() {
            try {
                return ManagementFactory.getThreadMXBean();
            } catch (RuntimeException | LinkageError unavailable) {
                return null;
            }
        }
    }
}

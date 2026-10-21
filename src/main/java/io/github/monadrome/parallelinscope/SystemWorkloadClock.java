package io.github.monadrome.parallelinscope;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.jspecify.annotations.Nullable;

/**
 * The JVM's clocks behind workload-fit screening. The {@link ThreadMXBean} is resolved once, on
 * first use, because the JDK 8 factory method is synchronized; a runtime without {@code
 * java.management} reads as having no CPU clock rather than failing the body that asked.
 */
enum SystemWorkloadClock implements WorkloadClock {
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

    @Override
    public boolean cpuTimeAvailable() {
        ThreadMXBean threads = Threads.BEAN;
        if (threads == null) {
            return false;
        }
        try {
            return threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        } catch (RuntimeException unavailable) {
            return false;
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

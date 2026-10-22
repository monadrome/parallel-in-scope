package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A {@link WorkloadClock} whose wall and CPU readings advance only when the calling thread spends
 * scripted time, so each body's measured ratio is exactly what it declares.
 */
final class ScriptedWorkloadClock implements WorkloadClock {
    private final ThreadLocal<long[]> elapsed = ThreadLocal.withInitial(() -> new long[2]);
    private final AtomicInteger wallReads = new AtomicInteger();
    private final AtomicInteger cpuReads = new AtomicInteger();
    private volatile boolean available = true;
    private volatile boolean readable = true;
    private volatile @Nullable Error failure;

    /** Advances the calling thread's clocks as a body that ran for {@code execution} using {@code cpu}. */
    void spend(Duration execution, Duration cpu) {
        long[] clocks = elapsed.get();
        clocks[0] += execution.toNanos();
        clocks[1] += cpu.toNanos();
    }

    /** Whether windows opened from now on can read thread CPU time. */
    void available(boolean available) {
        this.available = available;
    }

    /** Whether CPU readings from now on succeed; a failed reading returns a negative value. */
    void readable(boolean readable) {
        this.readable = readable;
    }

    /** Makes the next CPU readings throw {@code failure}, standing in for a VM error; null stops it. */
    void failWith(@Nullable Error failure) {
        this.failure = failure;
    }

    int wallReads() {
        return wallReads.get();
    }

    int cpuReads() {
        return cpuReads.get();
    }

    @Override
    public long wallNanos() {
        wallReads.incrementAndGet();
        return elapsed.get()[0];
    }

    @Override
    public long currentThreadCpuNanos() {
        cpuReads.incrementAndGet();
        Error error = failure;
        if (error != null) {
            throw error;
        }
        return readable ? elapsed.get()[1] : -1L;
    }

    @Override
    public boolean cpuTimeAvailable() {
        return available;
    }
}

package io.github.monadrome.parallelinscope;

import com.google.common.collect.Maps;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded once-per-pair memory of the submission-time {@link TaskType} mismatches one {@link
 * ParRuntime} has reported.
 *
 * <p>A pair is a {@link Par} and a unit name. The direction needs no key of its own: an entry has
 * one declared type, so every mismatch of a pair points the same way. The memory holds at most
 * {@link #CAPACITY} pairs and never evicts, so a reported pair stays quiet for the runtime's
 * lifetime. A pair claims a slot only after its insertion succeeds, so racing first sightings of
 * one pair claim one slot. Once the memory is full, a new pair is counted instead of remembered:
 * every such admission increments {@link #suppressed()}, and the first of them is reported as the
 * overflow. A duplicate racing with a pair whose insertion is being rolled back at the capacity
 * boundary may read as silent instead of suppressed.
 */
final class TaskTypeMismatches {
    static final int CAPACITY = 1024;

    /** What the caller should report for one mismatched admission. */
    enum Outcome {
        /** The pair was stored now; report it individually. */
        FIRST,
        /** Nothing to report: the pair was already reported, or the overflow was. */
        SILENT,
        /** The memory is full and this admission is the first to find it so; report the overflow once. */
        OVERFLOW
    }

    private final Set<Map.Entry<ParId, String>> reported = ConcurrentHashMap.newKeySet();
    private final AtomicInteger stored = new AtomicInteger();
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final AtomicLong suppressed = new AtomicLong();

    /** Records one mismatched admission of {@code unitName} through {@code par}. */
    Outcome record(ParId par, String unitName) {
        Map.Entry<ParId, String> pair = Maps.immutableEntry(par, unitName);
        if (reported.contains(pair)) {
            return Outcome.SILENT;
        }
        if (stored.get() < CAPACITY) {
            if (!reported.add(pair)) {
                return Outcome.SILENT;
            }
            if (stored.incrementAndGet() <= CAPACITY) {
                return Outcome.FIRST;
            }
            reported.remove(pair);
            stored.decrementAndGet();
        }
        suppressed.incrementAndGet();
        return overflowReported.compareAndSet(false, true) ? Outcome.OVERFLOW : Outcome.SILENT;
    }

    /** Mismatched admissions not reported individually because the memory was full. */
    long suppressed() {
        return suppressed.get();
    }
}

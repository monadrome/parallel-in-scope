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
 * lifetime. Once the capacity is used, a new pair is counted instead of remembered: the first such
 * mismatch is reported as the overflow, and every later one only increments {@link #suppressed()}.
 * The table never claims to recognize a pair it could not store.
 */
final class TaskTypeMismatches {
    static final int CAPACITY = 1024;

    /** What the caller should report for one mismatched admission. */
    enum Outcome {
        /** The pair was stored now; report it individually. */
        FIRST,
        /** Nothing to report: the pair was already reported, or the overflow was. */
        SILENT,
        /** The capacity was used up by this admission; report the overflow once. */
        OVERFLOW
    }

    private final Set<Map.Entry<ParId, String>> reported = ConcurrentHashMap.newKeySet();
    private final AtomicInteger freeSlots = new AtomicInteger(CAPACITY);
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final AtomicLong suppressed = new AtomicLong();

    /** Records one mismatched admission of {@code unitName} through {@code par}. */
    Outcome record(ParId par, String unitName) {
        Map.Entry<ParId, String> pair = Maps.immutableEntry(par, unitName);
        if (reported.contains(pair)) {
            return Outcome.SILENT;
        }
        if (claimSlot()) {
            return reported.add(pair) ? Outcome.FIRST : Outcome.SILENT;
        }
        suppressed.incrementAndGet();
        return overflowReported.compareAndSet(false, true) ? Outcome.OVERFLOW : Outcome.SILENT;
    }

    /** Mismatched admissions not reported individually because the memory was full. */
    long suppressed() {
        return suppressed.get();
    }

    private boolean claimSlot() {
        return freeSlots.getAndUpdate(free -> free == 0 ? 0 : free - 1) > 0;
    }
}

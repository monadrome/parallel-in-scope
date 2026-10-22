package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded once-per-pair memory of the submission-time {@link TaskType} mismatches one {@link
 * ParRuntime} has reported.
 *
 * <p>A pair is a {@link Par} and a unit name. The direction needs no key of its own: an entry has
 * one declared type, so every mismatch of a pair points the same way. The memory is one immutable
 * set replaced by compare-and-set, so every admission is decided against a single state: a pair is
 * remembered exactly when its own insertion succeeds, the set never holds more than its capacity,
 * and it never evicts. Once the set is full, every admission of a pair it does not hold increments
 * {@link #suppressed()}, and the first of them is reported as the overflow.
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

    private final int capacity;
    private final AtomicReference<ImmutableSet<Map.Entry<ParId, String>>> reported =
            new AtomicReference<>(ImmutableSet.of());
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final AtomicLong suppressed = new AtomicLong();

    TaskTypeMismatches() {
        this(CAPACITY);
    }

    /** Test seam: a smaller capacity, so boundary races can repeat cheaply. */
    @VisibleForTesting
    TaskTypeMismatches(int capacity) {
        checkArgument(capacity > 0, "capacity %s must be positive", capacity);
        this.capacity = capacity;
    }

    /** Records one mismatched admission of {@code unitName} through {@code par}. */
    Outcome record(ParId par, String unitName) {
        Map.Entry<ParId, String> pair = Maps.immutableEntry(par, unitName);
        for (; ; ) {
            ImmutableSet<Map.Entry<ParId, String>> current = verifyNotNull(reported.get());
            if (current.contains(pair)) {
                return Outcome.SILENT;
            }
            if (current.size() >= capacity) {
                suppressed.incrementAndGet();
                return overflowReported.compareAndSet(false, true) ? Outcome.OVERFLOW : Outcome.SILENT;
            }
            ImmutableSet<Map.Entry<ParId, String>> next =
                    ImmutableSet.<Map.Entry<ParId, String>>builderWithExpectedSize(current.size() + 1)
                            .addAll(current)
                            .add(pair)
                            .build();
            if (reported.compareAndSet(current, next)) {
                return Outcome.FIRST;
            }
        }
    }

    /** Mismatched admissions not reported individually because the memory was full. */
    long suppressed() {
        return suppressed.get();
    }
}

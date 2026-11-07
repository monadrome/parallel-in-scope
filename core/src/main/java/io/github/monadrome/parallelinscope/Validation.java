package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Duration;

/**
 * The argument checks the public option types, identities, and declaration entries share.
 *
 * <p>Each method returns its argument so it can wrap a field assignment, and each throws what
 * Guava's taxonomy prescribes for a caller contract violation: {@link NullPointerException} for a
 * null rejection, {@link IllegalArgumentException} for a value the caller could have checked.
 */
final class Validation {

    private Validation() {}

    /**
     * Rejects a null or blank name.
     *
     * @param name the name to check
     * @param what the argument's role, used to open the message ({@code "group name"})
     * @return {@code name}
     */
    static String requireName(String name, String what) {
        checkNotNull(name, "%s cannot be null", what);
        checkArgument(!name.trim().isEmpty(), "%s cannot be blank", what);
        return name;
    }

    /**
     * Rejects a null, zero, or negative duration, for a budget where zero would mean "already
     * expired" rather than a choice a caller can have meant.
     *
     * @param duration the duration to check
     * @param what the argument's role, used to open the message ({@code "timeout"})
     * @return {@code duration}
     */
    static Duration requirePositive(Duration duration, String what) {
        checkNotNull(duration, "%s cannot be null", what);
        checkArgument(!duration.isNegative() && !duration.isZero(), "%s must be positive: %s", what, duration);
        return duration;
    }

    /**
     * Rejects a null or negative duration, for a budget where zero is a meaningful choice — a zero
     * close grace makes {@code close()} cancel-only.
     *
     * @param duration the duration to check
     * @param what the argument's role, used to open the message ({@code "closeGrace"})
     * @return {@code duration}
     */
    static Duration requireNonNegative(Duration duration, String what) {
        checkNotNull(duration, "%s cannot be null", what);
        checkArgument(!duration.isNegative(), "%s must not be negative: %s", what, duration);
        return duration;
    }

    /**
     * Rejects a zero or negative count, for a limit where zero would mean "no workers at all"
     * rather than a choice a caller can have meant.
     *
     * @param count the count to check
     * @param what the argument's role, used to open the message ({@code "parallelism"})
     * @return {@code count}
     */
    static int requirePositive(int count, String what) {
        checkArgument(count > 0, "%s must be positive: %s", what, count);
        return count;
    }

    /**
     * Rejects a negative count.
     *
     * @param count the count to check
     * @param what the argument's role, used to open the message ({@code "taskCount"})
     * @return {@code count}
     */
    static int requireNonNegative(int count, String what) {
        checkArgument(count >= 0, "%s must not be negative: %s", what, count);
        return count;
    }
}

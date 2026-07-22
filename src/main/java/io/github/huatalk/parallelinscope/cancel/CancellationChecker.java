package io.github.huatalk.parallelinscope.cancel;

import java.time.Duration;
import java.util.Objects;

/**
 * Limits cancellation checks by count, time, or both.
 *
 * <p>The checker can either bind a cancellation action at construction or only report when a
 * check is due. It does not depend on a particular cancellation mechanism. Instances are stateful
 * and intended for use by one task.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class CancellationChecker {

    private final int minCalls;
    private final long minIntervalNanos;
    private final Runnable checkAction;
    private int remainingCalls;
    private long nextEligibleTimeNanos;

    private CancellationChecker(
            int minCalls, Duration minInterval, Runnable checkAction) {
        if (minInterval != null && (minInterval.isZero() || minInterval.isNegative())) {
            throw new IllegalArgumentException("minInterval must be positive");
        }
        this.minCalls = minCalls;
        this.remainingCalls = minCalls;
        this.minIntervalNanos = minInterval == null ? 0 : minInterval.toNanos();
        this.nextEligibleTimeNanos = minInterval == null
                ? 0
                : System.nanoTime() + minIntervalNanos;
        this.checkAction = checkAction;
    }

    /**
     * Creates an unbound checker due every {@code calls} calls.
     *
     * @param calls number of calls between checks
     * @return a count-based checker
     */
    public static CancellationChecker every(int calls) {
        requirePositive(calls, "calls");
        return new CancellationChecker(calls, null, null);
    }

    /**
     * Creates a checker that runs the supplied action every {@code calls} calls.
     *
     * @param calls number of calls between checks
     * @param cancellationCheck cancellation action to run
     * @return a count-based checker
     */
    public static CancellationChecker every(int calls, Runnable cancellationCheck) {
        requirePositive(calls, "calls");
        return new CancellationChecker(
                calls, null, Objects.requireNonNull(cancellationCheck, "cancellationCheck"));
    }

    /**
     * Creates an unbound checker due after each interval.
     *
     * @param interval minimum duration between checks
     * @return a time-based checker
     */
    public static CancellationChecker every(Duration interval) {
        return new CancellationChecker(
                0, Objects.requireNonNull(interval, "interval"), null);
    }

    /**
     * Creates a checker that runs the supplied action after each interval.
     *
     * @param interval minimum duration between checks
     * @param cancellationCheck cancellation action to run
     * @return a time-based checker
     */
    public static CancellationChecker every(Duration interval, Runnable cancellationCheck) {
        return new CancellationChecker(
                0,
                Objects.requireNonNull(interval, "interval"),
                Objects.requireNonNull(cancellationCheck, "cancellationCheck"));
    }

    /**
     * Creates an unbound checker that requires both count and time boundaries.
     *
     * @param minCalls minimum number of calls between checks
     * @param minInterval minimum duration between checks
     * @return a checker using both boundaries
     */
    public static CancellationChecker whenBoth(int minCalls, Duration minInterval) {
        requirePositive(minCalls, "minCalls");
        return new CancellationChecker(
                minCalls, Objects.requireNonNull(minInterval, "minInterval"), null);
    }

    /**
     * Creates a checker that runs the supplied action when both boundaries are reached.
     *
     * @param minCalls minimum number of calls between checks
     * @param minInterval minimum duration between checks
     * @param cancellationCheck cancellation action to run
     * @return a checker using both boundaries
     */
    public static CancellationChecker whenBoth(
            int minCalls, Duration minInterval, Runnable cancellationCheck) {
        requirePositive(minCalls, "minCalls");
        return new CancellationChecker(
                minCalls,
                Objects.requireNonNull(minInterval, "minInterval"),
                Objects.requireNonNull(cancellationCheck, "cancellationCheck"));
    }

    /**
     * Returns whether the configured count and time boundaries have been reached.
     *
     * @return {@code true} when a cancellation check is due
     */
    public boolean shouldCheck() {
        if (minCalls > 0) {
            if (remainingCalls > 0 && --remainingCalls > 0) {
                return false;
            }
            if (minIntervalNanos == 0) {
                remainingCalls = minCalls;
                return true;
            }
        }

        long now = System.nanoTime();
        if (now < nextEligibleTimeNanos) {
            return false;
        }
        remainingCalls = minCalls;
        nextEligibleTimeNanos = now + minIntervalNanos;
        return true;
    }

    /**
     * Runs the check bound at construction when the configured boundaries are reached.
     *
     * @return {@code true} when the bound check ran
     * @throws IllegalStateException if no check was bound at construction
     */
    public boolean check() {
        if (checkAction == null) {
            throw new IllegalStateException("no cancellation check is bound");
        }
        if (!shouldCheck()) {
            return false;
        }
        checkAction.run();
        return true;
    }

    /**
     * Runs the supplied check when the configured boundaries are reached.
     *
     * @param check cancellation action to run
     * @return {@code true} when the supplied check ran
     */
    public boolean checkIfDue(Runnable check) {
        Objects.requireNonNull(check, "check");
        if (!shouldCheck()) {
            return false;
        }
        check.run();
        return true;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

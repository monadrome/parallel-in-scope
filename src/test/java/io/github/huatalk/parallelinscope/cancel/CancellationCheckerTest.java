package io.github.huatalk.parallelinscope.cancel;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationCheckerTest {

    @Test
    void countBoundaryControlsWhenCheckIsDue() {
        CancellationChecker checker = CancellationChecker.every(3);

        assertFalse(checker.shouldCheck());
        assertFalse(checker.shouldCheck());
        assertTrue(checker.shouldCheck());
    }

    @Test
    void combinedModeRequiresCountAndTimeBoundaries() {
        CancellationChecker checker = CancellationChecker.whenBoth(3, Duration.ofHours(1));

        assertFalse(checker.shouldCheck());
        assertFalse(checker.shouldCheck());
        assertFalse(checker.shouldCheck());
    }

    @Test
    void combinedModeKeepsCountBoundarySatisfiedWhileWaitingForTime() throws Exception {
        CancellationChecker checker = CancellationChecker.whenBoth(3, Duration.ofMillis(20));

        assertFalse(checker.shouldCheck());
        assertFalse(checker.shouldCheck());
        assertFalse(checker.shouldCheck());

        Thread.sleep(30);

        assertTrue(checker.shouldCheck());
    }

    @Test
    void boundCheckRunsOnlyWhenDue() {
        AtomicInteger checks = new AtomicInteger();
        CancellationChecker checker = CancellationChecker.every(2, checks::incrementAndGet);

        assertFalse(checker.check());
        assertTrue(checker.check());
        assertEquals(1, checks.get());
    }

    @Test
    void checkRequiresABoundAction() {
        CancellationChecker checker = CancellationChecker.every(1);

        assertThrows(IllegalStateException.class, checker::check);
    }
}

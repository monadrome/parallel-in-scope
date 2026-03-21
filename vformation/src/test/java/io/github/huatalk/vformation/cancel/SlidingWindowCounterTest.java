package io.github.huatalk.vformation.cancel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlidingWindowCounter}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class SlidingWindowCounterTest {

    @Test
    public void testInitialCountIsZero() {
        SlidingWindowCounter counter = new SlidingWindowCounter(10_000, 10);
        assertEquals(0, counter.getCount());
        assertEquals(0.0, counter.getRatePerSecond(), 0.001);
    }

    @Test
    public void testRecordAndGetCount() {
        SlidingWindowCounter counter = new SlidingWindowCounter(60_000, 60);
        counter.record(5);
        counter.record(3);
        assertEquals(8, counter.getCount());
    }

    @Test
    public void testRatePerSecond() {
        // 60-second window, record 60 events
        SlidingWindowCounter counter = new SlidingWindowCounter(60_000, 60);
        counter.record(60);
        // 60 events / 60 seconds = 1.0 rps
        assertEquals(1.0, counter.getRatePerSecond(), 0.01);
    }

    @Test
    public void testRecordZeroOrNegativeIsIgnored() {
        SlidingWindowCounter counter = new SlidingWindowCounter(10_000, 10);
        counter.record(0);
        counter.record(-1);
        assertEquals(0, counter.getCount());
    }

    @Test
    public void testMultipleRecordsAccumulate() {
        SlidingWindowCounter counter = new SlidingWindowCounter(60_000, 60);
        for (int i = 0; i < 10; i++) {
            counter.record(1);
        }
        assertEquals(10, counter.getCount());
    }

    @Test
    public void testConstructor_invalidArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(-1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(10_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(10_000, -1));
    }

    @Test
    public void testSingleBucketWindow() {
        SlidingWindowCounter counter = new SlidingWindowCounter(10_000, 1);
        counter.record(5);
        assertEquals(5, counter.getCount());
        counter.record(3);
        assertEquals(8, counter.getCount());
    }
}

package io.github.huatalk.parallelinscope.control;

import com.google.common.base.Supplier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClearableLazyTest {

    @Test
    void successfulComputationFixesOneValue() {
        AtomicInteger computations = new AtomicInteger();
        Object expected = new Object();
        Supplier<Object> initializer = () -> {
            computations.incrementAndGet();
            return expected;
        };
        ClearableLazy<Object> lazy = ClearableLazy.from(initializer);

        assertEquals(ClearableLazy.State.NEW, lazy.getState());
        assertSame(expected, lazy.get());
        assertSame(expected, lazy.get());
        assertEquals(1, computations.get());
        assertEquals(ClearableLazy.State.IMMUTABLE, lazy.getState());
    }

    @Test
    void existingValueAlsoFollowsEntireLifecycle() {
        ClearableLazy<String> lazy = ClearableLazy.of("value");

        assertEquals(ClearableLazy.State.NEW, lazy.getState());
        assertEquals("value", lazy.get());
        assertEquals(ClearableLazy.State.IMMUTABLE, lazy.getState());

        lazy.clear();

        assertEquals(ClearableLazy.State.CLEARED, lazy.getState());
        assertThrows(IllegalStateException.class, lazy::get);
    }

    @Test
    void clearBeforeGetPreventsComputationPermanently() {
        AtomicInteger computations = new AtomicInteger();
        ClearableLazy<Integer> lazy = ClearableLazy.from(computations::incrementAndGet);

        lazy.clear();
        lazy.clear();

        assertEquals(ClearableLazy.State.CLEARED, lazy.getState());
        assertThrows(IllegalStateException.class, lazy::get);
        assertEquals(0, computations.get());
    }

    @Test
    void nullIsAValidFixedValue() {
        ClearableLazy<Object> lazy = ClearableLazy.of(null);

        assertNull(lazy.get());
        assertNull(lazy.get());
        assertEquals(ClearableLazy.State.IMMUTABLE, lazy.getState());
    }

    @Test
    void failedComputationRemainsNewAndCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        ClearableLazy<Integer> lazy = ClearableLazy.from(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first attempt failed");
            }
            return 42;
        });

        assertThrows(IllegalStateException.class, lazy::get);
        assertEquals(ClearableLazy.State.NEW, lazy.getState());
        assertEquals(42, lazy.get());
        assertEquals(ClearableLazy.State.IMMUTABLE, lazy.getState());
        assertEquals(2, attempts.get());
    }

    @Test
    void concurrentCallersShareOneComputation() throws Exception {
        int callerCount = 32;
        AtomicInteger computations = new AtomicInteger();
        Object expected = new Object();
        ClearableLazy<Object> lazy = ClearableLazy.from(() -> {
            computations.incrementAndGet();
            return expected;
        });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        try {
            for (int i = 0; i < callerCount; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return lazy.get();
                }));
            }
            start.countDown();

            for (Future<Object> result : results) {
                assertSame(expected, result.get());
            }
            assertEquals(1, computations.get());
            assertEquals(ClearableLazy.State.IMMUTABLE, lazy.getState());
        } finally {
            executor.shutdownNow();
        }
    }
}

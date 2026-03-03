package io.github.linzee1.vformation;

import io.github.linzee1.vformation.cancel.CancellationToken;
import io.github.linzee1.vformation.cancel.Checkpoints;
import io.github.linzee1.vformation.cancel.FatCancellationException;
import io.github.linzee1.vformation.cancel.LeanCancellationException;
import io.github.linzee1.vformation.context.TaskScopeTl;
import io.github.linzee1.vformation.scope.ParOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cooperative cancellation checkpoints.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class CheckpointsTest {

    @AfterEach
    public void cleanup() {
        TaskScopeTl.remove();
    }

    @Test
    public void testCheckpoint_noCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        TaskScopeTl.init(token, options);

        // Should not throw
        Checkpoints.checkpoint("myTask", true);
    }

    @Test
    public void testCheckpoint_leanCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThrows(LeanCancellationException.class, () ->
                Checkpoints.checkpoint("myTask", true));
    }

    @Test
    public void testCheckpoint_fatCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThrows(FatCancellationException.class, () ->
                Checkpoints.checkpoint("myTask", false));
    }

    @Test
    public void testCheckpoint_differentTaskName_noThrow() {
        ParOptions options = ParOptions.of("taskA").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        // Different task name - should not throw
        Checkpoints.checkpoint("taskB", true);
    }

    @Test
    public void testLeanException_noStackTrace() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertEquals(0, ex.getStackTrace().length);
    }

    @Test
    public void testFatException_hasStackTrace() {
        FatCancellationException ex = new FatCancellationException("test");
        assertTrue(ex.getStackTrace().length > 0);
    }

    // ==================== rawCheckpoint / sleep / propagateCancellation tests ====================

    @Test
    public void testRawCheckpoint_interruptedThread_throwsFatCancellationException() {
        Thread.currentThread().interrupt();
        assertThrows(FatCancellationException.class, Checkpoints::rawCheckpoint);
        // interrupt flag should have been consumed by Thread.interrupted()
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    public void testSleep_interrupted_throwsFatCancellationException() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> caught = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                started.countDown();
                Checkpoints.sleep(5000);
            } catch (Throwable ex) {
                caught.set(ex);
            }
        });
        t.start();
        started.await(1, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(50); // small delay to ensure sleep is entered
        t.interrupt();
        t.join(2000);

        assertInstanceOf(FatCancellationException.class, caught.get());
    }

    @Test
    public void testPropagateCancellation_rethrowsFatCancellationException() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThrows(FatCancellationException.class, () -> Checkpoints.propagateCancellation(ex));
    }

    @Test
    public void testPropagateCancellation_rethrowsLeanCancellationException() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertThrows(LeanCancellationException.class, () -> Checkpoints.propagateCancellation(ex));
    }

    @Test
    public void testPropagateCancellation_doesNothingForOtherExceptions() {
        RuntimeException ex = new RuntimeException("not a cancellation");
        // Should not throw
        Checkpoints.propagateCancellation(ex);
    }
}

package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for cooperative cancellation checkpoints.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
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

        assertThatThrownBy(() -> Checkpoints.checkpoint("myTask", true))
                .isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testCheckpoint_fatCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThatThrownBy(() -> Checkpoints.checkpoint("myTask", false))
                .isInstanceOf(FatCancellationException.class);
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
        assertThat(ex.getStackTrace()).isEmpty();
    }

    @Test
    public void testFatException_hasStackTrace() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThat(ex.getStackTrace()).isNotEmpty();
    }

    // ==================== rawCheckpoint / sleep / propagateCancellation tests ====================

    @Test
    public void testRawCheckpoint_interruptedThread_throwsFatCancellationException() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint)
                .isInstanceOf(FatCancellationException.class);
        // interrupt flag should have been consumed by Thread.interrupted()
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
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

        assertThat(caught.get()).isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_rethrowsFatCancellationException() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(ex))
                .isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_rethrowsLeanCancellationException() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(ex))
                .isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_doesNothingForOtherExceptions() {
        RuntimeException ex = new RuntimeException("not a cancellation");
        // Should not throw
        Checkpoints.propagateCancellation(ex);
    }
}

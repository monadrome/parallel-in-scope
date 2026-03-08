package io.github.linzee1.concurrent;

import io.github.linzee1.concurrent.cancel.CancellationToken;
import io.github.linzee1.concurrent.cancel.Checkpoints;
import io.github.linzee1.concurrent.cancel.FatCancellationException;
import io.github.linzee1.concurrent.cancel.LeanCancellationException;
import io.github.linzee1.concurrent.context.TaskScopeTl;
import io.github.linzee1.concurrent.scope.ParallelOptions;
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
        ParallelOptions options = ParallelOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        TaskScopeTl.init(token, options);

        // Should not throw
        Checkpoints.checkpoint("myTask", true);
    }

    @Test
    public void testCheckpoint_leanCancellation() {
        ParallelOptions options = ParallelOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThrows(LeanCancellationException.class, () ->
                Checkpoints.checkpoint("myTask", true));
    }

    @Test
    public void testCheckpoint_fatCancellation() {
        ParallelOptions options = ParallelOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThrows(FatCancellationException.class, () ->
                Checkpoints.checkpoint("myTask", false));
    }

    @Test
    public void testCheckpoint_differentTaskName_noThrow() {
        ParallelOptions options = ParallelOptions.of("taskA").build();
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
}

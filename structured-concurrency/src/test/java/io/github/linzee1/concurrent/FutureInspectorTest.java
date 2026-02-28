package io.github.linzee1.concurrent;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.linzee1.concurrent.internal.FutureInspector;
import io.github.linzee1.concurrent.scope.AsyncBatchResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FutureInspector.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class FutureInspectorTest {

    @Test
    public void testState_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertEquals(FutureInspector.State.SUCCESS, FutureInspector.state(future));
    }

    @Test
    public void testState_cancelled() {
        ListenableFuture<String> future = Futures.immediateCancelledFuture();
        assertEquals(FutureInspector.State.CANCELLED, FutureInspector.state(future));
    }

    @Test
    public void testState_failed() {
        ListenableFuture<String> future = Futures.immediateFailedFuture(new RuntimeException("fail"));
        assertEquals(FutureInspector.State.FAILED, FutureInspector.state(future));
    }

    @Test
    public void testState_running() {
        SettableFuture<String> future = SettableFuture.create();
        assertEquals(FutureInspector.State.RUNNING, FutureInspector.state(future));
    }

    @Test
    public void testExceptionNow_failed() {
        RuntimeException expected = new RuntimeException("fail");
        ListenableFuture<String> future = Futures.immediateFailedFuture(expected);
        Throwable actual = FutureInspector.exceptionNow(future);
        assertSame(expected, actual);
    }

    @Test
    public void testExceptionNow_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertNull(FutureInspector.exceptionNow(future));
    }

    @Test
    public void testReport() {
        List<ListenableFuture<String>> futures = Arrays.asList(
                Futures.immediateFuture("a"),
                Futures.immediateFuture("b"),
                Futures.immediateFailedFuture(new RuntimeException("fail"))
        );
        AsyncBatchResult<String> batch = AsyncBatchResult.simpleBatch(futures);
        java.util.Map.Entry<java.util.Map<FutureInspector.State, Integer>, Throwable> report = batch.report();

        assertEquals(2, (int) report.getKey().get(FutureInspector.State.SUCCESS));
        assertEquals(1, (int) report.getKey().get(FutureInspector.State.FAILED));
        assertNotNull(report.getValue());
        assertEquals("fail", report.getValue().getMessage());
    }
}

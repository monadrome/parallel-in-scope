package io.github.huatalk.vformation;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.huatalk.vformation.internal.FutureInspector;
import io.github.huatalk.vformation.internal.FutureState;
import io.github.huatalk.vformation.scope.AsyncBatchResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Future state inspection via FutureInspector.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class FutureInspectorTest {

    @Test
    public void testState_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertEquals(FutureState.SUCCESS, FutureInspector.state(future));
    }

    @Test
    public void testState_canceled() {
        ListenableFuture<String> future = Futures.immediateCancelledFuture();
        assertEquals(FutureState.CANCELLED, FutureInspector.state(future));
    }

    @Test
    public void testState_failed() {
        ListenableFuture<String> future = Futures.immediateFailedFuture(new RuntimeException("fail"));
        assertEquals(FutureState.FAILED, FutureInspector.state(future));
    }

    @Test
    public void testState_running() {
        SettableFuture<String> future = SettableFuture.create();
        assertEquals(FutureState.RUNNING, FutureInspector.state(future));
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
        assertThrows(IllegalStateException.class, () -> FutureInspector.exceptionNow(future));
    }

    @Test
    public void testReport() {
        List<ListenableFuture<String>> futures = Arrays.asList(
                Futures.immediateFuture("a"),
                Futures.immediateFuture("b"),
                Futures.immediateFailedFuture(new RuntimeException("fail"))
        );
        AsyncBatchResult<String> batch = AsyncBatchResult.of(futures);
        AsyncBatchResult.BatchReport report = batch.report();

        assertEquals(2, (int) report.getStateCounts().get(FutureState.SUCCESS));
        assertEquals(1, (int) report.getStateCounts().get(FutureState.FAILED));
        assertNotNull(report.getFirstException());
        assertEquals("fail", report.getFirstException().getMessage());
    }
}

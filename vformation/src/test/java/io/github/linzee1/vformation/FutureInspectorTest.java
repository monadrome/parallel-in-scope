package io.github.linzee1.vformation;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.foldright.cffu2.CffuState;
import io.foldright.cffu2.CompletableFutureUtils;
import io.github.linzee1.vformation.scope.AsyncBatchResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Future state inspection via cffu2.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class FutureInspectorTest {

    @Test
    public void testState_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertEquals(CffuState.SUCCESS, CompletableFutureUtils.state(future));
    }

    @Test
    public void testState_canceled() {
        ListenableFuture<String> future = Futures.immediateCancelledFuture();
        assertEquals(CffuState.CANCELLED, CompletableFutureUtils.state(future));
    }

    @Test
    public void testState_failed() {
        ListenableFuture<String> future = Futures.immediateFailedFuture(new RuntimeException("fail"));
        assertEquals(CffuState.FAILED, CompletableFutureUtils.state(future));
    }

    @Test
    public void testState_running() {
        SettableFuture<String> future = SettableFuture.create();
        assertEquals(CffuState.RUNNING, CompletableFutureUtils.state(future));
    }

    @Test
    public void testExceptionNow_failed() {
        RuntimeException expected = new RuntimeException("fail");
        ListenableFuture<String> future = Futures.immediateFailedFuture(expected);
        Throwable actual = CompletableFutureUtils.exceptionNow(future);
        assertSame(expected, actual);
    }

    @Test
    public void testExceptionNow_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertThrows(IllegalStateException.class, () -> CompletableFutureUtils.exceptionNow(future));
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

        assertEquals(2, (int) report.getStateCounts().get(CffuState.SUCCESS));
        assertEquals(1, (int) report.getStateCounts().get(CffuState.FAILED));
        assertNotNull(report.getFirstException());
        assertEquals("fail", report.getFirstException().getMessage());
    }
}

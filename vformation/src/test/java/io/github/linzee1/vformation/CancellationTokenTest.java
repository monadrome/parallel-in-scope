package io.github.linzee1.vformation;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.linzee1.vformation.cancel.CancellationToken;
import io.github.linzee1.vformation.cancel.CancellationTokenState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CancellationToken and cooperative cancellation.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class CancellationTokenTest {

    @Test
    public void testInitialState() {
        CancellationToken token = CancellationToken.create();
        assertEquals(CancellationTokenState.RUNNING, token.getState());
    }

    @Test
    public void testManualCancel() {
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        assertEquals(CancellationTokenState.MUTUAL_CANCELED, token.getState());
        assertTrue(token.getState().shouldInterruptCurrentThread());
    }

    @Test
    public void testParentChildChain() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);

        assertEquals(CancellationTokenState.RUNNING, parent.getState());
        assertEquals(CancellationTokenState.RUNNING, child.getState());

        parent.cancel(false);
        assertEquals(CancellationTokenState.MUTUAL_CANCELED, parent.getState());
    }

    @Test
    public void testCancellationTokenState_codes() {
        assertEquals(0, CancellationTokenState.RUNNING.getCode());
        assertEquals(1, CancellationTokenState.SUCCESS.getCode());
        assertFalse(CancellationTokenState.RUNNING.shouldInterruptCurrentThread());
        assertFalse(CancellationTokenState.SUCCESS.shouldInterruptCurrentThread());
        assertTrue(CancellationTokenState.FAIL_FAST_CANCELED.shouldInterruptCurrentThread());
        assertTrue(CancellationTokenState.TIMEOUT_CANCELED.shouldInterruptCurrentThread());
        assertTrue(CancellationTokenState.MUTUAL_CANCELED.shouldInterruptCurrentThread());
        assertTrue(CancellationTokenState.PROPAGATING_CANCELED.shouldInterruptCurrentThread());
    }

    // ==================== lateBind state transition tests ====================

    @Test
    public void testLateBind_success_allFuturesComplete() throws Exception {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> f1 = SettableFuture.create();
        SettableFuture<String> f2 = SettableFuture.create();
        SettableFuture<String> f3 = SettableFuture.create();
        List<ListenableFuture<String>> futures = Arrays.asList(f1, f2, f3);

        token.lateBind(futures, Duration.ofSeconds(5), Futures.immediateVoidFuture());

        f1.set("a");
        f2.set("b");
        f3.set("c");

        // Allow callback propagation
        Thread.sleep(50);
        assertEquals(CancellationTokenState.SUCCESS, token.getState());
    }

    @Test
    public void testLateBind_timeout_stateTransitionsToTimeoutCanceled() throws Exception {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> f1 = SettableFuture.create(); // never completed

        token.lateBind(List.of(f1), Duration.ofMillis(100), Futures.immediateVoidFuture());

        // Wait for timeout to fire
        Thread.sleep(300);
        assertEquals(CancellationTokenState.TIMEOUT_CANCELED, token.getState());
    }

    @Test
    public void testLateBind_failFast_oneFailsOthersCanceled() throws Exception {
        CancellationToken token = CancellationToken.create();

        SettableFuture<String> f1 = SettableFuture.create();
        SettableFuture<String> f2 = SettableFuture.create();
        List<ListenableFuture<String>> futures = Arrays.asList(f1, f2);

        token.lateBind(futures, Duration.ofSeconds(5), Futures.immediateVoidFuture());

        f1.setException(new RuntimeException("boom"));

        // Allow callback propagation
        Thread.sleep(50);
        assertEquals(CancellationTokenState.FAIL_FAST_CANCELED, token.getState());
    }

    @Test
    public void testLateBind_parentCanceled_childPropagates() throws Exception {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = new CancellationToken(parent);

        SettableFuture<String> f1 = SettableFuture.create();

        child.lateBind(List.of(f1), Duration.ofSeconds(5), Futures.immediateVoidFuture());

        parent.cancel(true);

        // Allow callback propagation
        Thread.sleep(50);
        assertEquals(CancellationTokenState.PROPAGATING_CANCELED, child.getState());
    }

    @Test
    public void testLateBind_parentAlreadyCanceled_childImmediatelyCanceled() {
        CancellationToken parent = CancellationToken.create();
        parent.cancel(true);
        assertTrue(parent.getState().shouldInterruptCurrentThread());

        CancellationToken child = new CancellationToken(parent);

        SettableFuture<String> f1 = SettableFuture.create();
        child.lateBind(List.of(f1), Duration.ofSeconds(5), Futures.immediateVoidFuture());

        // The future should be cancelled immediately because parent is already canceled
        assertTrue(f1.isCancelled());
    }
}

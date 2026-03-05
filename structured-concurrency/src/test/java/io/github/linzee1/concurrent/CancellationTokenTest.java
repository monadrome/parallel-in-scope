package io.github.linzee1.concurrent;

import io.github.linzee1.concurrent.cancel.CancellationToken;
import io.github.linzee1.concurrent.cancel.CancellationTokenState;
import org.junit.jupiter.api.Test;

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
}

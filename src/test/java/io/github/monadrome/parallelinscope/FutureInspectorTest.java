package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.*;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.monadrome.parallelinscope.queue.*;
import org.junit.jupiter.api.Test;

/**
 * Tests for failed-future inspection via FutureInspector.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class FutureInspectorTest {

    @Test
    public void testExceptionNow_failed() {
        RuntimeException expected = new RuntimeException("fail");
        ListenableFuture<String> future = Futures.immediateFailedFuture(expected);
        Throwable actual = FutureInspector.exceptionNow(future);
        assertThat(actual).isSameAs(expected);
    }

    @Test
    public void testExceptionNow_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertThatThrownBy(() -> FutureInspector.exceptionNow(future)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testExceptionNow_pendingAndCancelledAreRejected() {
        SettableFuture<String> pending = SettableFuture.create();
        assertThatThrownBy(() -> FutureInspector.exceptionNow(pending))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not completed");

        ListenableFuture<String> cancelled = Futures.immediateCancelledFuture();
        assertThatThrownBy(() -> FutureInspector.exceptionNow(cancelled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    public void interruptedFutureInspectionReturnsTheOriginalFailure() {
        IllegalArgumentException failure = new IllegalArgumentException("business failure");
        SettableFuture<Object> future = SettableFuture.create();
        future.setException(failure);
        try {
            Thread.currentThread().interrupt();
            assertThat(FutureInspector.exceptionNow(future)).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

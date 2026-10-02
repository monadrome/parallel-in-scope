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
    public void exceptionNowReadsADoneFailedFutureWhileTheCallingThreadIsInterrupted() {
        RuntimeException expected = new RuntimeException("fail");
        ListenableFuture<String> future = Futures.immediateFailedFuture(expected);

        // Guava's AbstractFuture.get() checks the interrupt flag before it looks at the value, so a
        // read that goes through get() reports InterruptedException for a future that has been done
        // all along; this one must not.
        Thread.currentThread().interrupt();
        try {
            assertThat(FutureInspector.exceptionNow(future)).isSameAs(expected);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void exceptionNowRejectsASuccessfulFutureWhileTheCallingThreadIsInterrupted() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> FutureInspector.exceptionNow(future))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("result");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

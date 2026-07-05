package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.*;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.monadrome.parallelinscope.queue.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    public void testExceptionNow_pendingAndCanceledAreRejected() {
        SettableFuture<String> pending = SettableFuture.create();
        assertThatThrownBy(() -> FutureInspector.exceptionNow(pending))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not completed");

        ListenableFuture<String> canceled = Futures.immediateCancelledFuture();
        assertThatThrownBy(() -> FutureInspector.exceptionNow(canceled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canceled");
    }

    @Test
    public void interruptedFutureInspectionRestoresInterruptStatus() {
        Future<Object> interrupted = new Future<Object>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Object get() throws InterruptedException {
                throw new InterruptedException("test");
            }

            @Override
            public Object get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("test");
            }
        };
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> FutureInspector.exceptionNow(interrupted))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

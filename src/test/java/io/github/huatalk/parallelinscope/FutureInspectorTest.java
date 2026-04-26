package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Future state inspection via FutureInspector.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class FutureInspectorTest {

    @Test
    public void testState_success() {
        ListenableFuture<String> future = Futures.immediateFuture("ok");
        assertThat(FutureInspector.state(future)).isEqualTo(FutureState.SUCCESS);
    }

    @Test
    public void testState_canceled() {
        ListenableFuture<String> future = Futures.immediateCancelledFuture();
        assertThat(FutureInspector.state(future)).isEqualTo(FutureState.CANCELLED);
    }

    @Test
    public void testState_failed() {
        ListenableFuture<String> future = Futures.immediateFailedFuture(new RuntimeException("fail"));
        assertThat(FutureInspector.state(future)).isEqualTo(FutureState.FAILED);
    }

    @Test
    public void testState_running() {
        SettableFuture<String> future = SettableFuture.create();
        assertThat(FutureInspector.state(future)).isEqualTo(FutureState.RUNNING);
    }

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
        assertThatThrownBy(() -> FutureInspector.exceptionNow(future))
                .isInstanceOf(IllegalStateException.class);
    }

}

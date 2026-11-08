package io.github.monadrome.parallelinscope;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.util.concurrent.Futures;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FutureInspectorGoldenTest {

    @Test
    void failedDoneFutureCanBeReadWithAnInterruptFlag() {
        IllegalStateException cause = new IllegalStateException("failure");
        Future<?> failed = Futures.immediateFailedFuture(cause);

        Thread.currentThread().interrupt();
        try {
            assertSame(cause, FutureInspector.exceptionNow(failed));
            assertThrows(IllegalStateException.class, () -> FutureInspector.exceptionNow(Futures.immediateFuture("ok")));
            assert Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }
    }
}

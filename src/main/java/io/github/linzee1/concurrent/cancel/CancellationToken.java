package io.github.linzee1.concurrent.cancel;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.linzee1.concurrent.scope.StructuredParallel;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.linzee1.concurrent.cancel.CancellationTokenState.FAIL_FAST_CANCELLED;
import static io.github.linzee1.concurrent.cancel.CancellationTokenState.MUTUAL_CANCELLED;
import static io.github.linzee1.concurrent.cancel.CancellationTokenState.PROPAGATING_CANCELLED;
import static io.github.linzee1.concurrent.cancel.CancellationTokenState.RUNNING;
import static io.github.linzee1.concurrent.cancel.CancellationTokenState.SUCCESS;
import static io.github.linzee1.concurrent.cancel.CancellationTokenState.TIMEOUT_CANCELLED;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * Cooperative cancellation token for parallel task groups.
 * <p>
 * Tracks task state via {@link CancellationTokenState} using {@link AtomicReference}.
 * Supports parent-child token chains for cascading cancellation.
 * Uses Guava's {@link SettableFuture} for late binding -- after all sub-futures are submitted,
 * {@link #lateBind} wires up timeout, fail-fast, and parent cancellation propagation.
 *
 * @author linqh
 */
public class CancellationToken {

    private final SettableFuture<Object> futureToken = SettableFuture.create();
    private final AtomicReference<CancellationTokenState> state = new AtomicReference<>(RUNNING);
    private final CancellationToken parent;

    public CancellationToken(CancellationToken parent) {
        this.parent = parent;
    }

    public CancellationToken() {
        this.parent = null;
    }

    public static CancellationToken create() {
        return new CancellationToken();
    }

    /**
     * Late binds the token to actual futures after submission is complete.
     * Wires up: parent cancellation propagation, fail-fast (allAsList), and timeout.
     *
     * @param futures         the list of task futures
     * @param timeout         timeout duration
     * @param submitCanceller future that cancels remaining submissions
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> void lateBind(List<ListenableFuture<T>> futures, Duration timeout, ListenableFuture<?> submitCanceller) {
        if (parent != null) {
            if (parent.getState().shouldInterruptCurrentThread()) {
                futureToken.cancel(true);
            } else {
                Futures.catching(parent.futureToken, Throwable.class, ex -> {
                    state.compareAndSet(RUNNING, PROPAGATING_CANCELLED);
                    futureToken.cancel(true);
                    return null;
                }, directExecutor());
            }
        }

        FluentFuture<?> failFastFuture = FluentFuture.from(Futures.allAsList(futures))
                .catchingAsync(Throwable.class, ex -> Futures.immediateCancelledFuture(), directExecutor())
                .withTimeout(timeout, StructuredParallel.getTimer());

        ListenableFuture<?> allFutures = Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller);

        failFastFuture.addCallback(new FutureCallback() {
            @Override
            public void onSuccess(Object result) {
                state.compareAndSet(RUNNING, SUCCESS);
            }

            @Override
            public void onFailure(Throwable t) {
                allFutures.cancel(true);
                if (t instanceof TimeoutException) {
                    state.compareAndSet(RUNNING, TIMEOUT_CANCELLED);
                } else {
                    state.compareAndSet(RUNNING, FAIL_FAST_CANCELLED);
                }
            }
        }, directExecutor());

        futureToken.setFuture(failFastFuture);
    }

    /**
     * Manually cancels this token and all linked futures.
     *
     * @param useInterrupt whether to interrupt running threads
     */
    public void cancel(boolean useInterrupt) {
        state.compareAndSet(RUNNING, MUTUAL_CANCELLED);
        futureToken.cancel(useInterrupt);
    }

    /**
     * Gets the current cancellation state.
     */
    public CancellationTokenState getState() {
        return state.get();
    }
}

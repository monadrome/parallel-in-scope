package io.github.huatalk.parallelinscope.cancel;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.github.huatalk.parallelinscope.cancel.CancellationToken.State.FAIL_FAST_CANCELED;
import static io.github.huatalk.parallelinscope.cancel.CancellationToken.State.MUTUAL_CANCELED;
import static io.github.huatalk.parallelinscope.cancel.CancellationToken.State.PROPAGATING_CANCELED;
import static io.github.huatalk.parallelinscope.cancel.CancellationToken.State.RUNNING;
import static io.github.huatalk.parallelinscope.cancel.CancellationToken.State.SUCCESS;
import static io.github.huatalk.parallelinscope.cancel.CancellationToken.State.TIMEOUT_CANCELED;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Cooperative cancellation token for parallel task groups.
 *
 * <p>A token may be linked to a parent so that cancellation propagates to child task groups. After
 * task submission, {@link #lateBind(List, Duration, ListenableFuture, ScheduledExecutorService)}
 * connects the token to the submitted futures and enables timeout and fail-fast cancellation.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class CancellationToken {

    private final SettableFuture<Object> futureToken = SettableFuture.create();
    private final AtomicReference<State> state = new AtomicReference<>(RUNNING);
    private final CancellationToken parent;

    /**
     * Creates a token linked to a parent, or a root token if {@code parent} is {@code null}.
     *
     * @param parent the parent token, or {@code null} for a root token
     */
    public CancellationToken(@Nullable CancellationToken parent) {
        this.parent = parent;
    }

    /** Creates an unlinked root token. */
    public CancellationToken() {
        this.parent = null;
    }

    /**
     * Creates an unlinked root token.
     *
     * @return a new cancellation token
     */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    /**
     * Connects this token to submitted work using the supplied timeout scheduler.
     *
     * @param <T> the task result type
     * @param futures the submitted task futures
     * @param timeout the maximum execution time
     * @param submitCanceller the submission future to cancel with the tasks
     * @param timer scheduler used to detect the timeout
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> void lateBind(
            List<ListenableFuture<T>> futures,
            Duration timeout,
            ListenableFuture<?> submitCanceller,
            ScheduledExecutorService timer) {
        Objects.requireNonNull(timer);
        if (parent != null) {
            if (parent.getState().shouldInterruptCurrentThread()) {
                state.compareAndSet(RUNNING, PROPAGATING_CANCELED);
                futureToken.cancel(true);
            } else {
                Futures.catching(
                        parent.futureToken,
                        Throwable.class,
                        ex -> {
                            state.compareAndSet(RUNNING, PROPAGATING_CANCELED);
                            futureToken.cancel(true);
                            return null;
                        },
                        directExecutor());
            }
        }

        FluentFuture<?> failFastFuture = FluentFuture.from(Futures.allAsList(futures))
                .catchingAsync(Throwable.class, ex -> Futures.immediateCancelledFuture(), directExecutor())
                .withTimeout(timeout, timer);

        ListenableFuture<?> allFutures = Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller);

        failFastFuture.addCallback(
                new FutureCallback() {
                    @Override
                    public void onSuccess(Object result) {
                        state.compareAndSet(RUNNING, SUCCESS);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        allFutures.cancel(true);
                        if (t instanceof TimeoutException) {
                            state.compareAndSet(RUNNING, TIMEOUT_CANCELED);
                        } else {
                            state.compareAndSet(RUNNING, FAIL_FAST_CANCELED);
                        }
                    }
                },
                directExecutor());

        futureToken.setFuture(failFastFuture);
    }

    /**
     * Cancels this token and its linked work, interrupting running threads.
     *
     * <p>This is the common case; it is equivalent to {@link #cancel(boolean) cancel(true)}. Use
     * {@link #cancel(boolean)} with {@code false} only when running tasks must be allowed to
     * complete without interruption.
     */
    public void cancel() {
        cancel(true);
    }

    /**
     * Cancels this token and its linked work.
     *
     * @param useInterrupt whether to interrupt running threads
     */
    public void cancel(boolean useInterrupt) {
        state.compareAndSet(RUNNING, MUTUAL_CANCELED);
        futureToken.cancel(useInterrupt);
    }

    /**
     * Returns the current state.
     *
     * @return the current state
     */
    public State getState() {
        return state.get();
    }

    /** Lifecycle state of a {@link CancellationToken}. */
    public enum State {

        /** The task is running. */
        RUNNING(0),
        /** The task completed successfully. */
        SUCCESS(1),
        /** No task was run. */
        NO_OP(2),
        /** A sibling task failed, triggering fail-fast cancellation. */
        FAIL_FAST_CANCELED(-1),
        /** The task timed out. */
        TIMEOUT_CANCELED(-2),
        /** The token was explicitly canceled. */
        MUTUAL_CANCELED(-3),
        /** The parent token was canceled. */
        PROPAGATING_CANCELED(-4);

        private final int code;

        State(int code) {
            this.code = code;
        }

        /** Returns the state code. */
        public int getCode() {
            return code;
        }

        /** Returns whether this state requires interruption. */
        boolean shouldInterruptCurrentThread() {
            return code < 0;
        }
    }
}

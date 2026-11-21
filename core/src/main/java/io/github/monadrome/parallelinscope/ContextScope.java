package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkState;

import org.jspecify.annotations.Nullable;

/**
 * One application-context installation, closed on its owner thread in reverse installation order.
 *
 * <p>Closing out of order or on another thread fails before changing bindings. A rejected close
 * can be retried correctly. An attempted restoration consumes the scope even if it throws;
 * subsequent closes on the owner thread do nothing. This class does not manipulate thread
 * interruption, cancellation, or executor ownership.
 */
public final class ContextScope implements AutoCloseable {
    private static final ThreadLocal<@Nullable ContextScope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<@Nullable Boolean> IN_CALLBACK = new ThreadLocal<>();

    private final Thread owner = Thread.currentThread();
    private @Nullable ContextScope previous;
    private @Nullable Runnable restoration;

    /** Creates a frame after provider installation succeeds. */
    private ContextScope(@Nullable ContextScope previous, Runnable restoration) {
        this.previous = previous;
        this.restoration = restoration;
    }

    /** Installs provider bindings without allowing callbacks to reenter the shared scope stack. */
    static ContextScope install(ContextSnapshot snapshot) {
        checkNotInCallback();
        IN_CALLBACK.set(true);
        Runnable restoration;
        try {
            restoration = snapshot.installBindings();
        } finally {
            IN_CALLBACK.remove();
        }
        ContextScope scope = new ContextScope(CURRENT.get(), restoration);
        CURRENT.set(scope);
        return scope;
    }

    /** Rejects protocol installation or closure from a provider callback before side effects. */
    private static void checkNotInCallback() {
        checkState(IN_CALLBACK.get() == null, "context callbacks must not reenter the scope protocol");
    }

    /**
     * Restores the entering bindings and releases the frame even when restoration fails.
     *
     * @throws IllegalStateException for wrong-thread, out-of-order, or callback-reentrant closure
     */
    @Override
    public void close() {
        checkState(Thread.currentThread() == owner, "context scope must close on its installing thread");
        checkNotInCallback();
        Runnable action = restoration;
        if (action == null) {
            return;
        }
        checkState(CURRENT.get() == this, "context scopes must close in reverse installation order");
        restoration = null;
        IN_CALLBACK.set(true);
        try {
            action.run();
        } finally {
            IN_CALLBACK.remove();
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            previous = null;
        }
    }
}

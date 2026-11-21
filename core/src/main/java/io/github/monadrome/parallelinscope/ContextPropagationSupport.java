package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.alibaba.ttl.TransmittableThreadLocal.Transmitter;
import com.alibaba.ttl.TtlCallable;
import com.alibaba.ttl.spi.TtlEnhanced;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/** Provider composition and task decoration, separate from framework execution and submission scopes. */
final class ContextPropagationSupport {
    static final ContextPropagation TTL = () -> {
        Object captured = Transmitter.capture();
        return ContextSnapshot.of(() -> {
            Object backup = Transmitter.replay(captured);
            return () -> Transmitter.restore(backup);
        });
    };
    private static final ContextSnapshot EMPTY_SNAPSHOT = ContextSnapshot.of(() -> () -> {});
    static final ContextPropagation NONE = () -> EMPTY_SNAPSHOT;

    /** Prevents construction of the shared protocol helpers. */
    private ContextPropagationSupport() {}

    /** Validates a provider's capture at the application-provider trust boundary. */
    static ContextSnapshot capture(ContextPropagation propagation) {
        return checkNotNull(propagation.capture(), "provider must return a snapshot");
    }

    /** Validates identity-distinct providers and copies their configured order. */
    static ContextPropagation compose(ContextPropagation[] providers) {
        checkNotNull(providers, "providers cannot be null");
        List<ContextPropagation> components = new ArrayList<>();
        Set<ContextPropagation> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ContextPropagation provider : providers) {
            checkNotNull(provider, "provider cannot be null");
            checkArgument(identities.add(provider), "provider instance occurs more than once");
            components.add(provider);
        }
        return components.isEmpty() ? NONE : new Composite(components);
    }

    /** Preserves the released TTL callable adapter for the default path and scopes custom captures. */
    static <V> Callable<V> wrap(ContextPropagation propagation, Callable<V> callable) {
        if (propagation == TTL) {
            return TtlCallable.get(callable, true, true);
        }
        ContextSnapshot captured = capture(propagation);
        return usesTtl(propagation)
                ? new TtlContextualCallable<>(captured, callable)
                : new ContextualCallable<>(captured, callable);
    }

    /** Whether the known provider composition includes the built-in TTL adapter. */
    private static boolean usesTtl(ContextPropagation propagation) {
        if (propagation == TTL) {
            return true;
        }
        if (propagation instanceof Composite) {
            for (ContextPropagation component : ((Composite) propagation).components) {
                if (usesTtl(component)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Captures all components before any installation and preserves their declared order. */
    private static final class Composite implements ContextPropagation {
        private final List<ContextPropagation> components;

        /** Owns the private provider list, which is never exposed or mutated. */
        private Composite(List<ContextPropagation> components) {
            this.components = components;
        }

        /** Captures independent reusable snapshots without installing application bindings. */
        @Override
        public ContextSnapshot capture() {
            List<ContextSnapshot> captures = new ArrayList<>();
            for (ContextPropagation component : components) {
                captures.add(ContextPropagationSupport.capture(component));
            }
            return ContextSnapshot.of(() -> installAll(captures));
        }
    }

    /** Installs raw component bindings under one guarded frame and rolls back partial installation. */
    private static Runnable installAll(List<ContextSnapshot> captures) {
        List<Runnable> restorations = new ArrayList<>();
        try {
            for (ContextSnapshot capture : captures) {
                restorations.add(capture.installBindings());
            }
        } catch (Throwable failure) {
            restoreAll(restorations, failure);
            throw failure;
        }
        return () -> restoreAll(restorations, null);
    }

    /**
     * Attempts every reverse-order unwind. With a primary installation failure, attaches cleanup
     * failures for the caller to rethrow; otherwise throws the first cleanup failure after unwinding.
     */
    private static void restoreAll(List<Runnable> restorations, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (int index = restorations.size() - 1; index >= 0; index--) {
            try {
                restorations.get(index).run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    suppress(failure, cleanupFailure);
                }
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
        }
    }

    /** Runs one callback under a snapshot while preserving a body failure over cleanup failure. */
    static void run(ContextSnapshot snapshot, Runnable command) {
        ContextScope scope = snapshot.install();
        Throwable primary = null;
        try {
            command.run();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            close(scope, primary);
        }
    }

    /** Restores a scope without replacing the body's failure or suppressing a throwable onto itself. */
    private static void close(ContextScope scope, @Nullable Throwable primary) {
        try {
            scope.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null) {
                throw cleanupFailure;
            }
            suppress(primary, cleanupFailure);
        }
    }

    /** Attaches a cleanup failure without replacing the primary or attempting self-suppression. */
    private static void suppress(Throwable primary, Throwable cleanupFailure) {
        if (primary != cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    /** One prepared execution retaining its snapshot only through the outer future's callable. */
    private static class ContextualCallable<V> implements Callable<V> {
        private final ContextSnapshot snapshot;
        private final Callable<V> callable;

        /** Binds an already captured snapshot to one prepared scoped callable. */
        private ContextualCallable(ContextSnapshot snapshot, Callable<V> callable) {
            this.snapshot = snapshot;
            this.callable = callable;
        }

        /** Restores the execution thread before the outer future publishes execution exit. */
        @Override
        public V call() throws Exception {
            ContextScope scope = snapshot.install();
            Throwable primary = null;
            try {
                return callable.call();
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                close(scope, primary);
            }
        }
    }

    /** Retains TTL enhancement recognition at the callable boundary for explicit TTL compositions. */
    private static final class TtlContextualCallable<V> extends ContextualCallable<V> implements TtlEnhanced {
        /** Binds the composition without wrapping or replacing the prepared executor command. */
        private TtlContextualCallable(ContextSnapshot snapshot, Callable<V> callable) {
            super(snapshot, callable);
        }
    }
}

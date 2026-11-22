package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.function.Supplier;

/**
 * Reusable application-context capture whose installations have independent restoration state.
 *
 * <p>A snapshot may cross threads when safely published and may be installed concurrently.
 * Each installation belongs only to the installing thread. Holding a snapshot retains its
 * captured values; snapshots do not own closeable resources.
 */
public final class ContextSnapshot {
    private final Supplier<? extends Runnable> installer;

    /** Creates a snapshot around a validated installation factory. */
    private ContextSnapshot(Supplier<? extends Runnable> installer) {
        this.installer = installer;
    }

    /**
     * Creates a reusable snapshot around a provider's captured data.
     *
     * <p>Each installer invocation must return a non-null action restoring that invocation's
     * entering bindings. If installation throws, it must first undo its partial changes. The
     * installer must support concurrent calls with independent backups. Installation and
     * restoration must not reenter the protocol. Both callbacks must be nonblocking and preserve
     * the interrupt flag. Neither callback may expose captured values in diagnostics.
     *
     * @param installer factory closing over the provider's captured data
     * @return a reusable snapshot; the installer is not invoked until installation
     * @throws NullPointerException if the installer is null
     */
    public static ContextSnapshot of(Supplier<? extends Runnable> installer) {
        return new ContextSnapshot(checkNotNull(installer, "installer cannot be null"));
    }

    /**
     * Installs this snapshot and returns its thread-bound, LIFO restoration scope.
     *
     * @return a scope to close on this thread, normally with try-with-resources
     * @throws IllegalStateException if called from a protocol installation or restoration callback
     */
    public ContextScope install() {
        return ContextScope.install(this);
    }

    /** Invokes the raw provider factory; only guarded installation and composition use this path. */
    Runnable installBindings() {
        return checkNotNull(installer.get(), "installer must return a restoration action");
    }
}

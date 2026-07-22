package io.github.huatalk.parallelinscope.scope;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global access point for the optional shared {@link ParConfig}.
 * <p>
 * Explicit configuration is preferred: construct {@link Par} with a
 * {@link ParConfig} supplied by the application's dependency-injection
 * container. This class exists as a convenience for applications that use the
 * shared {@link Par#getInstance()} entry point.
 * <p>
 * The global configuration is initialized at most once. Applications should
 * call {@link #initializeDefault(ParConfig)} during bootstrap, before any
 * code calls {@link #get()}. If no explicit configuration is installed, the
 * first call to {@code get()} freezes the built-in configuration.
 */
public final class GlobalParConfig {

    private static final ParConfig BUILT_IN_DEFAULT = ParConfig.builder().build();
    private static final AtomicReference<ParConfig> DEFAULT = new AtomicReference<>();

    private GlobalParConfig() {
    }

    /**
     * Returns the frozen global configuration.
     * <p>
     * The first read installs the built-in configuration if bootstrap did not
     * call {@link #initializeDefault(ParConfig)} first.
     *
     * @return the global configuration
     */
    public static ParConfig get() {
        ParConfig current = DEFAULT.get();
        if (current != null) {
            return current;
        }
        if (DEFAULT.compareAndSet(null, BUILT_IN_DEFAULT)) {
            return BUILT_IN_DEFAULT;
        }
        return DEFAULT.get();
    }

    /**
     * Installs the global configuration during application bootstrap.
     * <p>
     * The operation is linearizable: exactly one concurrent initializer can
     * succeed. A read of the global configuration also counts as
     * initialization, so this method must run before calls to {@link #get()},
     * {@link Par#getInstance()}, or other compatibility entry points.
     *
     * @param config the configuration to freeze globally
     * @throws NullPointerException if {@code config} is null
     * @throws IllegalStateException if the global configuration was initialized
     */
    public static void initializeDefault(ParConfig config) {
        Objects.requireNonNull(config, "config");
        if (!DEFAULT.compareAndSet(null, config)) {
            throw new IllegalStateException("Global ParConfig is already initialized");
        }
    }

    static void resetForTest() {
        DEFAULT.set(null);
    }
}

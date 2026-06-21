package io.github.monadrome.parallelinscope;

/**
 * Immutable deadlock policy owned by one ParRuntime.
 *
 * <p>The policy only decides whether closing a {@link TaskGraphObservationScope} runs the
 * detection pass over the recorded graph; the result is published through {@link
 * TaskGraphObservationScope#reportFuture()} as a {@link TaskGraphReport}. Disabling detection
 * neither stops graph recording nor the scope's static queries — it only skips the close-time
 * detection and edge rendering.
 */
public final class ParRuntimeDeadlockPolicy {
    private final boolean enabled;

    private ParRuntimeDeadlockPolicy(Builder builder) {
        this.enabled = builder.enabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether closing an observation scope runs the detection pass.
     *
     * @return {@code true} when close-time detection is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    public static final class Builder {
        private boolean enabled;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public ParRuntimeDeadlockPolicy build() {
            return new ParRuntimeDeadlockPolicy(this);
        }
    }
}

package io.github.monadrome.parallelinscope;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Immutable deadlock policy owned by one ParRuntime. */
public final class ParRuntimeDeadlockPolicy {
    private final boolean enabled;
    private final List<DeadlockDetectionListener> listeners;

    private ParRuntimeDeadlockPolicy(Builder builder) {
        this.enabled = builder.enabled;
        Set<DeadlockDetectionListener> seen = Sets.newIdentityHashSet();
        List<DeadlockDetectionListener> unique = new ArrayList<>();
        for (DeadlockDetectionListener listener : builder.listeners) {
            if (seen.add(listener)) unique.add(listener);
        }
        this.listeners = ImmutableList.copyOf(unique);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean enabled() {
        return enabled;
    }

    public List<DeadlockDetectionListener> listeners() {
        return listeners;
    }

    public static final class Builder {
        private boolean enabled;
        private final List<DeadlockDetectionListener> listeners = new ArrayList<>();

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder listener(DeadlockDetectionListener listener) {
            listeners.add(java.util.Objects.requireNonNull(listener));
            return this;
        }

        public ParRuntimeDeadlockPolicy build() {
            return new ParRuntimeDeadlockPolicy(this);
        }
    }
}

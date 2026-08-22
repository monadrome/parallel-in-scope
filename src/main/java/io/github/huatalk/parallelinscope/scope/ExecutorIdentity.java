package io.github.huatalk.parallelinscope.scope;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Identity key for one exact supplied executor object. */
public final class ExecutorIdentity {
    private final ExecutorService supplied;
    private final int hash;

    public ExecutorIdentity(ExecutorService supplied) {
        this.supplied = Objects.requireNonNull(supplied, "supplied executor cannot be null");
        this.hash = System.identityHashCode(supplied);
    }

    public ExecutorService suppliedExecutor() {
        return supplied;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExecutorIdentity
                && ((ExecutorIdentity) other).supplied == supplied;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return supplied.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(supplied));
    }
}

package io.github.monadrome.parallelinscope;

import java.util.Objects;

/**
 * Identity of one {@link Par} entry registered on a {@link ParRuntime}.
 *
 * <p>A {@code ParId} is a composition-root lookup key and diagnostic label, not a resource
 * identity. It names a logical execution entry; the physical pool is identified by {@link
 * ExecutorIdentity} through object reference. Within one {@code ParRuntime} an id resolves to
 * exactly one entry, and two differently named entries may intentionally share the same physical
 * executor. Obtaining a {@code Par} always goes through this id: {@link ParRuntime#par(ParId)},
 * {@link ParRuntime#find(ParId)}, and the registration endpoints on {@link ParRuntime.Builder}.
 *
 * <p>Instances are immutable value objects compared by {@link #value()}. Construction is the single
 * validation boundary: an id is never null and never blank, so {@link ParRuntime} and {@link
 * TaskGroupDefinition} no longer repeat that check. The value is used verbatim — no trimming,
 * lower-casing, or other normalization — so {@code of(" db ")} and {@code of("db")} are different
 * ids and lookup semantics do not change.
 *
 * <p>A {@code ParId} proves only that the argument is a well-formed id; it cannot prove the id is
 * registered. {@code of("htpp")} is a valid value that fails at {@link ParRuntime.Builder#build()}
 * or {@link ParRuntime#par(ParId)}. It must never participate in deadlock or purge decisions, which
 * are keyed on {@link ExecutorIdentity}.
 */
public final class ParId {
    private final String value;

    private ParId(String value) {
        this.value = value;
    }

    /**
     * Creates an id from a non-blank string.
     *
     * @param value the exact id to use as a lookup key
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static ParId of(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException("Par id cannot be blank");
        return new ParId(value);
    }

    /** The exact name supplied at construction, without normalization. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ParId && value.equals(((ParId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Diagnostics only; must not be persisted or used as a wire format. */
    @Override
    public String toString() {
        return value;
    }
}

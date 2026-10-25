package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * Identity of one {@link Par} entry registered on a {@link ParRuntime}.
 *
 * <p>A {@code ParId} is a composition-root lookup key and diagnostic label, not a resource
 * identity. It names a logical execution entry; executor resource identity uses the supplied
 * executor object's reference identity. Within one {@code ParRuntime} an id resolves to exactly one
 * entry, and two differently named entries may intentionally share the same physical executor.
 * Obtaining a {@code Par} always goes through this id: {@link ParRuntime#par(ParId)}, {@link
 * ParRuntime#find(ParId)}, and the registration endpoints on {@link ParRuntime.Builder}.
 *
 * <p>Instances are immutable value objects compared by {@link #value()}. An id's value is never
 * null or blank. The value is used verbatim — no trimming, lower-casing, or other normalization —
 * so {@code of(" db ")} and {@code of("db")} are different ids and lookup semantics do not change.
 *
 * <p>A {@code ParId} proves only that the argument is a well-formed id; it cannot prove the id is
 * registered. {@code of("htpp")} is a valid value that fails at {@link ParRuntime.Builder#build()}
 * or {@link ParRuntime#par(ParId)}. It must never participate in deadlock decisions, which are
 * keyed on executor object identity.
 *
 * @since 0.3.0
 */
@Immutable
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
        return new ParId(Validation.requireName(value, "Par id"));
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

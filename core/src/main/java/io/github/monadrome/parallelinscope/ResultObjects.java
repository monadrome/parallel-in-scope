package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Identity rendering of user objects inside diagnostic strings.
 *
 * <p>A user object's own {@code toString} may be expensive, buggy, or leak data into logs, so a
 * diagnostic rendering never calls it. The object appears as its concrete class and identity
 * hash, the shape Guava's {@code AbstractFuture.appendResultObject} uses for the inputs and
 * functions of a pending future.
 */
final class ResultObjects {

    private ResultObjects() {}

    /**
     * Renders {@code value} without calling its {@code toString}: "null" when null, "this" when
     * it is {@code owner} itself, and {@code SimpleClassName@identityHash} otherwise.
     */
    static String render(@Nullable Object value, Object owner) {
        if (value == null) {
            return "null";
        }
        if (value == owner) {
            return "this";
        }
        return value.getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(value));
    }
}

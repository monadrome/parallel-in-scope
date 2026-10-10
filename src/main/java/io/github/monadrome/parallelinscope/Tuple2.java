package io.github.monadrome.parallelinscope;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable pair of two nullable values.
 *
 * <p>{@code Tuple2} is the static result shape of a task group: the {@code V} of a submitted {@link
 * TaskGroup} is the first member's type for a one-member group, and left-nested pairs for larger
 * groups — {@code Tuple2<T1, T2>} for two members, {@code Tuple2<Tuple2<T1, T2>, T3>} for three, and
 * so on. The nesting is produced by the step builder, so callers normally never write {@link #of};
 * they read {@link #first()} and {@link #second()} off {@link GroupValues#typedValues()} or off the
 * value handed to a terminal {@link CombineBody}.
 *
 * <p>Both components may be null: a member body that returns null is a successful member, and its
 * slot holds null in the same way it would hold any other value.
 *
 * @param <A> the first component type
 * @param <B> the second component type
 */
public final class Tuple2<A, B> {
    private final @Nullable A first;
    private final @Nullable B second;

    private Tuple2(@Nullable A first, @Nullable B second) {
        this.first = first;
        this.second = second;
    }

    /**
     * Creates a pair from the two components, either of which may be null.
     *
     * @param first the first component, possibly null
     * @param second the second component, possibly null
     */
    public static <A, B> Tuple2<A, B> of(@Nullable A first, @Nullable B second) {
        return new Tuple2<>(first, second);
    }

    /** The first component, possibly null. */
    public @Nullable A first() {
        return first;
    }

    /** The second component, possibly null. */
    public @Nullable B second() {
        return second;
    }

    /** Value equality over both components; null components compare as equal to null. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Tuple2)) {
            return false;
        }
        Tuple2<?, ?> that = (Tuple2<?, ?>) other;
        return Objects.equals(first, that.first) && Objects.equals(second, that.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }

    @Override
    public String toString() {
        return "Tuple2[" + first + ", " + second + "]";
    }
}

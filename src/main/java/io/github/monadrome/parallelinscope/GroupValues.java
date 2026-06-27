package io.github.monadrome.parallelinscope;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Read-only snapshot of one group run's successful member values, published by {@link
 * TaskGroup#valuesFuture()} and handed to a terminal {@link CombineBody}.
 *
 * <p>Slots are addressed two ways, and both views read the same slot: by zero-based declaration
 * position ({@link #valueAt(int)}, {@link #typeAt(int)}) and by the member name declared in the
 * fluent chain ({@link #valueOf(String)}, {@link #typeOf(String)}). The order is the order the
 * {@code par} calls were written, never the order tasks completed in.
 *
 * <p>Every accessor has an untyped form returning {@code Object} and a typed form taking the {@link
 * TypeToken} that was declared for that slot. The typed form requires the query token to be exactly
 * equal to the declared token — no widening to a supertype — so asking for {@code
 * TypeToken<List<Order>>} where {@code TypeToken<List<Customer>>} was declared is rejected at the
 * lookup instead of surfacing as a {@code ClassCastException} at the use site. The equality check
 * runs even when the stored value is null: null is a legitimate successful value, not a wildcard
 * that skips validation.
 *
 * <p>The snapshot is shallowly immutable and never blocks: it is built only after every member has
 * completed, from already-settled futures. User objects inside it are not copied or frozen.
 *
 * <p>The declared tokens are not deep runtime validation. A token proves which parameterized type
 * the caller asked for; it cannot prove that every element inside a {@code List} matches, because
 * that information is erased.
 *
 * @param <V> the group's assembled member-value type
 */
public final class GroupValues<V> {
    private final ImmutableList<String> names;
    private final ImmutableList<TypeToken<?>> declaredTypes;

    /** Null-tolerant by design: {@code ImmutableList} rejects nulls, successful member values allow them. */
    private final List<@Nullable Object> values;

    private final @Nullable V typedValues;

    private GroupValues(
            ImmutableList<String> names,
            ImmutableList<TypeToken<?>> declaredTypes,
            List<@Nullable Object> values,
            @Nullable V typedValues) {
        this.names = names;
        this.declaredTypes = declaredTypes;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.typedValues = typedValues;
    }

    static <V> GroupValues<V> of(
            ImmutableList<String> names,
            ImmutableList<TypeToken<?>> declaredTypes,
            List<@Nullable Object> values,
            @Nullable V typedValues) {
        return new GroupValues<>(names, declaredTypes, values, typedValues);
    }

    /** The empty snapshot of a group with no plain members; {@link #typedValues()} is null. */
    @SuppressWarnings("unchecked")
    static <V> GroupValues<V> empty() {
        return (GroupValues<V>)
                new GroupValues<Object>(ImmutableList.of(), ImmutableList.of(), Collections.emptyList(), null);
    }

    /** The number of plain members; the terminal combine never occupies a slot. */
    public int size() {
        return names.size();
    }

    /**
     * The member values as the group's static type: the single member's value for a one-member
     * group, left-nested {@link Tuple2} for larger groups, and null for a group with no members.
     *
     * <p>Components may be null. This is the same value a terminal {@link CombineBody} receives.
     */
    public @Nullable V typedValues() {
        return typedValues;
    }

    /**
     * The value at a declaration position, or null when the member succeeded with null.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #size()}
     */
    public @Nullable Object valueAt(int index) {
        checkIndex(index);
        return values.get(index);
    }

    /**
     * The value of the named member, or null when the member succeeded with null.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member was declared with that name
     */
    public @Nullable Object valueOf(String name) {
        return values.get(locate(name));
    }

    /**
     * The value at a declaration position, checked against the token declared for that slot.
     *
     * @throws NullPointerException if {@code expectedType} is null
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #size()}
     * @throws IllegalArgumentException if {@code expectedType} is not exactly the declared token
     */
    public <T> @Nullable T valueAt(int index, TypeToken<T> expectedType) {
        Objects.requireNonNull(expectedType, "expectedType cannot be null");
        checkIndex(index);
        requireDeclaredType(index, expectedType);
        return cast(values.get(index));
    }

    /**
     * The value of the named member, checked against the token declared for that slot.
     *
     * @throws NullPointerException if {@code name} or {@code expectedType} is null
     * @throws IllegalArgumentException if no member was declared with that name, or {@code
     *     expectedType} is not exactly the declared token
     */
    public <T> @Nullable T valueOf(String name, TypeToken<T> expectedType) {
        Objects.requireNonNull(expectedType, "expectedType cannot be null");
        int index = locate(name);
        requireDeclaredType(index, expectedType);
        return cast(values.get(index));
    }

    /**
     * The token declared for the member at a declaration position, for callers that genuinely
     * address slots dynamically and need to inspect the schema before reading.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #size()}
     */
    public TypeToken<?> typeAt(int index) {
        checkIndex(index);
        return declaredTypes.get(index);
    }

    /**
     * The token declared for the named member.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member was declared with that name
     */
    public TypeToken<?> typeOf(String name) {
        return declaredTypes.get(locate(name));
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= values.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " is out of bounds for a group with " + values.size() + " members");
        }
    }

    private int locate(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        int index = names.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("no member named '" + name + "'");
        }
        return index;
    }

    private void requireDeclaredType(int index, TypeToken<?> expectedType) {
        TypeToken<?> declared = declaredTypes.get(index);
        if (!declared.equals(expectedType)) {
            throw new IllegalArgumentException("member '" + names.get(index) + "' (index " + index
                    + ") was declared as " + declared + " but queried as " + expectedType);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable T cast(@Nullable Object value) {
        return (T) value;
    }

    @Override
    public String toString() {
        return "GroupValues" + names;
    }
}

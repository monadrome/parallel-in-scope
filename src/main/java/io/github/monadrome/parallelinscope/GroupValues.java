package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Read-only snapshot of one group run's successful member values, published by {@link
 * TaskGroupResult#valuesOrThrow()} and handed to a terminal {@link CombineBody}.
 *
 * <p>Typed lookup uses the exact {@link TaskKey} supplied at declaration. Dynamic lookup by
 * zero-based declaration position ({@link #valueAt(int)}) or name ({@link #valueOf(String)})
 * returns {@code Object}. The order is the order the {@code par} calls were written, never the
 * order tasks completed in. Successful null values are allowed.
 *
 * <p>The snapshot is shallowly immutable and never blocks: it contains only completed members'
 * successful values. User objects inside it are not copied or frozen.
 *
 * <p>Types are enforced by the compiler; no runtime type information is stored or checked. Raw
 * types and unchecked casts can defeat this guarantee and are the caller's responsibility.
 *
 * @param <V> the group's assembled member-value type
 * @since 0.3.0
 */
public final class GroupValues<V> {
    private final ImmutableList<String> names;

    private final ImmutableList<TaskKey<?>> keys;

    /*
     * Null-tolerant by design: ImmutableList rejects nulls, successful member values allow them.
     */
    private final List<@Nullable Object> values;

    private final @Nullable V typedValues;

    private GroupValues(
            ImmutableList<String> names,
            ImmutableList<TaskKey<?>> keys,
            List<@Nullable Object> values,
            @Nullable V typedValues) {
        this.names = names;

        this.keys = keys;
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.typedValues = typedValues;
    }

    static <V> GroupValues<V> of(
            ImmutableList<String> names,
            ImmutableList<TaskKey<?>> keys,
            List<@Nullable Object> values,
            @Nullable V typedValues) {
        return new GroupValues<>(names, keys, values, typedValues);
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
     * Reads a member using the exact key object supplied at declaration. A successful null is
     * returned as null; an absent key throws even when another member has the same name and type.
     * The terminal combine is not a member of this view.
     *
     * @throws NullPointerException if the key is null
     * @throws IllegalArgumentException if the key was not declared as a member of this group
     */
    public <T> @Nullable T valueOf(TaskKey<T> key) {
        checkNotNull(key, "key cannot be null");
        int index = keys.indexOf(key);
        checkArgument(index >= 0, "no member declared with task key '%s'", key);
        return cast(values.get(index));
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

    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    private static <T> @Nullable T cast(@Nullable Object value) {
        return (T) value;
    }

    @Override
    public String toString() {
        return "GroupValues" + names;
    }
}

package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * Immutable, typed address of a declared task result, reusable across group runs.
 *
 * <p>Keys use object identity: two keys with the same name and type are different addresses.
 * Keep and reuse the same key for declaration and lookup, usually in a {@code static final}
 * field. The name supplies the group's unique diagnostic name, not the key's equality.
 * A key does not prove that a task was declared or executed, and is not a {@link TaskId}.
 *
 * <p>The type binds the task body and result lookup at compile time. Keys carry no runtime
 * type information and results are not checked at runtime. Raw types and unchecked casts can
 * defeat these guarantees and are the caller's responsibility.
 *
 * @param <T> the task's result type
 * @since 0.4.0
 */
@Immutable
@SuppressWarnings("UnusedTypeParameter")
public final class TaskKey<T> {
    private final String name;

    private TaskKey(String name) {
        this.name = Validation.requireName(name, "task key name");
    }

    /**
     * Creates a fresh key. Keep this object for both declaration and typed lookup.
     *
     * @throws NullPointerException if the name is null
     * @throws IllegalArgumentException if the name is blank
     */
    public static <T> TaskKey<T> named(String name) {
        return new TaskKey<>(name);
    }

    /** The task name, unique among members and the combine within a group. */
    public String name() {
        return name;
    }

    /** Diagnostic name only; equal renderings do not imply equal keys. */
    @Override
    public String toString() {
        return name;
    }
}

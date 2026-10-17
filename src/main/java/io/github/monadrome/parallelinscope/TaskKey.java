package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.reflect.TypeToken;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
 * Immutable, typed address of a declared task result, reusable across group runs.
 *
 * <p>Keys use object identity: two keys with the same name and type are different addresses.
 * Keep and reuse the same key for declaration and lookup, usually in a {@code static final}
 * field. The name supplies the group's unique diagnostic name, not the key's equality.
 * A key does not prove that a task was declared or executed, and is not a {@link TaskId}.
 *
 * <p>The type binds the task body and result lookup at compile time and retains the existing
 * raw-class runtime check. Parameterized tokens describe the declared type but cannot validate
 * elements of erased containers. Raw types and unchecked casts can defeat these guarantees.
 *
 * @param <T> the task's result type
 * @since 0.4.0
 */
public final class TaskKey<T> {
    private final String name;
    private final TypeToken<T> type;

    private TaskKey(String name, TypeToken<T> type) {
        this.name = Validation.requireName(name, "task key name");
        this.type = checkNotNull(type, "type cannot be null");
        checkConcreteType(name, type);
    }

    /**
     * Creates a fresh key for a concrete reference type.
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the name is blank or the type is primitive or unresolved
     */
    public static <T> TaskKey<T> of(String name, TypeToken<T> type) {
        return new TaskKey<>(name, type);
    }

    /**
     * Creates a fresh key for a plain reference class; use a {@link TypeToken} for generic types.
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the name is blank or the class is primitive or void
     */
    public static <T> TaskKey<T> of(String name, Class<T> type) {
        return of(name, TypeToken.of(checkNotNull(type, "type cannot be null")));
    }

    /** The task name, unique among members and the combine within a group. */
    public String name() {
        return name;
    }

    /** The concrete declared result type. */
    public TypeToken<T> type() {
        return type;
    }

    private static void checkConcreteType(String name, TypeToken<?> type) {
        Class<?> rawType = type.getRawType();
        checkArgument(
                !rawType.isPrimitive(), "the declared type of '%s' must be a reference type, not %s", name, rawType);
        checkArgument(
                !containsTypeVariable(type.getType()),
                "the declared type of '%s' must be a concrete type without type variables, but was %s",
                name,
                type);
    }

    private static boolean containsTypeVariable(Type type) {
        if (type instanceof TypeVariable) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (containsTypeVariable(parameterized.getRawType())) {
                return true;
            }
            Type owner = parameterized.getOwnerType();
            if (owner != null && containsTypeVariable(owner)) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (containsTypeVariable(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return containsTypeVariable(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            for (Type bound : wildcard.getUpperBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            for (Type bound : wildcard.getLowerBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Diagnostic name only; equal renderings do not imply equal keys. */
    @Override
    public String toString() {
        return name;
    }
}

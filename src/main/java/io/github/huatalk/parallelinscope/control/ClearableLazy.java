package io.github.huatalk.parallelinscope.control;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

import java.util.Objects;

/**
 * A thread-safe container that computes or exposes a value once and can later be cleared
 * permanently.
 *
 * <p>Every instance follows the one-way lifecycle {@link State#NEW NEW} to {@link
 * State#IMMUTABLE IMMUTABLE} to {@link State#CLEARED CLEARED}. A successful call to {@link
 * #get()} fixes the value and moves the container to {@code IMMUTABLE}. {@link #clear()} discards
 * references held by the container and moves it to {@code CLEARED}; a cleared container cannot be
 * read or restored.
 *
 * <p>Like Guava's memoizing suppliers, a failed computation is not cached. The container remains
 * {@code NEW}, so a later call may retry it. Immutability applies to the value reference held by
 * this container, not to the value object's internal state.
 *
 * @param <T> value type
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class ClearableLazy<T> implements Supplier<T> {

    /** Lifecycle states of a {@link ClearableLazy}. */
    public enum State {
        /** No value has been obtained successfully. */
        NEW,
        /** The first successful value has been fixed. */
        IMMUTABLE,
        /** The container has been cleared permanently. */
        CLEARED
    }

    private volatile State state = State.NEW;
    private Supplier<T> initializer;
    private T value;

    private ClearableLazy(Supplier<T> initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    /**
     * Creates a lazy container for an existing value.
     *
     * @param value value to expose; may be {@code null}
     * @param <T> value type
     * @return a new container in the {@link State#NEW NEW} state
     */
    public static <T> ClearableLazy<T> of(T value) {
        return new ClearableLazy<>(Suppliers.ofInstance(value));
    }

    /**
     * Creates a lazy container backed by a computation.
     *
     * @param initializer Guava computation used at most once successfully
     * @param <T> value type
     * @return a new container in the {@link State#NEW NEW} state
     */
    public static <T> ClearableLazy<T> from(Supplier<T> initializer) {
        return new ClearableLazy<>(Suppliers.memoize(Objects.requireNonNull(initializer, "initializer")));
    }

    /**
     * Returns the fixed value, computing it on the first successful call.
     *
     * @return the fixed value; may be {@code null}
     * @throws IllegalStateException if this container has been cleared
     * @throws RuntimeException if the computation fails
     */
    @Override
    public synchronized T get() {
        if (state == State.CLEARED) {
            throw new IllegalStateException("lazy container has been cleared");
        }
        if (state == State.NEW) {
            value = initializer.get();
            initializer = null;
            state = State.IMMUTABLE;
        }
        return value;
    }

    /**
     * Permanently clears this container and releases the references it holds.
     *
     * <p>Calling this method more than once has no additional effect.
     */
    public synchronized void clear() {
        if (state == State.CLEARED) {
            return;
        }
        initializer = null;
        value = null;
        state = State.CLEARED;
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return current state
     */
    public State getState() {
        return state;
    }
}

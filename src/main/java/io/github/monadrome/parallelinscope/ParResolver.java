package io.github.monadrome.parallelinscope;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only discovery of the executor-bound {@link Par} entries used by an application.
 *
 * <p>The resolver is intended for dependency injection at a composition boundary. It does not
 * mutate a runtime, select an executor from task type, or create a global service locator. A
 * runtime's topology is frozen before a resolver is handed to business code.
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface ParResolver {

    /**
     * Returns the registered entry for {@code id}, or empty when the topology has no such entry.
     *
     * @since 0.4.0
     */
    Optional<Par> find(ParId id);

    /**
     * Resolves a registered entry.
     *
     * @throws NullPointerException if {@code id} is null
     * @throws IllegalArgumentException if no entry is registered under {@code id}
     * @since 0.4.0
     */
    default Par resolve(ParId id) {
        Objects.requireNonNull(id, "id cannot be null");
        Optional<Par> value = find(id);
        if (!value.isPresent()) {
            throw new IllegalArgumentException("no Par registered with id '" + id + "'");
        }
        return value.get();
    }
}

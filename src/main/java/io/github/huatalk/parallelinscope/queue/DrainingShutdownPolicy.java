package io.github.huatalk.parallelinscope.queue;

import java.util.Objects;
import javax.annotation.Nullable;

/** Immutable shutdown behavior for {@link DrainingBlockingQueue}. */
public final class DrainingShutdownPolicy<E> {

    /** Behavior of collection mutations after the queue has drained. */
    public enum MutationsStrategy {
        NOOP,
        THROW
    }

    @Nullable
    private final E poison;

    private final MutationsStrategy mutationsStrategy;

    private DrainingShutdownPolicy(@Nullable E poison, MutationsStrategy mutationsStrategy) {
        this.poison = poison;
        this.mutationsStrategy = Objects.requireNonNull(mutationsStrategy, "mutationsStrategy");
    }

    public static <E> DrainingShutdownPolicy<E> empty() {
        return new DrainingShutdownPolicy<>(null, MutationsStrategy.NOOP);
    }

    public static <E> DrainingShutdownPolicy<E> poison(E poison) {
        return new DrainingShutdownPolicy<>(Objects.requireNonNull(poison, "poison"), MutationsStrategy.NOOP);
    }

    public static <E> DrainingShutdownPolicy<E> throwing() {
        return new DrainingShutdownPolicy<>(null, MutationsStrategy.THROW);
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    @Nullable
    E poison() {
        return poison;
    }

    MutationsStrategy mutationsStrategy() {
        return mutationsStrategy;
    }

    /** Builder for independent poison and mutation choices. */
    public static final class Builder<E> {
        @Nullable
        private E poison;

        private MutationsStrategy mutationsStrategy = MutationsStrategy.NOOP;

        public Builder<E> poison(E poison) {
            this.poison = Objects.requireNonNull(poison, "poison");
            return this;
        }

        public Builder<E> mutations(MutationsStrategy mutationsStrategy) {
            this.mutationsStrategy = Objects.requireNonNull(mutationsStrategy, "mutationsStrategy");
            return this;
        }

        public DrainingShutdownPolicy<E> build() {
            return new DrainingShutdownPolicy<>(poison, mutationsStrategy);
        }
    }
}

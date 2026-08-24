package io.github.huatalk.parallelinscope.queue;

import java.util.Objects;
import javax.annotation.Nullable;

/** Immutable shutdown behavior for {@link ClosableBlockingQueueV2}. */
public final class QueueShutdownPolicy<E> {

    /** Behavior of collection mutations after close. */
    public enum MutationsStrategy {
        NOOP,
        THROW
    }

    @Nullable
    private final E poison;

    private final MutationsStrategy mutationsStrategy;

    private QueueShutdownPolicy(@Nullable E poison, MutationsStrategy mutationsStrategy) {
        this.poison = poison;
        this.mutationsStrategy = Objects.requireNonNull(mutationsStrategy, "mutationsStrategy");
    }

    public static <E> QueueShutdownPolicy<E> empty() {
        return new QueueShutdownPolicy<>(null, MutationsStrategy.NOOP);
    }

    public static <E> QueueShutdownPolicy<E> poison(E poison) {
        return new QueueShutdownPolicy<>(Objects.requireNonNull(poison, "poison"), MutationsStrategy.NOOP);
    }

    public static <E> QueueShutdownPolicy<E> throwing() {
        return new QueueShutdownPolicy<>(null, MutationsStrategy.THROW);
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

        public QueueShutdownPolicy<E> build() {
            return new QueueShutdownPolicy<>(poison, mutationsStrategy);
        }
    }
}

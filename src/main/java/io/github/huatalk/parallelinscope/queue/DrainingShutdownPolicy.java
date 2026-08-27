package io.github.huatalk.parallelinscope.queue;

import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Immutable terminal-state behavior for {@link DrainingBlockingQueue}.
 *
 * <p>The dimensions are independent: {@code poison} controls what value-returning consumer
 * methods observe only after the queue is {@code DRAINED}; {@link MutationsStrategy} controls
 * {@code clear}, {@code remove}, {@code removeIf}, {@code removeAll}, and {@code retainAll} only
 * after that same terminal state. Neither setting changes the rule that consumers receive all real
 * queued elements while the queue is {@code DRAINING}. The poison is a virtual signal, never a
 * stored element, and queue write paths reject elements equal to it.
 */
public final class DrainingShutdownPolicy<E> {

    /** Behavior of ordinary collection mutations after the queue has drained. */
    public enum MutationsStrategy {
        /** Terminal mutations report no change and leave the already empty queue untouched. */
        NOOP,

        /** Terminal mutations throw {@link IllegalStateException} to make a stale mutation visible. */
        THROW
    }

    @Nullable
    private final E poison;

    private final MutationsStrategy mutationsStrategy;

    private DrainingShutdownPolicy(@Nullable E poison, MutationsStrategy mutationsStrategy) {
        this.poison = poison;
        this.mutationsStrategy = Objects.requireNonNull(mutationsStrategy, "mutationsStrategy");
    }

    /**
     * Returns the default: no poison, with terminal mutations as no-ops. At {@code DRAINED},
     * special-value reads return {@code null}, required-value reads throw, and ordinary mutations
     * report no change.
     */
    public static <E> DrainingShutdownPolicy<E> empty() {
        return new DrainingShutdownPolicy<>(null, MutationsStrategy.NOOP);
    }

    /**
     * Returns a policy that emits {@code poison} from every value-returning consumer method after
     * {@code DRAINED}, while terminal mutations remain no-ops. The poison must be non-null.
     */
    public static <E> DrainingShutdownPolicy<E> poison(E poison) {
        return new DrainingShutdownPolicy<>(Objects.requireNonNull(poison, "poison"), MutationsStrategy.NOOP);
    }

    /**
     * Returns a policy with no poison that rejects ordinary mutations after {@code DRAINED}.
     * Required-value consumers still throw {@link java.util.NoSuchElementException}, and
     * special-value consumers still return {@code null}.
     */
    public static <E> DrainingShutdownPolicy<E> throwing() {
        return new DrainingShutdownPolicy<>(null, MutationsStrategy.THROW);
    }

    /** Returns a builder for independently choosing the terminal signal and mutation behavior. */
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

    /**
     * Builder for independent poison and mutation choices. Unset choices use the same defaults as
     * {@link DrainingShutdownPolicy#empty()}: no poison and {@link MutationsStrategy#NOOP}.
     */
    public static final class Builder<E> {
        @Nullable
        private E poison;

        private MutationsStrategy mutationsStrategy = MutationsStrategy.NOOP;

        /**
         * Configures the non-null virtual signal returned only after {@code DRAINED}. It does not
         * cause {@code DRAINING} consumers to stop receiving real queued elements.
         */
        public Builder<E> poison(E poison) {
            this.poison = Objects.requireNonNull(poison, "poison");
            return this;
        }

        /**
         * Configures the behavior of ordinary collection mutations only after {@code DRAINED};
         * mutations remain valid while {@code DRAINING} regardless of this choice.
         */
        public Builder<E> mutations(MutationsStrategy mutationsStrategy) {
            this.mutationsStrategy = Objects.requireNonNull(mutationsStrategy, "mutationsStrategy");
            return this;
        }

        /** Returns the immutable policy represented by the current independent choices. */
        public DrainingShutdownPolicy<E> build() {
            return new DrainingShutdownPolicy<>(poison, mutationsStrategy);
        }
    }
}

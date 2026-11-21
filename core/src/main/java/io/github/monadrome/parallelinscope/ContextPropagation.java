package io.github.monadrome.parallelinscope;

/**
 * Captures application context without changing the calling thread's bindings.
 *
 * <p>Implementations must support concurrent capture and return reusable snapshots. Capture must
 * not acquire resources requiring explicit release. Providers define whether captured values are
 * immutable, copied, or shared; binding restoration does not undo mutations to shared values.
 * Capture, installation, and restoration must not reenter this protocol. Framework task identity,
 * cancellation, deadlines, and submission policy are not application context.
 */
@FunctionalInterface
public interface ContextPropagation {

    /** Returns a non-null reusable snapshot of the calling thread's application context. */
    ContextSnapshot capture();

    /** Returns the provider for Alibaba TTL context; this is the runtime default. */
    static ContextPropagation ttl() {
        return ContextPropagationSupport.TTL;
    }

    /** Returns a provider that leaves application bindings unchanged, not a provider that clears them. */
    static ContextPropagation none() {
        return ContextPropagationSupport.NONE;
    }

    /**
     * Composes disjoint providers in capture and installation order, unwinding in reverse order.
     *
     * <p>The provider array is copied. Repeated provider instances and nulls are rejected before
     * capture. Providers must own disjoint bindings; overlapping ownership cannot be detected.
     * An empty composition is equivalent to {@link #none()}.
     *
     * @param providers providers with disjoint context ownership
     * @return an immutable composition
     * @throws NullPointerException if the array or a provider is null
     * @throws IllegalArgumentException if a provider instance occurs more than once
     */
    static ContextPropagation compose(ContextPropagation... providers) {
        return ContextPropagationSupport.compose(providers);
    }
}

package integrations.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import integrations.support.Cancellations;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Guards one downstream call inside a batch element or group member with a Sentinel resource.
 *
 * <p>Sentinel keeps its call stack in a plain {@link ThreadLocal}, so an entry must be acquired and
 * exited on the same thread, inside the execution. Acquiring it around {@code Par.map} on the caller
 * would count one call for the whole fan-out and exit on the wrong thread. A blocked entry degrades
 * that element or member to the fallback value; it does not fail the batch or group. Business
 * failures are reported to Sentinel with {@link Tracer#traceEntry}, while failures caused by
 * cancellation are not, so fail-fast and deadlines do not trip exception-based degrade rules.
 */
public final class SentinelGuard {

    private SentinelGuard() {}

    /**
     * Calls {@code downstream} as Sentinel resource {@code resource}, or returns the fallback when
     * Sentinel blocks the entry.
     *
     * <p>Runs in whatever Sentinel context the current thread already has, or in Sentinel's default
     * context, which has no origin. Use {@link #callWithOrigin} when rules depend on the caller.
     *
     * @param resource the Sentinel resource name; keep it a fixed name, not one built per call
     * @param downstream the synchronous downstream call
     * @param whenBlocked supplies the degraded value when a flow, degrade, or system rule blocks
     * @param <T> the result type
     * @return the downstream value, or the degraded value when blocked
     */
    public static <T> T call(
            String resource,
            Supplier<? extends T> downstream,
            Function<? super BlockException, ? extends T> whenBlocked) {
        Entry entry;
        try {
            entry = SphU.entry(resource, EntryType.OUT);
        } catch (BlockException blocked) {
            return whenBlocked.apply(blocked);
        }
        try {
            return downstream.get();
        } catch (RuntimeException | Error failure) {
            if (!Cancellations.causedByCancellation(failure)) {
                Tracer.traceEntry(failure, entry);
            }
            throw failure;
        } finally {
            entry.exit();
        }
    }

    /**
     * Like {@link #call}, but enters Sentinel context {@code contextName} with caller {@code
     * origin} first, so origin-specific rules ({@code limitApp}) apply on the worker thread.
     *
     * <p>The origin is a request value that does not reach the worker by itself: capture it on the
     * request thread and pass it in. The context is exited before this method returns, so it never
     * stays behind on a pooled thread for the next task. When the current thread already has a
     * Sentinel context, as on a caller thread that runs an execution inline, that context and its origin
     * are kept and left in place.
     *
     * @param contextName the Sentinel context name, typically the entrance of the request
     * @param origin the caller identity that origin-specific rules match
     * @param resource the Sentinel resource name
     * @param downstream the synchronous downstream call
     * @param whenBlocked supplies the degraded value when a rule blocks
     * @param <T> the result type
     * @return the downstream value, or the degraded value when blocked
     */
    public static <T> T callWithOrigin(
            String contextName,
            String origin,
            String resource,
            Supplier<? extends T> downstream,
            Function<? super BlockException, ? extends T> whenBlocked) {
        if (ContextUtil.getContext() != null) {
            return call(resource, downstream, whenBlocked);
        }
        ContextUtil.enter(contextName, origin);
        try {
            return call(resource, downstream, whenBlocked);
        } finally {
            ContextUtil.exit();
        }
    }
}

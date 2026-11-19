package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Identity rendering of user objects inside diagnostic strings.
 *
 * <p>A user object's own {@code toString} may be expensive, buggy, or leak data into logs, so a
 * diagnostic rendering never calls it. The object appears as its concrete class and identity
 * hash, the shape Guava's {@code AbstractFuture.appendResultObject} uses for the inputs and
 * functions of a pending future. A failure renders as its type only, because a throwable's
 * message can carry credentials and its rendering can throw.
 */
final class ResultObjects {

    private ResultObjects() {}

    /**
     * Renders {@code value} without calling its {@code toString}: "null" when null, "this future"
     * when it is {@code owner} itself, and {@code SimpleClassName@identityHash} otherwise. An
     * anonymous class has no simple name, so it renders with its full binary name instead of an
     * empty prefix.
     */
    static String render(@Nullable Object value, Object owner) {
        if (value == null) {
            return "null";
        }
        if (value == owner) {
            return "this future";
        }
        String simpleName = value.getClass().getSimpleName();
        String className = simpleName.isEmpty() ? value.getClass().getName() : simpleName;
        return className + '@' + Integer.toHexString(System.identityHashCode(value));
    }

    /**
     * Renders {@code failure} as its type only: "null" when null, "this future" when it is
     * {@code owner} itself, and the fully-qualified class name otherwise. Neither the throwable's
     * {@code toString} nor its message ever runs — a message can disclose credentials into logs,
     * and user rendering code can throw — so this rendering has nothing to guard against and no
     * fallback to degrade to.
     */
    static String renderFailure(@Nullable Throwable failure, Object owner) {
        if (failure == null) {
            return "null";
        }
        if (failure == owner) {
            return "this future";
        }
        return failure.getClass().getName();
    }

    /**
     * The terminal status branch of a future envelope, mirroring Guava's {@code AbstractFuture}:
     * {@code SUCCESS} with the value's identity, {@code FAILURE} with the cause's type, {@code
     * CANCELLED}, or the guarded {@code UNKNOWN}. The whole branch is computed before it is
     * returned, so a throwing outcome read cannot leave a half-written success prefix in front of
     * the failure branch. Reading the outcome wraps a failure in an {@link ExecutionException},
     * whose construction renders the cause, so a cause whose own {@code toString} throws degrades
     * the branch to {@code UNKNOWN} instead of throwing out of a diagnostic.
     */
    static String doneBranch(ListenableFuture<?> delegate, Object owner) {
        try {
            return "SUCCESS, result=[" + render(Futures.getDone(delegate), owner) + ']';
        } catch (ExecutionException failure) {
            return "FAILURE, cause=[" + renderFailure(failure.getCause(), owner) + ']';
        } catch (CancellationException cancelled) {
            return "CANCELLED";
        } catch (Throwable thrownFromGet) {
            return "UNKNOWN, cause=[" + thrownFromGet.getClass() + " thrown from get()]";
        }
    }
}

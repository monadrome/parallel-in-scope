package io.github.linzee1.vformation.cancel;

/**
 * Heavy-weight cancellation exception that preserves the full stack trace.
 * <p>
 * Used for debugging purposes where stack trace information is needed
 * to track where cancellation originated.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 * @see LeanCancellationException
 */
public class FatCancellationException extends java.util.concurrent.CancellationException {

    private static final long serialVersionUID = 1L;

    public FatCancellationException(String message) {
        super(message);
    }
}

package io.github.huatalk.parallelinscope.cancel;

/**
 * Cancellation exception that retains its stack trace for diagnostics.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see LeanCancellationException
 */
public class FatCancellationException extends java.util.concurrent.CancellationException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a cancellation exception that retains its stack trace.
     *
     * @param message the detail message
     */
    public FatCancellationException(String message) {
        super(message);
    }
}

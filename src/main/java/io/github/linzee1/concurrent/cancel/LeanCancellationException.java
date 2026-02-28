package io.github.linzee1.concurrent.cancel;

/**
 * Light-weight cancellation exception that skips stack trace filling for performance.
 * <p>
 * Overrides {@link Throwable#fillInStackTrace()} to avoid the overhead of capturing
 * stack traces in production environments. Use when cancellation is frequent and
 * stack trace information is not needed.
 *
 * @author linqh
 * @see FatCancellationException
 */
public class LeanCancellationException extends java.util.concurrent.CancellationException {

    private static final long serialVersionUID = 1L;

    public LeanCancellationException(String message) {
        super(message);
        setStackTrace(new StackTraceElement[0]);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}

package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Library-marked acknowledgement that a {@link Checkpoints} call read a committed cancellation
 * token.
 *
 * <p>The class is the provenance mark: it is package-private and final, and only {@link
 * #acknowledge(String, Throwable)} constructs it, so user code can neither construct nor subclass
 * a marked instance. {@link Checkpoints} constructs it only after reading a token whose state is
 * already committed, including the deadline backstop that commits {@code TIMEOUT} itself before
 * throwing; it never constructs one for an uncommitted token. Callers outside the package see an
 * ordinary {@link LeanCancellationException}.
 *
 * <p>The mark only selects the late-failure log level in the shared future kernel; it does not
 * take part in outcome attribution, which reads the token.
 */
final class CheckpointCancellation extends LeanCancellationException {

    private static final long serialVersionUID = 1L;

    private CheckpointCancellation(String message) {
        super(message);
    }

    /**
     * Creates a marked acknowledgement. Call only after the current token was read as committed.
     *
     * @param message the detail message
     * @param cause the interruption the checkpoint translated, or {@code null} when the checkpoint
     *     read the token directly
     * @return a new acknowledgement without a stack trace
     */
    static CheckpointCancellation acknowledge(String message, @Nullable Throwable cause) {
        CheckpointCancellation acknowledgement = new CheckpointCancellation(message);
        if (cause != null) {
            acknowledgement.initCause(cause);
        }
        return acknowledgement;
    }
}

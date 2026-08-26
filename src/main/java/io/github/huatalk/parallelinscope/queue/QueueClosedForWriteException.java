package io.github.huatalk.parallelinscope.queue;

/** Rejected write operation after producer admission has closed. */
public class QueueClosedForWriteException extends IllegalStateException implements QueueShutdownException {

    private static final long serialVersionUID = 1L;

    public QueueClosedForWriteException(String message) {
        super(message);
    }
}

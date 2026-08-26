package io.github.huatalk.parallelinscope.queue;

import java.util.NoSuchElementException;

/** Rejected read operation after the queue has drained. */
public class QueueClosedForReadException extends NoSuchElementException implements QueueShutdownException {

    private static final long serialVersionUID = 1L;

    public QueueClosedForReadException(String message) {
        super(message);
    }
}

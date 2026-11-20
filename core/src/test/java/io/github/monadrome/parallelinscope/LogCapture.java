package io.github.monadrome.parallelinscope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;
import org.slf4j.helpers.MessageFormatter;

/**
 * Subscribes to the events one class-name logger publishes through the test-scope
 * {@link CapturingLoggerProvider}, replacing the JUL {@code Handler} capture the logging tests
 * used before the SLF4J migration. Every published event is recorded with its level, formatted
 * message, throwable, and publishing thread; the {@link #failOn(String)} and
 * {@link #blockOn(String)} hooks model a failing or blocked logging backend, throwing or stalling
 * out of the library's logging call exactly like a backend that does.
 *
 * <p>Attach in setup and {@link #close()} in teardown, mirroring the handler-removal discipline of
 * the JUL tests: an attached capture keeps recording until closed.
 */
final class LogCapture implements AutoCloseable {

    private static final ConcurrentMap<String, CopyOnWriteArrayList<LogCapture>> ATTACHED = new ConcurrentHashMap<>();

    /** One published log event. */
    static final class Event {
        private final Level level;
        private final String message;
        private final @Nullable Throwable thrown;
        private final Thread thread;

        private Event(Level level, String message, @Nullable Throwable thrown, Thread thread) {
            this.level = level;
            this.message = message;
            this.thrown = thrown;
            this.thread = thread;
        }

        Level level() {
            return level;
        }

        String message() {
            return message;
        }

        @Nullable
        Throwable thrown() {
            return thrown;
        }

        /** The thread that ran the logging call, so delivery can be pinned off the admitting thread. */
        Thread thread() {
            return thread;
        }
    }

    private static final class LatchRule {
        private final String substring;
        private final CountDownLatch latch;

        private LatchRule(String substring, CountDownLatch latch) {
            this.substring = substring;
            this.latch = latch;
        }
    }

    private static final class BlockRule {
        private final String substring;
        private final CountDownLatch published;
        private final CountDownLatch release;

        private BlockRule(String substring, CountDownLatch published, CountDownLatch release) {
            this.substring = substring;
            this.published = published;
            this.release = release;
        }
    }

    /** Called by the capturing provider on the thread that ran the library's logging call. */
    static void publish(
            String loggerName,
            Level level,
            String messagePattern,
            Object @Nullable [] arguments,
            @Nullable Throwable throwable) {
        CopyOnWriteArrayList<LogCapture> captures = ATTACHED.get(loggerName);
        if (captures == null) {
            return;
        }
        String message = MessageFormatter.arrayFormat(messagePattern, arguments, throwable)
                .getMessage();
        Event event = new Event(level, message, throwable, Thread.currentThread());
        for (LogCapture capture : captures) {
            capture.deliver(event);
        }
    }

    /** Attaches a new capture to the logger named after {@code owner}, exactly the logger the owning class uses. */
    static LogCapture attach(Class<?> owner) {
        LogCapture capture = new LogCapture();
        ATTACHED.computeIfAbsent(owner.getName(), name -> new CopyOnWriteArrayList<>())
                .add(capture);
        return capture;
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<LatchRule> latchRules = new CopyOnWriteArrayList<>();
    private final List<String> failSubstrings = new CopyOnWriteArrayList<>();
    private final List<BlockRule> blockRules = new CopyOnWriteArrayList<>();

    private void deliver(Event event) {
        events.add(event);
        for (LatchRule rule : latchRules) {
            if (event.message().contains(rule.substring)) {
                rule.latch.countDown();
            }
        }
        for (String substring : failSubstrings) {
            if (event.message().contains(substring)) {
                throw new IllegalStateException("logging backend unavailable");
            }
        }
        for (BlockRule rule : blockRules) {
            if (event.message().contains(rule.substring)) {
                rule.published.countDown();
                try {
                    rule.release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Every event recorded so far, oldest first. */
    List<Event> events() {
        return new ArrayList<>(events);
    }

    /** Every recorded event whose message contains {@code substring}, oldest first. */
    List<Event> events(String substring) {
        List<Event> matching = new ArrayList<>();
        for (Event event : events) {
            if (event.message().contains(substring)) {
                matching.add(event);
            }
        }
        return matching;
    }

    /** Drops the recorded events; hooks stay registered. */
    void clear() {
        events.clear();
    }

    /** Returns a latch counted down on every published event whose message contains {@code substring}. */
    CountDownLatch latchOn(String substring) {
        CountDownLatch latch = new CountDownLatch(1);
        latchRules.add(new LatchRule(substring, latch));
        return latch;
    }

    /**
     * Makes every published event whose message contains {@code substring} throw out of the
     * library's logging call, like a failing logging backend; the event is still recorded first.
     */
    void failOn(String substring) {
        failSubstrings.add(substring);
    }

    /**
     * Makes every published event whose message contains {@code substring} block inside the
     * library's logging call until {@link #releaseBlocked()}, like a stalled logging backend.
     * Returns a latch counted down when the first such block begins.
     */
    CountDownLatch blockOn(String substring) {
        CountDownLatch published = new CountDownLatch(1);
        blockRules.add(new BlockRule(substring, published, new CountDownLatch(1)));
        return published;
    }

    /** Releases every event currently blocked by a {@link #blockOn(String)} rule. */
    void releaseBlocked() {
        for (BlockRule rule : blockRules) {
            rule.release.countDown();
        }
    }

    @Override
    public void close() {
        for (CopyOnWriteArrayList<LogCapture> captures : ATTACHED.values()) {
            captures.remove(this);
        }
        releaseBlocked();
    }
}

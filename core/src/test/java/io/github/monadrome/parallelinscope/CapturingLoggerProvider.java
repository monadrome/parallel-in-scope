package io.github.monadrome.parallelinscope;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The test-scope SLF4J provider: every logger the library creates routes its events to the
 * {@link LogCapture} instances attached to that logger's name, so tests assert diagnostics without
 * a published backend. Registered through
 * {@code core/src/test/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider}; it lives in
 * the test tree only, so the published jar carries no provider and the library's consumers still
 * choose their own backend.
 */
public final class CapturingLoggerProvider implements SLF4JServiceProvider {

    /** Matches the slf4j-api 2.0.x line the build compiles against. */
    private static final String REQUESTED_API_VERSION = "2.0.99";

    private final ILoggerFactory loggerFactory = new CapturingLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public void initialize() {}

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    private static final class CapturingLoggerFactory implements ILoggerFactory {
        private final ConcurrentMap<String, CapturingLogger> loggers = new ConcurrentHashMap<>();

        @Override
        public org.slf4j.Logger getLogger(String name) {
            return loggers.computeIfAbsent(name, CapturingLogger::new);
        }
    }

    /**
     * Reports every level as enabled, matching the {@code Level.ALL} capture the JUL-based tests
     * configured, and forwards each call to {@link LogCapture}. A {@code LogCapture} hook that
     * throws or blocks propagates out of the logging call exactly like a failing or blocked
     * backend, which is what the delivery-isolation tests exercise.
     */
    private static final class CapturingLogger extends AbstractLogger {
        private static final long serialVersionUID = 1L;

        CapturingLogger(String name) {
            this.name = name;
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isTraceEnabled(@Nullable Marker marker) {
            return true;
        }

        @Override
        public boolean isDebugEnabled(@Nullable Marker marker) {
            return true;
        }

        @Override
        public boolean isInfoEnabled(@Nullable Marker marker) {
            return true;
        }

        @Override
        public boolean isWarnEnabled(@Nullable Marker marker) {
            return true;
        }

        @Override
        public boolean isErrorEnabled(@Nullable Marker marker) {
            return true;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return CapturingLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(
                Level level,
                @Nullable Marker marker,
                String messagePattern,
                Object @Nullable [] arguments,
                @Nullable Throwable throwable) {
            LogCapture.publish(name, level, messagePattern, arguments, throwable);
        }
    }
}

package io.github.monadrome.parallelinscope;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Explicit OpenTelemetry instrumentation for task bodies submitted to this library.
 *
 * <p>Construction captures the complete current OTel {@link Context}. Each decorated invocation
 * creates one internal span with that context as its parent, makes the span current only while the
 * body runs, records thrown failures, and ends the span after the body has returned or thrown. The
 * adapter does not change cancellation, deadline, or body-exit semantics in the execution core.
 *
 * <p>Use a new instance at each logical submission boundary. Nested code that wants a nested span
 * should capture a new instance while its enclosing span is current.
 *
 * @since 0.4.0
 */
public final class OpenTelemetryTaskDecorator {
    private final Tracer tracer;
    private final Context parent;

    private OpenTelemetryTaskDecorator(Tracer tracer, Context parent) {
        this.tracer = Objects.requireNonNull(tracer, "tracer cannot be null");
        this.parent = Objects.requireNonNull(parent, "parent context cannot be null");
    }

    /**
     * Captures the current OTel context and uses {@code tracer} for every decorated body.
     *
     * @since 0.4.0
     */
    public static OpenTelemetryTaskDecorator capture(Tracer tracer) {
        return new OpenTelemetryTaskDecorator(tracer, Context.current());
    }

    /**
     * Captures the current context and obtains a tracer through OTel's global API/provider
     * discovery.
     *
     * @since 0.4.0
     */
    public static OpenTelemetryTaskDecorator global(String instrumentationName) {
        Validation.requireName(instrumentationName, "instrumentation name");
        return capture(GlobalOpenTelemetry.get().getTracer(instrumentationName));
    }

    /**
     * Decorates a function with a fixed span name.
     *
     * @since 0.4.0
     */
    public <T, R> Function<T, R> function(String spanName, Function<? super T, ? extends R> body) {
        Validation.requireName(spanName, "span name");
        Objects.requireNonNull(body, "body cannot be null");
        return value -> runUnchecked(spanName, () -> body.apply(value));
    }

    /**
     * Decorates a function whose span name is derived from its input.
     *
     * @since 0.4.0
     */
    public <T, R> Function<T, R> function(Function<? super T, String> spanName, Function<? super T, ? extends R> body) {
        Objects.requireNonNull(spanName, "span name function cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
        return value -> {
            String name = spanName.apply(value);
            Validation.requireName(name, "span name");
            return runUnchecked(name, () -> body.apply(value));
        };
    }

    /**
     * Decorates a checked-exception body with a fixed span name.
     *
     * @since 0.4.0
     */
    public <R> Callable<R> callable(String spanName, Callable<R> body) {
        Validation.requireName(spanName, "span name");
        Objects.requireNonNull(body, "body cannot be null");
        return () -> run(spanName, body);
    }

    private <R> R runUnchecked(String spanName, Callable<R> body) {
        try {
            return run(spanName, body);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception impossible) {
            throw new AssertionError("function body cannot throw a checked exception", impossible);
        }
    }

    private <R> R run(String spanName, Callable<R> body) throws Exception {
        Span span = tracer.spanBuilder(spanName)
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope ignored = parent.with(span).makeCurrent()) {
            return body.call();
        } catch (RuntimeException | Error failure) {
            recordFailure(span, failure);
            throw failure;
        } catch (Exception failure) {
            recordFailure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private static void recordFailure(Span span, Throwable failure) {
        span.recordException(failure);
        span.setStatus(StatusCode.ERROR);
    }
}

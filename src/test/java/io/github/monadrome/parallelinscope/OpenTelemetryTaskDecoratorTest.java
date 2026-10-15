package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OpenTelemetryTaskDecoratorTest {

    @Test
    void capturesCompleteContextAndRestoresItAfterSuccess() throws Exception {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(tracer.spanBuilder("load")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        ContextKey<String> key = ContextKey.named("request");
        Context parent = Context.current().with(key, "request-1");
        AtomicReference<Context> seen = new AtomicReference<>();

        try (Scope ignored = parent.makeCurrent()) {
            Callable<String> body = OpenTelemetryTaskDecorator.capture(tracer).callable("load", () -> {
                seen.set(Context.current());
                return "ok";
            });
            assertThat(body.call()).isEqualTo("ok");
            assertThat(Context.current()).isSameAs(parent);
        }

        Context observed = Objects.requireNonNull(seen.get());
        assertThat(observed.get(key)).isEqualTo("request-1");
        verify(builder).setParent(eq(parent));
        verify(span).end();
    }

    @Test
    void recordsAndRethrowsCheckedFailure() {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(tracer.spanBuilder("load")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        Exception failure = new Exception("boom");

        Callable<String> body = OpenTelemetryTaskDecorator.capture(tracer).callable("load", () -> {
            throw failure;
        });

        assertThatThrownBy(body::call).isSameAs(failure);
        verify(span).recordException(failure);
        verify(span).end();
    }

    @Test
    void recordsAndRethrowsRuntimeFailure() {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(tracer.spanBuilder("load")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        RuntimeException failure = new IllegalStateException("boom");

        Callable<String> body = OpenTelemetryTaskDecorator.capture(tracer).callable("load", () -> {
            throw failure;
        });

        assertThatThrownBy(body::call).isSameAs(failure);
        verify(span).recordException(failure);
        verify(span).end();
    }

    @Test
    void derivesSpanNamesFromInputs() {
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(tracer.spanBuilder("load-id")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);

        Function<String, String> body =
                OpenTelemetryTaskDecorator.capture(tracer).function(value -> "load-" + value, value -> value);

        assertThat(body.apply("id")).isEqualTo("id");
        verify(tracer).spanBuilder("load-id");
        verify(span).end();
    }

    @Test
    void globalFactoryUsesTheOtelProviderApi() {
        assertThat(OpenTelemetryTaskDecorator.global("parallel-in-scope-test")
                        .function("work", value -> value)
                        .apply("ok"))
                .isEqualTo("ok");
    }

    @Test
    void propagatesThroughTheManagedBatchBody() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(tracer.spanBuilder("load")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        ContextKey<String> key = ContextKey.named("request");
        Context parent = Context.current().with(key, "request-2");
        AtomicReference<Context> seen = new AtomicReference<>();

        try (Scope ignored = parent.makeCurrent()) {
            OpenTelemetryTaskDecorator otel = OpenTelemetryTaskDecorator.capture(tracer);
            TaskBatchResult<String> result = runtime.par(ParId.of("worker"))
                    .map(
                            Collections.singletonList("id"),
                            otel.function("load", id -> {
                                seen.set(Context.current());
                                return id;
                            }),
                            BatchOptions.timeout("load", Duration.ofSeconds(5)));
            assertThat(result.valuesOrThrow()).containsExactly("id");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            runtime.close();
            executor.shutdownNow();
        }

        assertThat(Objects.requireNonNull(seen.get()).get(key)).isEqualTo("request-2");
        verify(builder).setParent(eq(parent));
        verify(span).end();
    }

    @Test
    void exportsRealSdkSpansWithParentAndContextValues() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = provider.get("parallel-in-scope-test");
        ContextKey<String> key = ContextKey.named("request");
        Context parentContext = Context.current().with(key, "request-sdk");
        Span parent = tracer.spanBuilder("request").startSpan();
        Context expectedCurrent = parentContext.with(parent);

        try (Scope parentScope = expectedCurrent.makeCurrent()) {
            OpenTelemetryTaskDecorator otel = OpenTelemetryTaskDecorator.capture(tracer);
            assertThat(otel.callable("load", () -> {
                                assertThat(Context.current().get(key)).isEqualTo("request-sdk");
                                return "ok";
                            })
                            .call())
                    .isEqualTo("ok");
            assertThat(Context.current()).isSameAs(expectedCurrent);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            parent.end();
        }

        assertThat(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        provider.close();
        assertThat(spans).hasSize(2);
        SpanData request = spans.stream()
                .filter(span -> span.getName().equals("request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("request span was not exported"));
        SpanData load = spans.stream()
                .filter(span -> span.getName().equals("load"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("load span was not exported"));
        assertThat(load.getParentSpanId()).isEqualTo(request.getSpanId());
        assertThat(load.getStatus().getStatusCode().name()).isEqualTo("UNSET");
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContextExecutorsTest {

    private static final TransmittableThreadLocal<String> TTL = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Map<String, String>> MUTABLE_TTL = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<List<String>> COPYING_TTL =
            new TransmittableThreadLocal<List<String>>() {
                @Override
                public List<String> copy(List<String> parentValue) {
                    return new ArrayList<>(parentValue);
                }
            };

    @Test
    void registrationExecutorCapturesAtCreationInsteadOfCommandExecution() throws Exception {
        ExecutorService completionThread = Executors.newSingleThreadExecutor();
        try {
            TTL.set("registration");
            Executor direct = ContextExecutors.registrationContextExecutor();
            TTL.set("completion");
            AtomicReference<String> seen = new AtomicReference<>();

            Future<?> submitted = completionThread.submit(() -> direct.execute(() -> seen.set(TTL.get())));
            submitted.get();

            assertThat(seen).hasValue("registration");
            assertThat(TTL.get()).isEqualTo("completion");
        } finally {
            TTL.remove();
            completionThread.shutdownNow();
        }
    }

    @Test
    void restoresTheEnclosingContextAfterMutationAndFailure() {
        TTL.set("registration");
        Executor direct = ContextExecutors.registrationContextExecutor();
        TTL.set("caller");
        try {
            assertThatThrownBy(() -> direct.execute(() -> {
                        assertThat(TTL.get()).isEqualTo("registration");
                        TTL.set("command");
                        throw new IllegalStateException("boom");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
            assertThat(TTL.get()).isEqualTo("caller");
        } finally {
            TTL.remove();
        }
    }

    @Test
    void nestedExecutionRestoresTheOuterCapturedContext() {
        TTL.set("outer");
        Executor outer = ContextExecutors.registrationContextExecutor();
        TTL.set("inner");
        Executor inner = ContextExecutors.registrationContextExecutor();
        try {
            AtomicReference<String> afterInner = new AtomicReference<>();
            outer.execute(() -> {
                assertThat(TTL.get()).isEqualTo("outer");
                inner.execute(() -> assertThat(TTL.get()).isEqualTo("inner"));
                afterInner.set(TTL.get());
            });
            assertThat(afterInner).hasValue("outer");
            assertThat(TTL.get()).isEqualTo("inner");
        } finally {
            TTL.remove();
        }
    }

    @Test
    void rejectsNullCommands() {
        assertThatThrownBy(() -> ContextExecutors.directContextExecutor().execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("command cannot be null");
        assertThatThrownBy(() -> ContextExecutors.registrationContextExecutor().execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("command cannot be null");
    }

    @Test
    void hasStableDiagnosticNamesWithoutExposingTheCapturedContext() {
        assertThat(ContextExecutors.directContextExecutor()).hasToString("ContextExecutors.directContextExecutor()");
        assertThat(ContextExecutors.registrationContextExecutor())
                .hasToString("ContextExecutors.registrationContextExecutor()");
    }

    @Test
    void directContextExecutorCapturesTheCurrentContextForEachCommand() {
        Executor direct = ContextExecutors.directContextExecutor();
        try {
            TTL.set("first");
            AtomicReference<String> first = new AtomicReference<>();
            direct.execute(() -> first.set(TTL.get()));

            TTL.set("second");
            AtomicReference<String> second = new AtomicReference<>();
            direct.execute(() -> second.set(TTL.get()));

            assertThat(first).hasValue("first");
            assertThat(second).hasValue("second");
            assertThat(TTL.get()).isEqualTo("second");
        } finally {
            TTL.remove();
        }
    }

    @Test
    void directContextExecutorRunsTheCopierForEachCommandAndRestoresTheCallerBinding() {
        List<String> original = new ArrayList<>(Arrays.asList("a"));
        COPYING_TTL.set(original);
        Executor direct = ContextExecutors.directContextExecutor();
        try {
            AtomicReference<List<String>> first = new AtomicReference<>();
            direct.execute(() -> {
                first.set(COPYING_TTL.get());
                COPYING_TTL.get().add("first");
            });

            AtomicReference<List<String>> second = new AtomicReference<>();
            direct.execute(() -> second.set(COPYING_TTL.get()));

            assertThat(first.get()).containsExactly("a", "first");
            assertThat(second.get()).containsExactly("a");
            assertThat(second.get()).isNotSameAs(first.get());
            assertThat(COPYING_TTL.get()).isSameAs(original).containsExactly("a");
        } finally {
            COPYING_TTL.remove();
        }
    }

    @Test
    void directContextExecutorRestoresTheCallerBindingAfterFailure() {
        TTL.set("caller");
        Executor direct = ContextExecutors.directContextExecutor();
        try {
            assertThatThrownBy(() -> direct.execute(() -> {
                        assertThat(TTL.get()).isEqualTo("caller");
                        TTL.set("command");
                        throw new IllegalStateException("boom");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
            assertThat(TTL.get()).isEqualTo("caller");
        } finally {
            TTL.remove();
        }
    }

    @Test
    void oneCapturedExecutorCanBeUsedConcurrentlyWithoutCrossingCallerBindings() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            TTL.set("registration");
            Executor direct = ContextExecutors.registrationContextExecutor();
            Future<?> first = callers.submit(() -> runWithCallerValue(direct, "caller-one"));
            Future<?> second = callers.submit(() -> runWithCallerValue(direct, "caller-two"));
            first.get();
            second.get();
        } finally {
            TTL.remove();
            callers.shutdownNow();
        }
    }

    private static void runWithCallerValue(Executor direct, String callerValue) {
        TTL.set(callerValue);
        try {
            direct.execute(() -> assertThat(TTL.get()).isEqualTo("registration"));
            assertThat(TTL.get()).isEqualTo(callerValue);
        } finally {
            TTL.remove();
        }
    }

    @Test
    void restoresTheBindingWhenACommandReplacesTheTtlValue() {
        Map<String, String> original = new HashMap<>();
        original.put("trace", "request");
        MUTABLE_TTL.set(original);
        Executor direct = ContextExecutors.registrationContextExecutor();
        try {
            direct.execute(() -> MUTABLE_TTL.set(new HashMap<>()));
            assertThat(MUTABLE_TTL.get()).isSameAs(original);
        } finally {
            MUTABLE_TTL.remove();
        }
    }

    @Test
    void mutatingAReferencedMutableValueStillChangesTheSharedObject() {
        Map<String, String> context = new HashMap<>();
        context.put("trace", "request");
        MUTABLE_TTL.set(context);
        Executor direct = ContextExecutors.registrationContextExecutor();
        try {
            // CRR restores the binding, but the default TTL copier does not deep-copy this Map.
            direct.execute(() -> MUTABLE_TTL.get().put("trace", "changed"));
            assertThat(MUTABLE_TTL.get()).containsEntry("trace", "changed");
        } finally {
            MUTABLE_TTL.remove();
        }
    }
}

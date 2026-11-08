package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContextExecutorsTest {

    private static final TransmittableThreadLocal<String> TTL = new TransmittableThreadLocal<>();

    @Test
    void capturesAtExecutorCreationInsteadOfCommandExecution() throws Exception {
        ExecutorService completionThread = Executors.newSingleThreadExecutor();
        try {
            TTL.set("registration");
            Executor direct = ContextExecutors.directExecutor();
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
        Executor direct = ContextExecutors.directExecutor();
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
        Executor outer = ContextExecutors.directExecutor();
        TTL.set("inner");
        Executor inner = ContextExecutors.directExecutor();
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
        assertThatThrownBy(() -> ContextExecutors.directExecutor().execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("command cannot be null");
    }
}

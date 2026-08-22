package io.github.huatalk.parallelinscope.internal;

import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.context.ThreadRelay;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedCallableContextRestoreTest {
    @Test
    void nestedCallRestoresOuterBatchRelayAndCurrentCallable() throws Exception {
        ThreadRelay.clearCurrent();
        try {
            BatchExecutionContext outer = context("same-name");
            BatchExecutionContext inner = BatchExecutionContext.resolve(
                    GlobalExecutionPolicy.builder().build(),
                    ExecutionOptions.of("same-name").build(), 1, outer);
            AtomicReference<ScopedCallable<String>> outerReference = new AtomicReference<>();
            ScopedCallable<String> outerCallable = new ScopedCallable<>(
                    "outer", () -> {
                        assertThat(TaskScopeTl.getBatchExecutionContext()).isSameAs(outer);
                        assertThat(ThreadRelay.getCurrentCancellationToken()).isSameAs(outer.cancellationToken());
                        assertThat(ScopedCallable.current()).isSameAs(outerReference.get());

                        ScopedCallable<String> innerCallable = new ScopedCallable<>(
                                "inner", () -> {
                                    assertThat(TaskScopeTl.getBatchExecutionContext()).isSameAs(inner);
                                    assertThat(ScopedCallable.current()).isNotSameAs(outerReference.get());
                                    return "inner";
                                }, inner, null);
                        assertThat(innerCallable.call()).isEqualTo("inner");

                        assertThat(TaskScopeTl.getBatchExecutionContext()).isSameAs(outer);
                        assertThat(ThreadRelay.getCurrentCancellationToken()).isSameAs(outer.cancellationToken());
                        assertThat(ScopedCallable.current()).isSameAs(outerReference.get());
                        return "outer";
                    }, outer, null);
            outerReference.set(outerCallable);

            assertThat(outerCallable.call()).isEqualTo("outer");
            assertThat(TaskScopeTl.getBatchExecutionContext()).isNull();
            assertThat(ThreadRelay.getCurrentCancellationToken()).isNull();
            assertThat(ScopedCallable.current()).isNull();
        } finally {
            ThreadRelay.clearCurrent();
        }
    }

    private static BatchExecutionContext context(String name) {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().build(),
                ExecutionOptions.of(name).build(), 1, null);
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/** Verifies that group cancellation keeps its primary source identity. */
class TaskGroupScopeTerminationCauseTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final TaskKey<String> FIRST = TaskKey.named("first");
    private static final TaskKey<String> SECOND = TaskKey.named("second");

    @Test
    void directMemberCancellationKeepsTheMemberSource() {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("direct"), executor).build()) {
            SettableFuture<String> cancelled = SettableFuture.create();
            cancelled.cancel(false);
            SettableFuture<String> sibling = SettableFuture.create();

            TaskGroupResult<Void> result = runtime.group("member-scope", TIMEOUT)
                    .adopt(FIRST, cancelled)
                    .adopt(SECOND, sibling)
                    .runAll();

            CancellationReason reason = Verify.verifyNotNull(
                    Verify.verifyNotNull(result.termination().cause()).cancellationReason());
            assertThat(reason.kind()).isEqualTo(CancellationReason.Kind.CANCELLED_BY_MEMBER);
            assertThat(reason.source()).isEqualTo("first");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callerCancellationKeepsTheGroupSource() {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("direct"), executor).build()) {
            SettableFuture<String> pending = SettableFuture.create();

            TaskGroupResult<Void> result = runtime.group("caller-scope", TIMEOUT)
                    .cancelOn(Futures.immediateFuture("request done"))
                    .adopt(FIRST, pending)
                    .runAll();

            CancellationReason reason = Verify.verifyNotNull(
                    Verify.verifyNotNull(result.termination().cause()).cancellationReason());
            assertThat(reason.kind()).isEqualTo(CancellationReason.Kind.CANCELLED_BY_CALLER);
            assertThat(reason.source()).isEqualTo("caller-scope");
        } finally {
            executor.shutdownNow();
        }
    }
}

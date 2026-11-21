package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.google.common.util.concurrent.MoreExecutors;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Verifies failed CRR preparation releases unpublished tasks and cancellation-parent links. */
class ContextPreparationFailureTest {
    private static final ParId WORKERS = ParId.of("failed-context-workers");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** A later element capture failure releases earlier prepared futures before returning. */
    @Test
    void failedElementCaptureReleasesEarlierPreparations() throws Exception {
        assertFailedPreparation(
                2,
                3,
                runtime -> runtime.par(WORKERS)
                        .map(Arrays.asList(1, 2, 3), value -> value, BatchOptions.timeout("failed-batch", TIMEOUT)));
    }

    /** A terminal capture failure cancels the element unit as well as its terminal child. */
    @Test
    void failedTerminalCaptureReleasesElementPreparations() throws Exception {
        assertFailedPreparation(
                3,
                3,
                runtime -> runtime.par(WORKERS)
                        .mapAndCombine(
                                Arrays.asList(1, 2),
                                value -> value,
                                BatchOptions.timeout("failed-combine", TIMEOUT),
                                runtime.par(WORKERS),
                                List::size));
    }

    /** A failed group member releases earlier callables and the group's own parent link. */
    @Test
    void failedGroupCaptureReleasesMemberPreparations() throws Exception {
        assertFailedPreparation(
                2,
                3,
                runtime -> runtime.groupDraft("failed-group", TIMEOUT)
                        .par(TaskKey.<Integer>named("first"), runtime.par(WORKERS), () -> 1)
                        .par(TaskKey.<Integer>named("second"), runtime.par(WORKERS), () -> 2)
                        .par(TaskKey.<Integer>named("third"), runtime.par(WORKERS), () -> 3)
                        .submitAll());
    }

    /** Records real, unpublished preparation results without replacing their production behavior. */
    private static void assertFailedPreparation(int failAt, int taskCount, Consumer<ParRuntime> prepare)
            throws Exception {
        IllegalStateException failure = new IllegalStateException("capture failed");
        AtomicInteger captures = new AtomicInteger();
        ContextPropagation provider = () -> {
            if (captures.incrementAndGet() == failAt) {
                throw failure;
            }
            return ContextPropagation.none().capture();
        };
        List<TaskExecutionContext> contexts = new ArrayList<>();
        List<ExecutionPhaseHintFuture<?>> futures = new ArrayList<>();
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            TaskExecutionContext enclosing = PreparedStates.contextPreparation(runtime.par(WORKERS));
            try (MockedStatic<TaskSubmissions> recording = mockStatic(TaskSubmissions.class, invocation -> {
                if (!invocation.getMethod().getName().equals("prepare")) {
                    return invocation.callRealMethod();
                }
                contexts.add(invocation.getArgument(0));
                ExecutionPhaseHintFuture<?> future =
                        (ExecutionPhaseHintFuture<?>) checkNotNull(invocation.callRealMethod());
                futures.add(future);
                return future;
            })) {
                TaskExecutionContext previous = TaskExecutionContext.install(enclosing);
                try {
                    assertThatThrownBy(() -> prepare.accept(runtime)).isSameAs(failure);
                } finally {
                    TaskExecutionContext.restore(previous);
                }
            }
            assertThat(contexts).hasSize(failAt);
            assertThat(futures).hasSize(failAt - 1);
            for (ExecutionPhaseHintFuture<?> future : futures) {
                assertThat(future.isCancelled()).isTrue();
                assertThat(future.callableReleased()).isTrue();
                assertThat(future.state()).isEqualTo(TaskState.EXITED);
                TaskExitObservation<?> observation =
                        checkNotNull(future.observation()).view().get();
                assertThat(observation.termination().kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
                assertThat(observation.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
            }
            for (TaskExecutionContext context : contexts) {
                CancellationToken token = context.multiTaskContext().cancellationToken();
                assertThat(token.state().shouldInterruptCurrentThread()).isTrue();
                assertParentLinkReleased(token);
                TaskExecutionState executionState = checkNotNull(context.executionState());
                assertThat(executionState.isOutstanding()).isFalse();
                assertThat(executionState.terminal().isDone()).isTrue();
                ExecutionExitTracker tracker =
                        (ExecutionExitTracker) checkNotNull(readField(executionState, "tracker"));
                AtomicInteger outstanding = (AtomicInteger) checkNotNull(readField(tracker, "outstanding"));
                assertThat(outstanding).hasValue(taskCount - failAt);
                assertThat(tracker.isDone()).isEqualTo(taskCount == failAt);
            }
            CancellationToken owner = (CancellationToken)
                    checkNotNull(readField(contexts.get(0).multiTaskContext().cancellationToken(), "parent"));
            if (owner != enclosing.multiTaskContext().cancellationToken()) {
                assertThat(owner.state()).isEqualTo(CancellationToken.State.CANCELLED);
                assertParentLinkReleased(owner);
            }
            assertThat(enclosing.multiTaskContext().cancellationToken().state())
                    .isEqualTo(CancellationToken.State.RUNNING);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Checks the retention edge itself rather than relying on nondeterministic garbage collection. */
    private static void assertParentLinkReleased(CancellationToken token) throws Exception {
        Object link = checkNotNull(readField(token, "parentCancellationLink"));
        assertThat(readField(link, "child")).isNull();
    }

    /** Reads private retention edges without widening the production API for test access. */
    private static @Nullable Object readField(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}

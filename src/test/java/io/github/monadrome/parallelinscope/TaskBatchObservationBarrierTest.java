package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The lightweight observation barrier preserves publication, freezing, and optional-view semantics. */
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TaskBatchObservationBarrierTest {

    /** A terminal task still waits within the cleanup budget for its final observation. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finishWaitsForObservationPublication(boolean combined) throws Exception {
        SettableFuture<@Nullable Void> publication = SettableFuture.create();
        TaskBatch<Integer> batch = batch(publication, Duration.ofSeconds(5), snapshot());
        Task<Integer> terminal = (Task<Integer>) batch(Futures.immediateVoidFuture(), Duration.ZERO, snapshot())
                .results()
                .get(0);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch finishing = new CountDownLatch(1);
        try {
            Future<TaskBatchResult<Integer>> frozen = caller.submit(() -> {
                finishing.countDown();
                return combined ? batch.finishCombined(terminal).batchResult() : batch.finish();
            });
            assertThat(finishing.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> frozen.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            publication.set(null);
            TaskBatchResult<Integer> result = frozen.get(2, TimeUnit.SECONDS);
            assertThat(result.valuesOrThrow()).containsExactly(7);
            assertThat(result.completions()).doesNotContainNull();
            assertThat(result.completions().get(0).endTimeNanos()).isEqualTo(3);
        } finally {
            publication.set(null);
            caller.shutdownNow();
        }
    }

    /** Concurrent internal observers receive one read-only view with the original snapshot objects. */
    @Test
    void concurrentReadersShareOneReadOnlyObservationFuture() throws Exception {
        SettableFuture<@Nullable Void> publication = SettableFuture.create();
        TaskCompletion<Integer> snapshot = snapshot();
        TaskBatch<Integer> batch = batch(publication, Duration.ofSeconds(5), snapshot);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            List<Future<ListenableFuture<List<TaskCompletion<Integer>>>>> requests = new ArrayList<>();
            for (int reader = 0; reader < 12; reader++) {
                requests.add(callers.submit(batch::completionFuture));
            }
            ListenableFuture<List<TaskCompletion<Integer>>> view =
                    requests.get(0).get(2, TimeUnit.SECONDS);
            for (Future<ListenableFuture<List<TaskCompletion<Integer>>>> request : requests) {
                assertThat(request.get(2, TimeUnit.SECONDS)).isSameAs(view);
            }
            assertThat(view.cancel(true)).isFalse();
            publication.set(null);
            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(view.isDone()).isTrue();
            List<TaskCompletion<Integer>> observations = view.get(2, TimeUnit.SECONDS);
            assertThat(observations.get(0)).isSameAs(snapshot);
            assertThatThrownBy(observations::clear).isInstanceOf(UnsupportedOperationException.class);
        } finally {
            publication.set(null);
            callers.shutdownNow();
        }
    }

    /** A later publication cannot mutate a result frozen with a missing observation. */
    @Test
    void zeroGraceKeepsMissingObservationsFrozen() throws Exception {
        SettableFuture<@Nullable Void> publication = SettableFuture.create();
        TaskBatch<Integer> batch = batch(publication, Duration.ZERO, snapshot());
        TaskBatchResult<Integer> result = batch.finish();
        assertThat(result.completions()).containsExactly((TaskCompletion<Integer>) null);
        publication.set(null);
        assertThat(batch.completionFuture().get(2, TimeUnit.SECONDS)).hasSize(1);
        assertThat(result.completions()).containsExactly((TaskCompletion<Integer>) null);
    }

    /** Observation construction failures remain visible even when the barrier was already terminal. */
    @Test
    void failedObservationsStillFailFreezing() {
        TaskObservation<Integer> observation = TaskObservation.of(
                () -> {
                    throw new IllegalStateException("broken observation");
                },
                Futures.immediateVoidFuture());
        TaskBatch<Integer> batch = batch(observation, Duration.ZERO);
        assertThatThrownBy(batch::finish).isInstanceOf(AssertionError.class).hasRootCauseMessage("broken observation");
    }

    /** Creates an observation controlled independently from the already-successful execution future. */
    private static TaskBatch<Integer> batch(
            ListenableFuture<?> publication, Duration grace, TaskCompletion<Integer> snapshot) {
        return batch(TaskObservation.of(() -> snapshot, publication), grace);
    }

    /** Installs a controlled observation on a real attributed task view before running its body. */
    private static TaskBatch<Integer> batch(TaskObservation<Integer> observation, Duration grace) {
        CancellationToken token = CancellationToken.create();
        ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(() -> 7);
        future.observation(observation);
        Task<Integer> task = Task.attributedOnSettle("observed", token, future);
        future.run();
        return TaskBatch.of(
                BodyCompletionTracker.empty(),
                Futures.immediateVoidFuture(),
                Collections.singletonList(task),
                token,
                grace);
    }

    /** A successful record with distinct times makes publication and freezing assertions concrete. */
    private static TaskCompletion<Integer> snapshot() {
        return TaskCompletion.snapshot("observed", null, 0, 1, 2, 3, TaskOutcome.SUCCESS, 7, null);
    }
}

package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Completion contract of {@link TaskGroup#valuesFuture()} (design §5, §8): the aggregated values
 * future settles exactly once when the group converges and never stays pending, so a caller that
 * waits on it can always tell success, failure, and cancellation apart.
 *
 * <p>Two of these are regression tests for defects that the rest of the suite could not see: the
 * future is a read-only view (a caller's own {@code cancel} must not overwrite a successful
 * group's publication), and an {@link Error} escaping a caller's listener must not strand
 * {@code completionFuture()} — Guava's listener fan-out catches {@code RuntimeException} but not
 * {@code Error}.
 */
class TaskGroupValuesFutureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void aSuccessfulGroupPublishesItsOrderedValues() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Tuple2<String, Integer>, Void> group = global.groupDraft("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                .par("count", global.par(ParId.of("worker")), Integer.class, () -> 41)
                .submitAll()) {
            GroupValues<Tuple2<String, Integer>> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            assertThat(values.size()).isEqualTo(2);
            assertThat(values.typedValues()).isEqualTo(Tuple2.of("alice", 41));
            assertThat(values.valueAt(0)).isEqualTo("alice");
            assertThat(values.valueOf("count")).isEqualTo(41);
            // The values view names its slots in declaration order when printed, which is what a
            // caller logging a group result sees.
            assertThat(values).hasToString("GroupValues[user, count]");
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            // The documented ordering invariant: a done completion future implies a done values
            // future, so a reader of both never sees a converged group with pending values.
            assertThat(group.valuesFuture().isDone()).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aFailingMemberFailsTheValuesFutureWithThatFailureAsTheCause() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        IllegalStateException boom = new IllegalStateException("boom");
        try (TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> {
                    throw boom;
                })
                .submitAll()) {
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.USER_FAILURE);

            // Settled, not stranded: the failure reaches the caller as a cause, never as a hang.
            assertThat(group.valuesFuture().isDone()).isTrue();
            assertThatThrownBy(() -> group.valuesFuture().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(boom);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void valuesFollowDeclarationOrderNotCompletionOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch secondSettled = new CountDownLatch(1);
        TaskGroup<Tuple2<String, Integer>, Void> group = global.groupDraft("page", TIMEOUT)
                .par("first", global.par(ParId.of("worker")), String.class, () -> {
                    // Two workers, so this one blocks while the member declared after it finishes.
                    if (!secondSettled.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the second member never settled");
                    }
                    return "declared-first";
                })
                .par("second", global.par(ParId.of("worker")), Integer.class, () -> 42)
                .submitAll();
        try {
            // The order is forced by this thread observing the second member's *future* complete,
            // not by a latch its body counts down on its way out. Releasing from inside the second
            // body would only order the bodies: the first future could still settle first, and an
            // implementation that assembled values in completion order would pass.
            assertThat(group.futureOf("second", TypeToken.of(Integer.class)).get(5, TimeUnit.SECONDS))
                    .isEqualTo(42);
            secondSettled.countDown();
            GroupValues<Tuple2<String, Integer>> values = group.valuesFuture().get(5, TimeUnit.SECONDS);

            assertThat(values.valueAt(0)).isEqualTo("declared-first");
            assertThat(values.valueOf("second")).isEqualTo(42);
            assertThat(values.typedValues()).isEqualTo(Tuple2.of("declared-first", 42));
        } finally {
            secondSettled.countDown();
            group.close();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aFailingCombineFailsTheValuesFutureWithTheCombinesOwnFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        IllegalStateException boom = new IllegalStateException("combine boom");
        try (TaskGroup<String, String> group = global.groupDraft("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                    throw boom;
                })
                .submitAll()) {
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");

            // The terminal is not a member, so this is the case that proves the recorded failure is
            // looked up across both: a lookup that only consulted the member map would cancel the
            // values future here instead of failing it with the combine's own cause.
            assertThat(group.valuesFuture().isDone()).isTrue();
            assertThatThrownBy(() -> group.valuesFuture().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(boom);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aDirectGroupCancellationCancelsTheValuesFuture() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        started.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return "late";
                    })
                    .submitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            group.cancel();

            assertThat(group.valuesFuture().isCancelled()).isTrue();
            assertThatThrownBy(() -> group.valuesFuture().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(CancellationException.class);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aGroupDeadlineWithNoRecordedFailureCancelsTheValuesFuture() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", Duration.ofMillis(150))
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        release.await(10, TimeUnit.SECONDS);
                        return "late";
                    })
                    .submitAll();

            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            // A deadline is a cancellation, not a failure: there is no throwable to hand back.
            assertThat(group.valuesFuture().isCancelled()).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void anEmptyGroupPublishesAnEmptyValuesView() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Void, Void> group = global.groupDraft("empty", TIMEOUT).submitAll()) {
            GroupValues<Void> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            assertThat(values.size()).isZero();
            assertThat(values.typedValues()).isNull();
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void valuesFutureIsReadOnlyAndSurvivesACallersCancelAttempt() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        started.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return "value";
                    })
                    .submitAll();

            // Wait until the body is provably running, so the cancel below lands while the group is
            // still in flight. A cancel attempted after the group had already converged would be
            // refused by any future at all, including a raw sink, and the test would then pass
            // against the unfixed code purely because it lost the race.
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            // The group's own publication is not the caller's to cancel: the view refuses, exactly
            // like TaskGraphObservationScope.reportFuture(). Without that, a cancel here would win
            // the race against convergence and a group that finished SUCCESS would report
            // CancellationException from get().
            assertThat(group.valuesFuture().cancel(true)).isFalse();
            release.countDown();

            assertThat(group.valuesFuture().get(2, TimeUnit.SECONDS).typedValues())
                    .isEqualTo("value");
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void anErrorFromAValuesListenerDoesNotStrandTheCompletionFuture() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        started.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return "value";
                    })
                    .submitAll();
            // The listener must be in place before publication, and the body must be running, so the
            // Error is guaranteed to be raised on the converging thread instead of the registration
            // racing the publication.
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            group.valuesFuture()
                    .addListener(
                            () -> {
                                throw new AssertionError("listener boom");
                            },
                            directExecutor());
            release.countDown();

            // The value is published before the listeners run, so both facts must hold: the values
            // are readable, and convergence still publishes the completion future.
            assertThat(group.valuesFuture().get(2, TimeUnit.SECONDS).typedValues())
                    .isEqualTo("value");
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }
}

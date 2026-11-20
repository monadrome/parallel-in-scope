package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * Group failure loudness and the member-cancellation cascade: {@code orThrow()} rethrows the
 * recorded failure by kind, and directly cancelling one member reaches its siblings' tokens so the
 * whole group converges instead of stranding the cancelled member's peers.
 */
class TaskGroupCascadeAndLoudFailureTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void orThrowRethrowsAnUncheckedMemberFailureAsIs() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            TaskGroupResult<Void> result = global.group("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        throw boom;
                    })
                    .runAll();

            TerminationAssertions.assertScopeFailed(result.termination());
            assertThatThrownBy(result::orThrow).isSameAs(boom);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void orThrowWrapsACheckedMemberFailure() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        Exception checked = new Exception("checked");
        try {
            TaskGroupResult<Void> result = global.group("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        throw checked;
                    })
                    .runAll();

            assertThatThrownBy(result::orThrow)
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasCause(checked);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void orThrowRethrowsAMemberErrorAsIs() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        AssertionError fatal = new AssertionError("fatal");
        try {
            TaskGroupResult<Void> result = global.group("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        throw fatal;
                    })
                    .runAll();

            assertThatThrownBy(result::orThrow).isSameAs(fatal);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void orThrowSurfacesACancelledGroupAsCancellation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupResult<Void> result = global.group("page", Duration.ofMillis(100))
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        release.await(10, TimeUnit.SECONDS);
                        return "late";
                    })
                    .runAll();

            TerminationAssertions.assertScopeCancelled(result.termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
            assertThatThrownBy(result::orThrow)
                    .isInstanceOf(java.util.concurrent.CancellationException.class)
                    .hasMessageContaining("page");
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void theReportsOwnOrThrowFollowsTheSameLoudnessRule() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        IllegalStateException boom = new IllegalStateException("boom");
        AssertionError fatal = new AssertionError("fatal");
        Exception checked = new Exception("checked");
        try {
            TaskGroupReport unchecked = global.groupDraft("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        throw boom;
                    })
                    .submitAll()
                    .convergenceFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThatThrownBy(unchecked::orThrow).isSameAs(boom);

            TaskGroupReport errored = global.groupDraft("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        throw fatal;
                    })
                    .submitAll()
                    .convergenceFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThatThrownBy(errored::orThrow).isSameAs(fatal);

            TaskGroupReport wrapped = global.groupDraft("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        throw checked;
                    })
                    .submitAll()
                    .convergenceFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThatThrownBy(wrapped::orThrow)
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasCause(checked);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void theReportsOwnOrThrowSurfacesACancellationShape() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupReport timedOut = global.groupDraft("page", Duration.ofMillis(100))
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        release.await(10, TimeUnit.SECONDS);
                        return "late";
                    })
                    .submitAll()
                    .convergenceFuture()
                    .get(5, TimeUnit.SECONDS);

            TerminationAssertions.assertScopeCancelled(
                    timedOut.termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
            assertThatThrownBy(timedOut::orThrow)
                    .isInstanceOf(java.util.concurrent.CancellationException.class)
                    .hasMessageContaining("page");
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void directlyCancellingAMemberCascadesToItsSiblingTokens() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("cascade", TIMEOUT)
                    .par(TaskKey.<Integer>named("first"), global.par(ParId.of("worker")), () -> {
                        bothStarted.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .par(TaskKey.<Integer>named("second"), global.par(ParId.of("worker")), () -> {
                        bothStarted.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 2;
                    })
                    .submitAll();
            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(group.futureOf("first").cancel(true)).isTrue();

            // The cascade cancels the sibling's token, which cancels and interrupts its parked
            // execution: the group converges instead of waiting for the untouched member.
            Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(group.futureOf("second").isDone()).isTrue();
                assertThat(group.futureOf("second").isCancelled()).isTrue();
            });
            TaskGroupReport report = group.convergenceFuture().get(5, TimeUnit.SECONDS);
            assertThat(report.termination().kind()).isNotEqualTo(ScopeTermination.Kind.SUCCESS);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aValuesPublicationFailureIsLoggedSeverely() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LogCapture capture = LogCapture.attach(TaskGroup.class);
        try {
            TaskGroup<Void> group = global.groupDraft("page", TIMEOUT)
                    .par(TaskKey.<String>named("user"), global.par(ParId.of("worker")), () -> {
                        started.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return "value";
                    })
                    .submitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            group.valuesFuture()
                    .addListener(
                            () -> {
                                throw new AssertionError("listener boom");
                            },
                            directExecutor());
            release.countDown();

            // Convergence survives the broken listener, and the publication failure leaves an
            // error-level diagnostic naming the group.
            TerminationAssertions.assertScopeSucceeded(
                    group.convergenceFuture().get(5, TimeUnit.SECONDS).termination());
            assertThat(capture.events()).anySatisfy(record -> {
                assertThat(record.level()).isEqualTo(Level.ERROR);
                assertThat(record.message()).contains("page");
            });
        } finally {
            release.countDown();
            capture.close();
            global.close();
            executor.shutdownNow();
        }
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link TaskFuture} view the library delivers: delivery completeness,
 * outcome attribution, race stability, pre-submission identity, delegation transparency, and
 * deadline reporting.
 */
class TaskFutureTest {
    private static final TaskKey<String> KEY_USER_STRING = TaskKey.named("user");
    private static final TaskKey<String> KEY_SLOW_STRING = TaskKey.named("slow");
    private static final TaskKey<String> KEY_INHERITED_STRING = TaskKey.named("inherited");
    private static final TaskKey<String> KEY_TIGHTER_STRING = TaskKey.named("tighter");

    private static final Duration SCOPE_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void batchDeliversEveryElementAsATaskFutureInsideAndOutsideTheWindow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("first", "second", "third"),
                            item -> hold(block, item),
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT).parallelism(1));

            // Element 0 occupies the only slot; elements 1 and 2 are still waiting for one, yet
            // already answer with their task identity.
            assertThat(batch.results()).allSatisfy(future -> assertThat(future).isNotNull());
            TaskFuture<String> pending = batch.results().get(2);
            assertThat(pending.taskName()).isEqualTo("orders");
            TerminationAssertions.assertPending(pending.termination());
            assertThat(pending.deadlineNanos()).isNotEqualTo(Long.MAX_VALUE);

            block.countDown();
            assertThat(batch.results())
                    .extracting(future -> future.get(2, TimeUnit.SECONDS))
                    .containsExactly("first", "second", "third");
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void batchRejectedAtSubmitDeliversIdentifiedTaskFutures() {
        ExecutorService rejected = Executors.newSingleThreadExecutor();
        rejected.shutdownNow();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), rejected).build();
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("first", "second"),
                            item -> item,
                            BatchOptions.timeout("rejected", SCOPE_TIMEOUT)
                                    .parallelism(2)
                                    .taskType(TaskType.BLOCKING));

            assertThat(batch.results()).allSatisfy(future -> assertThat(future).isNotNull());
            for (TaskFuture<String> element : batch.results()) {
                assertThat(element.taskName()).isEqualTo("rejected");
                TerminationAssertions.assertSubmissionFailure(element.termination());
                assertThat(element.failure()).isInstanceOf(SubmissionException.class);
            }
            assertThat(batch.report().terminationCounts())
                    .containsOnlyKeys(TaskTermination.Kind.FAILED)
                    .containsEntry(TaskTermination.Kind.FAILED, 2);
        } finally {
            global.close();
        }
    }

    @Test
    void groupDeliversMembersCombineAndCompletionAsTaskFutures() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskKey<Integer> orders = TaskKey.named("orders");
            TaskGroup<String> group = global.groupDraft("page", SCOPE_TIMEOUT)
                    .par(KEY_USER_STRING, global.par(ParId.of("worker")), memberOptions(), () -> "alice")
                    .par(orders, global.par(ParId.of("worker")), memberOptions(), () -> 7)
                    .combine(TaskKey.<String>named("assemble"), global.par(ParId.of("worker")), values -> {
                        return values.valueOf(KEY_USER_STRING) + ":" + values.valueOf(orders);
                    })
                    .submitAll();

            TaskFuture<String> userFuture = group.futureOf(KEY_USER_STRING);
            assertThat(userFuture).isInstanceOf(TaskFuture.class);
            assertThat(userFuture.taskName()).isEqualTo("user");
            assertThat(group.terminalFuture().orElseThrow(() -> new AssertionError("no combine declared")))
                    .isInstanceOf(TaskFuture.class);
            assertThat(group.findMember("user")).contains(userFuture);
            assertThat(group.members().values())
                    .allSatisfy(future -> assertThat(future).isNotNull());
            assertThat(group.convergenceFuture()).isInstanceOf(TaskFuture.class);
            TerminationAssertions.assertScopeSucceeded(
                    group.convergenceFuture().get(2, TimeUnit.SECONDS).termination());
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void submitCancellerStaysAPlainFuture() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("first", "second"),
                            item -> hold(block, item),
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT).parallelism(1));

            assertThat(batch.submitCanceller()).isNotInstanceOf(TaskFuture.class);
            assertThat(batch.results().get(1)).isInstanceOf(TaskFuture.class);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void succeededTaskReadsSuccessWithoutAFailure() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> "done",
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT));

            TaskFuture<String> element = batch.results().get(0);
            assertThat(element.get(2, TimeUnit.SECONDS)).isEqualTo("done");
            TerminationAssertions.assertSuccess(element.termination());
            assertThat(element.failure()).isNull();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void failedTaskReadsUserFailureAndExposesTheCause() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> {
                                throw boom;
                            },
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT));

            TaskFuture<String> element = batch.results().get(0);
            await().atMost(2, TimeUnit.SECONDS).until(element::isDone);
            TerminationAssertions.assertUserFailure(element.termination());
            assertThat(element.failure()).isSameAs(boom);
            assertThatThrownBy(element::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(boom);
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void aMemberCancelledDirectlyKeepsItsInitiatorAttributionInTheSnapshot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("page", SCOPE_TIMEOUT)
                    .par(
                            KEY_SLOW_STRING,
                            global.par(ParId.of("worker")),
                            memberOptions(),
                            () -> startedThenHold(started, block, "never"))
                    .submitAll();
            TaskFuture<String> member = group.futureOf(KEY_SLOW_STRING);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(member.cancel(true)).isTrue();
            TaskGroupReport snapshot = group.convergenceFuture().get(2, TimeUnit.SECONDS);

            // The member snapshot records the reason before the group cancels itself for the other
            // members, so it keeps the initiating member distinct from the fall-out.
            TerminationAssertions.assertCancelled(
                    Objects.requireNonNull(snapshot.members().get("slow")).termination(),
                    CancellationReason.Kind.CANCELLED_BY_CALLER);
            // The group-level scope termination is the post-hoc view: the member's direct
            // cancellation ended the group, so the scope records the member as its primary cause.
            TerminationAssertions.assertScopeCancelled(
                    snapshot.termination(), CancellationReason.Kind.CANCELLED_BY_MEMBER);
            // The member view's termination is frozen at settle: it keeps the direct-cancellation
            // attribution even after the group's own cancel lands on the token chain.
            TerminationAssertions.assertCancelled(member.termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void groupCancellationReadsGroupCancelledOnEveryMember() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("page", SCOPE_TIMEOUT)
                    .par(
                            TaskKey.<String>named("first"),
                            global.par(ParId.of("worker")),
                            memberOptions(),
                            () -> startedThenHold(started, block, "never"))
                    .par(
                            TaskKey.<String>named("second"),
                            global.par(ParId.of("worker")),
                            memberOptions(),
                            () -> startedThenHold(started, block, "never"))
                    .submitAll();

            group.cancel();
            TerminationAssertions.assertScopeCancelled(
                    group.convergenceFuture().get(2, TimeUnit.SECONDS).termination(),
                    CancellationReason.Kind.CANCELLED_BY_CALLER);

            TerminationAssertions.assertCancelled(
                    group.findMember("first").get().termination(), CancellationReason.Kind.CANCELLED_BY_PARENT_SCOPE);
            TerminationAssertions.assertCancelled(
                    group.findMember("second").get().termination(), CancellationReason.Kind.CANCELLED_BY_PARENT_SCOPE);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void siblingFailureReadsFailFastOnTheCancelledElement() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("boom", "victim"),
                            item -> "boom".equals(item) ? boom() : hold(block, item),
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT).parallelism(2));

            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> batch.results().get(1).isDone());
            TerminationAssertions.assertUserFailure(batch.results().get(0).termination());
            TerminationAssertions.assertCancelled(
                    batch.results().get(1).termination(), CancellationReason.Kind.ABORTED_BY_FAIL_FAST);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void expiredDeadlineReadsTimeoutAndClampsTheRemainingBudget() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> hold(block, item),
                            BatchOptions.timeout("orders", Duration.ofMillis(50)));

            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> batch.results().get(0).isDone());
            TaskFuture<String> element = batch.results().get(0);
            TerminationAssertions.assertCancelled(element.termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
            assertThat(element.remaining()).isEqualTo(Duration.ZERO);
            assertThat(element.failure()).isNull();
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void nestedScopeCancellationReadsTheOriginatingCause() throws Exception {
        ExecutorService outerPool = Executors.newSingleThreadExecutor();
        ExecutorService innerPool = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outerPool)
                .register(ParId.of("inner"), innerPool)
                .build();
        AtomicReference<CancellationToken> outerToken = new AtomicReference<>();
        AtomicReference<TaskFuture<String>> innerMember = new AtomicReference<>();
        AtomicReference<TaskGroup<?>> innerGroup = new AtomicReference<>();
        CountDownLatch memberStarted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        try {
            global.par(ParId.of("outer"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            ignored -> {
                                outerToken.set(Objects.requireNonNull(TaskExecutionContext.current())
                                        .multiTaskContext()
                                        .cancellationToken());
                                TaskGroup<Void> group = global.groupDraft("inner", SCOPE_TIMEOUT)
                                        .par(
                                                KEY_SLOW_STRING,
                                                global.par(ParId.of("inner")),
                                                memberOptions(),
                                                () -> startedThenHold(memberStarted, block, "never"))
                                        .submitAll();
                                innerGroup.set(group);
                                innerMember.set(group.futureOf(KEY_SLOW_STRING));
                                return awaitQuietly(group.convergenceFuture()) == null ? "cancelled" : "done";
                            },
                            BatchOptions.timeout("outer", SCOPE_TIMEOUT));

            assertThat(memberStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Objects.requireNonNull(outerToken.get()).cancel();

            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> Objects.requireNonNull(innerGroup.get())
                            .convergenceFuture()
                            .isDone());
            // The member token only learned about the cancellation by propagation, so the reported
            // cause is the originating state at the root of the chain, not the propagation link.
            TerminationAssertions.assertCancelled(
                    Objects.requireNonNull(innerMember.get()).termination(),
                    CancellationReason.Kind.CANCELLED_BY_PARENT_SCOPE);
        } finally {
            block.countDown();
            global.close();
            outerPool.shutdownNow();
            innerPool.shutdownNow();
        }
    }

    @Test
    void aCancellationSignalRacingTheCascadeIsAttributedToTheDeadline() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> {
                                try {
                                    block.await();
                                } catch (InterruptedException interrupted) {
                                    // Report the observed cancellation as a failure instead of
                                    // letting the interrupt surface: this checkpoint races the
                                    // cascade cancel on the element future, and must be attributed
                                    // to the deadline either way.
                                    Thread.currentThread().interrupt();
                                    throw new CancellationException("checkpoint observed cancellation");
                                }
                                return item;
                            },
                            BatchOptions.timeout("orders", Duration.ofMillis(50)));

            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> batch.results().get(0).isDone());
            TerminationAssertions.assertCancelled(
                    batch.results().get(0).termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void recordedSuccessSurvivesALaterDeadlineCommit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList("fast", "slow"),
                            item -> "fast".equals(item) ? item : hold(block, item),
                            BatchOptions.timeout("orders", Duration.ofMillis(60))
                                    .parallelism(2));

            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo("fast");
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> batch.results().get(1).isDone());
            TerminationAssertions.assertCancelled(
                    batch.results().get(1).termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
            TerminationAssertions.assertSuccess(batch.results().get(0).termination());
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void terminalOutcomeIsStableAcrossRepeatedReads() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> hold(block, item),
                            BatchOptions.timeout("orders", Duration.ofMillis(50)));
            TaskFuture<String> element = batch.results().get(0);

            TaskTermination<String> terminal = null;
            boolean regressed = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                TaskTermination<String> observed = element.termination();
                if (observed == null) {
                    regressed = terminal != null;
                } else {
                    terminal = observed;
                    if (element.isDone()) break;
                }
            }

            assertThat(regressed).isFalse();
            assertThat(element.isDone()).isTrue();
            assertThat(element.termination()).isNotNull();
            TerminationAssertions.assertCancelled(element.termination(), CancellationReason.Kind.DEADLINE_EXCEEDED);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    /** A prepared future with no observation and no execution slot, standing in for a batch element. */
    private static <V> ExecutionPhaseHintFuture<V> preparedFuture(Callable<V> taskCode) {
        return ExecutionPhaseHintFuture.create(taskCode);
    }

    @Test
    void stateReadsPendingAndExitedFromAPlainDelegate() {
        SettableFuture<String> delegate = SettableFuture.create();
        Task<String> view = ordersTask(delegate);
        assertThat(view.state()).isEqualTo(TaskState.PENDING);
        delegate.set("ok");
        assertThat(view.state()).isEqualTo(TaskState.EXITED);
    }

    @Test
    void unsubmittedElementDeliversListenersRegisteredBeforeItRuns() {
        ExecutionPhaseHintFuture<String> prepared = preparedFuture(() -> "done");
        Task<String> element = ordersTask(prepared);
        AtomicReference<String> observed = new AtomicReference<>();

        // The view is the element's final handle from creation on, so a listener registered before
        // anything is submitted fires with the executed outcome — no bind step swaps the delegate.
        element.addListener(
                () -> observed.set(com.google.common.base.Verify.verifyNotNull(element.termination())
                        .kind()
                        .name()),
                Runnable::run);
        TerminationAssertions.assertPending(element.termination());

        prepared.run();

        assertThat(observed.get()).isEqualTo("SUCCESS");
        TerminationAssertions.assertSuccess(element.termination());
    }

    @Test
    void cancellingAnElementBeforeSubmissionKeepsItsExecutionUnentered() {
        AtomicBoolean executionRan = new AtomicBoolean();
        ExecutionPhaseHintFuture<String> prepared = preparedFuture(() -> {
            executionRan.set(true);
            return "done";
        });
        Task<String> element = ordersTask(prepared);

        assertThat(element.cancel(true)).isTrue();
        // Cancelling the view forwards to the prepared future: the executor may still invoke it, and
        // the phase claim is what keeps the execution unentered.
        assertThat(prepared.isCancelled()).isTrue();
        prepared.run();

        assertThat(executionRan).isFalse();
        TerminationAssertions.assertCancelled(element.termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
    }

    @Test
    void submissionFailureAndCancellationAttributeTheirOwnCause() {
        ExecutionPhaseHintFuture<String> rejected = preparedFuture(() -> "unreachable");
        ExecutionPhaseHintFuture<String> cancelled = preparedFuture(() -> "unreachable");
        Task<String> rejectedView = ordersTask(rejected);
        Task<String> cancelledView = ordersTask(cancelled);

        assertThat(rejected.claimSubmissionFailure(new InterruptedException("submitter interrupted")))
                .isTrue();
        rejected.settleSubmissionFailure();
        cancelled.skipExecution();
        cancelled.cancel(true);

        TerminationAssertions.assertSubmissionFailure(rejectedView.termination());
        assertThat(rejectedView.failure())
                .isInstanceOf(SubmissionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(cancelledView.isCancelled()).isTrue();
        TerminationAssertions.assertCancelled(cancelledView.termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
    }

    @Test
    void aClaimedSubmissionFailureOutranksACascadeCancellationThatRacesIt() {
        ExecutionPhaseHintFuture<String> prepared = preparedFuture(() -> "unreachable");
        Task<String> element = ordersTask(prepared);

        // The batch claims every element on a handoff failure, then settles them. A fail-fast
        // cascade fired by an earlier settle can cancel this element in between; its recorded
        // attribution must still win, or the batch would report a cancellation instead of the
        // submission failure that actually ended it.
        assertThat(prepared.claimSubmissionFailure(new IllegalStateException("handoff broken")))
                .isTrue();
        prepared.cancel(true);

        TerminationAssertions.assertSubmissionFailure(element.termination());
        assertThat(element.failure())
                .isInstanceOf(SubmissionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelInterruptsTheWorkerThroughTheTaskView() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> {
                                started.countDown();
                                try {
                                    Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                                } catch (InterruptedException observed) {
                                    interrupted.countDown();
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(observed);
                                }
                                return item;
                            },
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(batch.results().get(0).cancel(true)).isTrue();

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(batch.results().get(0).isCancelled()).isTrue();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void listenersRunOnTheSuppliedExecutor() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "task-worker"));
        ExecutorService listeners =
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "listener-thread"));
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskBatch<String> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList("x"),
                            item -> "done",
                            BatchOptions.timeout("orders", SCOPE_TIMEOUT));
            TaskFuture<String> element = batch.results().get(0);
            AtomicReference<String> listenerThread = new AtomicReference<>();

            element.addListener(() -> listenerThread.set(Thread.currentThread().getName()), listeners);

            await().atMost(2, TimeUnit.SECONDS).until(() -> listenerThread.get() != null);
            assertThat(listenerThread.get()).isEqualTo("listener-thread");
        } finally {
            global.close();
            pool.shutdownNow();
            listeners.shutdownNow();
        }
    }

    @Test
    void theTaskViewIsStillAPlainListenableFuture() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2, 3), item -> item + 1, BatchOptions.timeout("orders", SCOPE_TIMEOUT));
            List<ListenableFuture<Integer>> asPlainFutures = new ArrayList<>(batch.results());

            assertThat(Futures.allAsList(asPlainFutures).get(2, TimeUnit.SECONDS))
                    .containsExactly(2, 3, 4);
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void deadlineAndRemainingBudgetComeFromTheOwningScope() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch block = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("page", SCOPE_TIMEOUT)
                    .par(
                            KEY_INHERITED_STRING,
                            global.par(ParId.of("worker")),
                            TaskOptions.inheritTimeout(),
                            () -> hold(block, "x"))
                    .par(
                            KEY_TIGHTER_STRING,
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(200)),
                            () -> hold(block, "y"))
                    .submitAll();

            TaskFuture<String> inheritedFuture = group.futureOf(KEY_INHERITED_STRING);
            TaskFuture<String> tighterFuture = group.futureOf(KEY_TIGHTER_STRING);
            long groupDeadline = group.convergenceFuture().deadlineNanos();

            assertThat(inheritedFuture.deadlineNanos()).isEqualTo(groupDeadline);
            assertThat(tighterFuture.deadlineNanos()).isLessThan(groupDeadline);
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).deadlineNanos())
                    .isEqualTo(groupDeadline);

            Duration before = inheritedFuture.remaining();
            Thread.sleep(5);
            assertThat(inheritedFuture.remaining()).isLessThanOrEqualTo(before);
        } finally {
            block.countDown();
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void packagePrivateFluentDerivationKeepsTheTaskView() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CancellationToken token = new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        try {
            Task<String> origin = Task.of("orders", token, Futures.immediateFuture("4"));
            Task<Integer> transformed = origin.transform(value -> Integer.valueOf(value) + 1, Runnable::run);
            Task<String> recovered = Task.of(
                            "orders", token, Futures.<String>immediateFailedFuture(new IllegalStateException("boom")))
                    .catching(IllegalStateException.class, failure -> "fallback", Runnable::run);
            Task<String> bounded = Task.of("orders", token, SettableFuture.<String>create())
                    .withTimeout(Duration.ofMillis(20), scheduler);

            // The derived shell keeps the name and token of the task that produced it, and stays a
            // Task rather than decaying into a plain FluentFuture.
            assertThat(transformed.taskName()).isEqualTo("orders");
            assertThat(transformed.deadlineNanos()).isEqualTo(origin.deadlineNanos());
            assertThat(transformed.toString()).doesNotContain("placeholder=");
            assertThat(transformed.get(2, TimeUnit.SECONDS)).isEqualTo(5);
            TerminationAssertions.assertSuccess(transformed.termination());
            assertThat(recovered.get(2, TimeUnit.SECONDS)).isEqualTo("fallback");
            assertThatThrownBy(() -> bounded.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void toStringDescribesTheTaskForDiagnostics() {
        Task<String> succeeded = ordersTask(Futures.immediateFuture("done"));
        Task<String> timed = Task.of(
                "orders",
                new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)),
                SettableFuture.<String>create());
        Task<String> failed = Task.of(
                "orders", new CancellationToken(), Futures.immediateFailedFuture(new IllegalStateException("boom")));
        Task<String> cancelled = ordersTask(SettableFuture.<String>create());
        cancelled.cancel(false);

        assertThat(succeeded.toString())
                .isEqualTo("Task@" + Integer.toHexString(System.identityHashCode(succeeded))
                        + "[status=SUCCESS, result=[String@"
                        + Integer.toHexString(System.identityHashCode("done")) + "], task='orders']");
        // The pending branch carries the state hint but no clock-dependent remaining budget.
        assertThat(timed.toString())
                .isEqualTo("Task@" + Integer.toHexString(System.identityHashCode(timed))
                        + "[status=PENDING, taskState=PENDING, task='orders']")
                .doesNotContain("remaining");
        assertThat(failed.toString())
                .isEqualTo("Task@" + Integer.toHexString(System.identityHashCode(failed))
                        + "[status=FAILURE, cause=[java.lang.IllegalStateException], task='orders']");
        assertThat(cancelled.toString())
                .isEqualTo("Task@" + Integer.toHexString(System.identityHashCode(cancelled))
                        + "[status=CANCELLED, task='orders']");
    }

    @Test
    void toStringRendersASelfReferentialResultWithoutRecursing() {
        SettableFuture<Object> delegate = SettableFuture.create();
        Task<Object> task = Task.of("self", new CancellationToken(), delegate);

        delegate.set(task);

        assertThat(task.toString()).contains("status=SUCCESS, result=[this future], task='self'");
    }

    @Test
    void toStringRedactsTheFailureMessage() {
        Task<String> secretBearing = Task.of(
                "orders",
                new CancellationToken(),
                Futures.immediateFailedFuture(new IllegalStateException("credentials=hunter2")));

        assertThat(secretBearing.toString())
                .contains("status=FAILURE, cause=[java.lang.IllegalStateException]")
                .doesNotContain("hunter2");
    }

    @Test
    void toStringDegradesToUnknownWhenReadingTheOutcomeThrows() {
        // Wrapping the failure in an ExecutionException renders its cause (JDK behavior), so a
        // cause whose toString throws cannot reach the FAILURE branch; Guava's UNKNOWN branch keeps
        // the diagnostic itself from throwing.
        Task<String> unrenderable = ordersTask(Futures.immediateFailedFuture(new RuntimeException("boom") {
            @Override
            public String toString() {
                throw new AssertionError("the cause's toString may throw");
            }
        }));

        assertThat(unrenderable.toString())
                .isEqualTo("Task@" + Integer.toHexString(System.identityHashCode(unrenderable))
                        + "[status=UNKNOWN, cause=[class java.lang.AssertionError thrown from get()], task='orders']");
    }

    @Test
    void toStringRedoesTheTerminalBranchWhenCompletionLandsDuringRendering() {
        SettableFuture<String> sink = SettableFuture.create();
        AtomicInteger isDoneReads = new AtomicInteger();
        ListenableFuture<String> settlingMidRender = new ForwardingListenableFuture<String>() {
            @Override
            protected ListenableFuture<String> delegate() {
                return sink;
            }

            @Override
            public boolean isDone() {
                // The second read is the pending branch's task-state hint: settle inside the
                // window between the done check and the hint read. Without Guava's
                // truncate-and-redo the envelope renders the contradictory status=PENDING,
                // taskState=EXITED.
                if (isDoneReads.incrementAndGet() == 2) {
                    sink.set("done");
                }
                return super.isDone();
            }
        };
        Task<String> task = ordersTask(settlingMidRender);

        assertThat(task.toString())
                .isEqualTo("Task@" + Integer.toHexString(System.identityHashCode(task))
                        + "[status=SUCCESS, result=[String@"
                        + Integer.toHexString(System.identityHashCode("done")) + "], task='orders']");
    }

    @Test
    void completionFutureRendersItsOwnReadOnlyEnvelope() {
        Task<String> pending = ordersTask(SettableFuture.<String>create());
        ListenableFuture<TaskExitObservation<String>> pendingView = pending.completionFuture();
        assertThat(pendingView.toString())
                .isEqualTo("ReadOnlyView@" + Integer.toHexString(System.identityHashCode(pendingView))
                        + "[status=PENDING]");
        assertThat(pendingView.cancel(true)).isFalse();

        Task<String> settled = ordersTask(Futures.immediateFuture("done"));
        ListenableFuture<TaskExitObservation<String>> settledView = settled.completionFuture();
        assertThat(settledView.toString())
                .isEqualTo("ReadOnlyView@" + Integer.toHexString(System.identityHashCode(settledView))
                        + "[status=SUCCESS, result=[TaskExitObservation@"
                        + Integer.toHexString(System.identityHashCode(Futures.getUnchecked(settledView))) + "]]");
    }

    @Test
    void toStringRendersCancelledWhenOnlyTheOutcomeReadSeesTheCancellation() {
        SettableFuture<String> cancelled = SettableFuture.create();
        cancelled.cancel(false);
        ListenableFuture<String> cancellationVisibleOnlyToGet = new ForwardingListenableFuture<String>() {
            @Override
            protected ListenableFuture<String> delegate() {
                return cancelled;
            }

            @Override
            public boolean isCancelled() {
                // The shape of a cancellation landing after the envelope's isCancelled() check:
                // the read-only checks report a plain done future and only the outcome read meets
                // the CancellationException. The terminal branch renders CANCELLED, not UNKNOWN.
                return false;
            }
        };

        assertThat(ordersTask(cancellationVisibleOnlyToGet).toString()).contains("status=CANCELLED");
    }

    @Test
    void readOnlyViewRendersCancelledWhenItsSinkIsCancelled() {
        SettableFuture<String> sink = SettableFuture.create();
        ListenableFuture<String> view = TaskObservation.readOnly(sink);

        sink.cancel(false);

        assertThat(view.toString())
                .isEqualTo("ReadOnlyView@" + Integer.toHexString(System.identityHashCode(view)) + "[status=CANCELLED]");
    }

    @Test
    void terminationReadsSuccessEvenWhenTheCallingThreadIsInterrupted() {
        Task<String> succeeded = ordersTask(Futures.immediateFuture("done"));

        Thread.currentThread().interrupt();
        try {
            TerminationAssertions.assertSuccess(succeeded.termination());
            assertThat(succeeded.failure()).isNull();
        } finally {
            Thread.interrupted();
        }
    }

    private static TaskOptions memberOptions() {
        return TaskOptions.timeout(SCOPE_TIMEOUT);
    }

    /** Blocks the worker until the latch opens or the thread is interrupted, then returns {@code value}. */
    private static <T> T hold(CountDownLatch latch, T value) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding", interrupted);
        }
        return value;
    }

    /** Signals that the worker reached this point, then blocks until the latch opens. */
    private static <T> T startedThenHold(CountDownLatch started, CountDownLatch latch, T value) {
        started.countDown();
        return hold(latch, value);
    }

    private static String boom() {
        throw new IllegalStateException("boom");
    }

    /** A task view named {@code orders} with a fresh unbounded token over {@code delegate}. */
    private static Task<String> ordersTask(ListenableFuture<String> delegate) {
        return Task.of("orders", new CancellationToken(), delegate);
    }

    private static <T> T awaitQuietly(ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

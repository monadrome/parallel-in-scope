package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
            assertThat(pending.outcome()).isEqualTo(TaskClassification.RUNNING);
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
                assertThat(element.outcome()).isEqualTo(TaskClassification.SUBMISSION_FAILURE);
                assertThat(element.failure()).isInstanceOf(SubmissionException.class);
            }
            assertThat(batch.report().stateCounts())
                    .containsOnlyKeys(TaskClassification.SUBMISSION_FAILURE)
                    .containsEntry(TaskClassification.SUBMISSION_FAILURE, 2);
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
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.SUCCESS);
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
            assertThat(element.outcome()).isEqualTo(TaskClassification.SUCCESS);
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
            assertThat(element.outcome()).isEqualTo(TaskClassification.USER_FAILURE);
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
            assertThat(Objects.requireNonNull(snapshot.members().get("slow")).outcome())
                    .isEqualTo(TaskClassification.MEMBER_CANCELLED);
            // The group-level outcome is the post-hoc view: the group token was cancelled to protect
            // the remaining members, so the group reads GROUP_CANCELLED.
            assertThat(snapshot.outcome()).isEqualTo(TaskClassification.GROUP_CANCELLED);
            // A future reads the token chain only. The member token is wired to the group token, so
            // once the group's own cancel lands it reports the group's cancellation too — the
            // transient MEMBER_CANCELLED a caller may see right after its own cancel() is not stable.
            await().atMost(2, TimeUnit.SECONDS).until(() -> member.outcome() == TaskClassification.GROUP_CANCELLED);
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
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.GROUP_CANCELLED);

            assertThat(group.findMember("first").get().outcome()).isEqualTo(TaskClassification.GROUP_CANCELLED);
            assertThat(group.findMember("second").get().outcome()).isEqualTo(TaskClassification.GROUP_CANCELLED);
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
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskClassification.USER_FAILURE);
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskClassification.FAIL_FAST);
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
            assertThat(element.outcome()).isEqualTo(TaskClassification.TIMEOUT);
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
            assertThat(Objects.requireNonNull(innerMember.get()).outcome())
                    .isEqualTo(TaskClassification.GROUP_CANCELLED);
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
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskClassification.TIMEOUT);
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
            assertThat(batch.results().get(1).outcome()).isEqualTo(TaskClassification.TIMEOUT);
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskClassification.SUCCESS);
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

            Optional<TaskClassification> terminal = Optional.empty();
            boolean regressed = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                TaskClassification observed = element.outcome();
                if (observed == TaskClassification.RUNNING) {
                    regressed = terminal.isPresent();
                } else {
                    terminal = Optional.of(observed);
                    if (element.isDone()) break;
                }
            }

            assertThat(regressed).isFalse();
            assertThat(element.isDone()).isTrue();
            assertThat(element.outcome()).isEqualTo(terminal.get()).isEqualTo(TaskClassification.TIMEOUT);
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
    void unsubmittedElementDeliversListenersRegisteredBeforeItRuns() {
        ExecutionPhaseHintFuture<String> prepared = preparedFuture(() -> "done");
        Task<String> element = Task.of("orders", new CancellationToken(), prepared);
        AtomicReference<String> observed = new AtomicReference<>();

        // The view is the element's final handle from creation on, so a listener registered before
        // anything is submitted fires with the executed outcome — no bind step swaps the delegate.
        element.addListener(() -> observed.set(element.outcome().name()), Runnable::run);
        assertThat(element.outcome()).isEqualTo(TaskClassification.RUNNING);

        prepared.run();

        assertThat(observed.get()).isEqualTo("SUCCESS");
        assertThat(element.outcome()).isEqualTo(TaskClassification.SUCCESS);
    }

    @Test
    void cancellingAnElementBeforeSubmissionKeepsItsExecutionUnentered() {
        AtomicBoolean executionRan = new AtomicBoolean();
        ExecutionPhaseHintFuture<String> prepared = preparedFuture(() -> {
            executionRan.set(true);
            return "done";
        });
        Task<String> element = Task.of("orders", new CancellationToken(), prepared);

        assertThat(element.cancel(true)).isTrue();
        // Cancelling the view forwards to the prepared future: the executor may still invoke it, and
        // the phase claim is what keeps the execution unentered.
        assertThat(prepared.isCancelled()).isTrue();
        prepared.run();

        assertThat(executionRan).isFalse();
        assertThat(element.outcome()).isEqualTo(TaskClassification.MEMBER_CANCELLED);
    }

    @Test
    void submissionFailureAndCancellationAttributeTheirOwnCause() {
        ExecutionPhaseHintFuture<String> rejected = preparedFuture(() -> "unreachable");
        ExecutionPhaseHintFuture<String> cancelled = preparedFuture(() -> "unreachable");
        Task<String> rejectedView = Task.of("orders", new CancellationToken(), rejected);
        Task<String> cancelledView = Task.of("orders", new CancellationToken(), cancelled);

        assertThat(rejected.claimSubmissionFailure(new InterruptedException("submitter interrupted")))
                .isTrue();
        rejected.settleSubmissionFailure();
        cancelled.skipExecution();
        cancelled.cancel(true);

        assertThat(rejectedView.outcome()).isEqualTo(TaskClassification.SUBMISSION_FAILURE);
        assertThat(rejectedView.failure())
                .isInstanceOf(SubmissionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(cancelledView.isCancelled()).isTrue();
        assertThat(cancelledView.outcome()).isEqualTo(TaskClassification.MEMBER_CANCELLED);
    }

    @Test
    void aClaimedSubmissionFailureOutranksACascadeCancellationThatRacesIt() {
        ExecutionPhaseHintFuture<String> prepared = preparedFuture(() -> "unreachable");
        Task<String> element = Task.of("orders", new CancellationToken(), prepared);

        // The batch claims every element on a handoff failure, then settles them. A fail-fast
        // cascade fired by an earlier settle can cancel this element in between; its recorded
        // attribution must still win, or the batch would report a cancellation instead of the
        // submission failure that actually ended it.
        assertThat(prepared.claimSubmissionFailure(new IllegalStateException("handoff broken")))
                .isTrue();
        prepared.cancel(true);

        assertThat(element.outcome()).isEqualTo(TaskClassification.SUBMISSION_FAILURE);
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
            assertThat(transformed.outcome()).isEqualTo(TaskClassification.SUCCESS);
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
        Task<String> succeeded = Task.of("orders", new CancellationToken(), Futures.immediateFuture("done"));
        Task<String> timed = Task.of(
                "orders",
                new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)),
                SettableFuture.<String>create());

        assertThat(succeeded.toString()).isEqualTo("Task[name=orders, state=SUCCESS, remaining=unbounded]");
        assertThat(timed.toString()).contains("name=orders", "state=RUNNING", "remaining=PT");
    }

    @Test
    void outcomeReadsSuccessEvenWhenTheCallingThreadIsInterrupted() {
        Task<String> succeeded = Task.of("orders", new CancellationToken(), Futures.immediateFuture("done"));

        Thread.currentThread().interrupt();
        try {
            assertThat(succeeded.outcome()).isEqualTo(TaskClassification.SUCCESS);
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

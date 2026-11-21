package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Integration evidence for capture timing, cancellation, and execution-exit ownership. */
class ContextPropagationRuntimeTest {
    private static final ParId WORKERS = ParId.of("context-workers");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Failed capture cancels the admitted unit instead of retaining running admission state. */
    @Test
    void captureFailureCancelsAdmittedUnit() {
        IllegalStateException failure = new IllegalStateException("capture failed");
        ContextPropagation provider = () -> {
            throw failure;
        };
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            TaskExecutionContext context = PreparedStates.contextPreparation(runtime.par(WORKERS));
            assertThatThrownBy(() -> TaskSubmissions.prepare(context, () -> "unreachable"))
                    .isSameAs(failure);
            assertThat(context.multiTaskContext().cancellationToken().state())
                    .isEqualTo(CancellationToken.State.CANCELLED);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Ownership transfers at runAll, including adopted sources on both sides of a failing preparation. */
    @Test
    void captureFailurePreservesAdoptedAdmissionCancellationOwnership() throws Exception {
        IllegalStateException failure = new IllegalStateException("capture failed");
        ContextPropagation provider = () -> {
            throw failure;
        };
        SettableFuture<String> before = SettableFuture.create();
        SettableFuture<String> after = SettableFuture.create();
        AtomicInteger bodies = new AtomicInteger();
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            assertThatThrownBy(() -> runtime.group("adopted-capture-failure", TIMEOUT)
                            .adopt(TaskKey.<String>named("before"), before)
                            .par(TaskKey.<Integer>named("failing"), runtime.par(WORKERS), bodies::incrementAndGet)
                            .adopt(TaskKey.<String>named("after"), after)
                            .runAll())
                    .isSameAs(failure);
            assertThat(before.isCancelled()).isTrue();
            assertThat(after.isCancelled()).isTrue();
            assertThat(bodies).hasValue(0);
            runtime.close();
            assertThat(runtime.awaitQuiescence(Duration.ofSeconds(1))).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Deferred window elements retain admission context and leave the worker's original context intact. */
    @Test
    void deferredElementsCaptureOnAdmissionNotWorkerHandoff() throws Exception {
        ThreadLocal<String> local = new ThreadLocal<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(ContextPropagationTest.provider(local))
                .build()) {
            executor.submit(() -> local.set("worker")).get(5, TimeUnit.SECONDS);
            local.set("admission");
            TaskBatch<String> batch = runtime.par(WORKERS)
                    .submitBatch(
                            Arrays.asList(1, 2),
                            value -> {
                                assertThat(local.get()).isEqualTo("admission");
                                if (value == 1) {
                                    entered.countDown();
                                    Uninterruptibles.awaitUninterruptibly(release);
                                }
                                String captured = local.get();
                                local.set("task mutation");
                                return captured;
                            },
                            BatchOptions.timeout("delayed-context", TIMEOUT).parallelism(1));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                local.set("later");
            } finally {
                release.countDown();
            }
            for (TaskFuture<String> task : batch.results()) {
                assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo("admission");
            }
            assertThat(executor.submit(local::get).get(5, TimeUnit.SECONDS)).isEqualTo("worker");
            assertThat(local.get()).isEqualTo("later");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** Normal completion listeners see restoration and released callable ownership, not just body exit. */
    @Test
    void normalSettlementFollowsRestorationAndRelease() {
        AtomicInteger restores = new AtomicInteger();
        ContextPropagation provider = () -> ContextSnapshot.of(() -> restores::incrementAndGet);
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            ExecutionPhaseHintFuture<String> future = PreparedStates.contextTask(runtime.par(WORKERS), () -> "value");
            AtomicBoolean listenerSawRestoredExit = new AtomicBoolean();
            future.addListener(
                    () -> listenerSawRestoredExit.set(
                            restores.get() == 1 && future.state() == TaskState.EXITED && future.callableReleased()),
                    MoreExecutors.directExecutor());
            future.run();
            assertThat(listenerSawRestoredExit).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /** Cancellation during provider installation still owns exactly one unwind and skips the body checkpoint. */
    @Test
    void cancellationDuringInstallationRestoresWithoutBodyEntry() throws Exception {
        CountDownLatch installing = new CountDownLatch(1);
        CountDownLatch finishInstall = new CountDownLatch(1);
        AtomicInteger restores = new AtomicInteger();
        AtomicBoolean bodyEntered = new AtomicBoolean();
        ContextPropagation provider = () -> ContextSnapshot.of(() -> {
            installing.countDown();
            Uninterruptibles.awaitUninterruptibly(finishInstall);
            return restores::incrementAndGet;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            ExecutionPhaseHintFuture<String> future = PreparedStates.contextTask(runtime.par(WORKERS), () -> {
                bodyEntered.set(true);
                return "unexpected";
            });
            Future<?> running = executor.submit(future);
            try {
                assertThat(installing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(future.cancel(true)).isTrue();
                assertThat(checkNotNull(future.observation()).view().isDone()).isFalse();
            } finally {
                finishInstall.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
            assertThat(bodyEntered).isFalse();
            assertThat(restores).hasValue(1);
            assertThat(future.callableReleased()).isTrue();
            assertThat(future.state()).isEqualTo(TaskState.EXITED);
        } finally {
            finishInstall.countDown();
            executor.shutdownNow();
        }
    }

    /** The same configured provider covers single, batch, group, combine, and nested inline tasks. */
    @Test
    void customProviderCoversEveryExecutionEntryAndRestoresCaller() throws Exception {
        ThreadLocal<String> local = new ThreadLocal<>();
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ContextPropagation provider = ContextPropagationTest.provider(local);
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            assertThat(runtime.contextPropagation()).isSameAs(provider);
            Par par = runtime.par(WORKERS);
            local.set("request");
            assertThat(par.submit(
                                    "single",
                                    () -> {
                                        assertThat(local.get()).isEqualTo("request");
                                        assertThat(TaskExecutionContext.current())
                                                .isNotNull();
                                        assertThat(SubmissionScope.current()).isNull();
                                        local.set("nested");
                                        return par.submit("nested", local::get, TaskOptions.timeout(TIMEOUT))
                                                .get();
                                    },
                                    TaskOptions.timeout(TIMEOUT))
                            .get())
                    .isEqualTo("nested");
            assertThat(local.get()).isEqualTo("request");
            assertThat(par.mapAndCombine(
                                    Arrays.asList(1, 2),
                                    ignored -> {
                                        assertThat(local.get()).isEqualTo("request");
                                        local.set("element");
                                        return 1;
                                    },
                                    BatchOptions.timeout("batch", TIMEOUT).parallelism(1),
                                    par,
                                    values -> local.get())
                            .terminalValueOrThrow())
                    .isEqualTo("request");
            TaskKey<String> member = TaskKey.named("member");
            try (TaskGroup<Void> group = runtime.groupDraft("group", TIMEOUT)
                    .par(member, par, local::get)
                    .submitAll()) {
                assertThat(group.futureOf(member).get()).isEqualTo("request");
            }
            assertThat(local.get()).isEqualTo("request");
        } finally {
            executor.shutdownNow();
        }
    }

    /** No-op propagation leaves application bindings alone but never disables framework context. */
    @Test
    void nonePreservesFrameworkBindingAndBorrowedExecutorOwnership() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(ContextPropagation.none())
                .build()) {
            assertThat(runtime.par(WORKERS)
                            .submit(
                                    "none",
                                    () -> {
                                        assertThat(TaskExecutionContext.current())
                                                .isNotNull();
                                        assertThat(SubmissionScope.current()).isNull();
                                        return "ok";
                                    },
                                    TaskOptions.timeout(TIMEOUT))
                            .get())
                    .isEqualTo("ok");
            assertThat(TaskExecutionContext.current()).isNull();
        } finally {
            assertThat(executor.isShutdown()).isFalse();
            executor.shutdownNow();
        }
    }

    /** All batch captures, including the deferred terminal, happen before the first executor handoff. */
    @Test
    void batchAndTerminalCaptureBeforeAnyBodyRuns() {
        AtomicInteger captures = new AtomicInteger();
        ContextPropagation provider = () -> {
            captures.incrementAndGet();
            return ContextPropagation.none().capture();
        };
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            Par par = runtime.par(WORKERS);
            assertThat(par.mapAndCombine(
                                    Arrays.asList(1, 2),
                                    value -> {
                                        assertThat(captures).hasValue(3);
                                        return value;
                                    },
                                    BatchOptions.timeout("prepared", TIMEOUT).parallelism(1),
                                    par,
                                    values -> {
                                        assertThat(captures).hasValue(3);
                                        return values.size();
                                    })
                            .terminalValueOrThrow())
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Capture failure prevents every handoff for single, batch, terminal, and group preparation. */
    @Test
    void captureFailurePreventsPartialSubmission() {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger failAt = new AtomicInteger(1);
        AtomicInteger bodies = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("capture failed");
        ContextPropagation provider = () -> {
            if (captures.incrementAndGet() == failAt.get()) {
                throw failure;
            }
            return ContextPropagation.none().capture();
        };
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            Par par = runtime.par(WORKERS);
            assertThatThrownBy(() -> par.submit("single", bodies::incrementAndGet, TaskOptions.timeout(TIMEOUT)))
                    .isSameAs(failure);
            captures.set(0);
            failAt.set(2);
            assertThatThrownBy(() -> par.map(
                            Arrays.asList(1, 2),
                            ignored -> bodies.incrementAndGet(),
                            BatchOptions.timeout("batch", TIMEOUT)))
                    .isSameAs(failure);
            captures.set(0);
            failAt.set(3);
            assertThatThrownBy(() -> par.mapAndCombine(
                            Arrays.asList(1, 2),
                            ignored -> bodies.incrementAndGet(),
                            BatchOptions.timeout("combine", TIMEOUT),
                            par,
                            values -> bodies.incrementAndGet()))
                    .isSameAs(failure);
            captures.set(0);
            failAt.set(2);
            assertThatThrownBy(() -> runtime.groupDraft("group", TIMEOUT)
                            .par(TaskKey.<Integer>named("first"), par, bodies::incrementAndGet)
                            .par(TaskKey.<Integer>named("second"), par, bodies::incrementAndGet)
                            .submitAll())
                    .isSameAs(failure);
            assertThat(bodies).hasValue(0);
            captures.set(0);
            failAt.set(Integer.MAX_VALUE);
            assertThat(par.submit("recovered", bodies::incrementAndGet, TaskOptions.timeout(TIMEOUT)))
                    .isNotNull();
            assertThat(bodies).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Cancellation before execution claim never installs the captured context or enters the body. */
    @Test
    void cancelledBeforeClaimNeverInstallsAndReleasesCallable() {
        AtomicInteger installs = new AtomicInteger();
        ContextPropagation provider = () -> ContextSnapshot.of(() -> {
            installs.incrementAndGet();
            return () -> {};
        });
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            ExecutionPhaseHintFuture<String> future = PreparedStates.contextTask(runtime.par(WORKERS), () -> {
                throw new AssertionError("cancelled body entered");
            });
            assertThat(future.cancel(true)).isTrue();
            future.run();
            assertThat(installs).hasValue(0);
            assertThat(future.callableReleased()).isTrue();
            assertThat(future.state()).isEqualTo(TaskState.EXITED);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Physical exit and its observation cannot precede application-context restoration. */
    @Test
    void restorationPrecedesExecutionExitEvenAfterCancellation() throws Exception {
        CountDownLatch restoring = new CountDownLatch(1);
        CountDownLatch allowRestore = new CountDownLatch(1);
        AtomicInteger restores = new AtomicInteger();
        ContextPropagation provider = () -> ContextSnapshot.of(() -> () -> {
            restoring.countDown();
            Uninterruptibles.awaitUninterruptibly(allowRestore);
            restores.incrementAndGet();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            ExecutionPhaseHintFuture<String> future = PreparedStates.contextTask(runtime.par(WORKERS), () -> "value");
            TaskObservation<String> observation = checkNotNull(future.observation());
            Future<?> running = executor.submit(future);
            assertThat(restoring.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.isDone()).isFalse();
            assertThat(future.state()).isEqualTo(TaskState.CLAIMED);
            assertThat(observation.view().isDone()).isFalse();
            assertThat(future.callableReleased()).isFalse();
            assertThat(future.cancel(true)).isTrue();
            assertThat(future.state()).isEqualTo(TaskState.RESULT_SETTLED);
            assertThat(observation.view().isDone()).isFalse();
            allowRestore.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertThat(restores).hasValue(1);
            assertThat(future.state()).isEqualTo(TaskState.EXITED);
            assertThat(observation.view().isDone()).isTrue();
            assertThat(future.callableReleased()).isTrue();
        } finally {
            allowRestore.countDown();
            executor.shutdownNow();
        }
    }

    /** Failed context installation still releases the claimed execution without entering user code. */
    @Test
    void installationFailureReleasesExecution() {
        IllegalStateException failure = new IllegalStateException("install failed");
        ContextPropagation provider = () -> ContextSnapshot.of(() -> {
            throw failure;
        });
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(WORKERS, executor)
                .contextPropagation(provider)
                .build()) {
            ExecutionPhaseHintFuture<String> future = PreparedStates.contextTask(runtime.par(WORKERS), () -> {
                throw new AssertionError("body entered after failed install");
            });
            future.run();
            assertThatThrownBy(future::get).hasCause(failure);
            assertThat(future.state()).isEqualTo(TaskState.EXITED);
            assertThat(future.callableReleased()).isTrue();
            assertThat(checkNotNull(future.observation()).view().isDone()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}

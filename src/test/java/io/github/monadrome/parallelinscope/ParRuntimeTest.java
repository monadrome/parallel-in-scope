package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ParRuntimeTest {
    @Test
    void propagatesAndRestoresTransmittableThreadLocalForEveryTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransmittableThreadLocal<String> context = new TransmittableThreadLocal<>();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            context.set("request-42");

            TaskBatchResult<String> result = global.par(ParId.of("worker"))
                    .map(
                            java.util.Arrays.asList(1, 2),
                            ignored -> context.get(),
                            BatchOptions.timeout("ttl", Duration.ofSeconds(30)).parallelism(1));

            assertThat(result.results())
                    .extracting(future -> future.get(2, TimeUnit.SECONDS))
                    .containsExactly("request-42", "request-42");

            AtomicReference<String> workerAfterTasks = new AtomicReference<>();
            executor.submit(() -> workerAfterTasks.set(context.get())).get(2, TimeUnit.SECONDS);
            assertThat(workerAfterTasks.get()).isNull();
        } finally {
            context.remove();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void executorRuntimeIsPackagePrivateImplementationDetail() {
        assertThat(Modifier.isPublic(ExecutorRuntime.class.getModifiers())).isFalse();
    }

    @Test
    void buildsImmutableNamedEntriesAndSharesRuntimeBySuppliedIdentity() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            ParRuntime global = ParRuntime.builder()
                    .register(ParId.of("one"), executor)
                    .register(ParId.of("same"), executor)
                    .defaultPar(ParId.of("one"))
                    .build();

            assertThat(global.defaultPar()).isSameAs(global.par(ParId.of("one")));
            assertThat(global.par(ParId.of("one")).id()).isEqualTo(ParId.of("one"));
            assertThat(global.par(ParId.of("one")).runtime()).isSameAs(global);
            assertThat(global.par(ParId.of("one")).runtime())
                    .isSameAs(global.par(ParId.of("same")).runtime());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsUnknownDefaultAndDuplicateNames() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() ->
                            ParRuntime.builder().defaultPar(ParId.of("missing")).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ParRuntime.builder()
                            .register(ParId.of("x"), executor)
                            .register(ParId.of("x"), executor))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executesUsingTheExecutorBoundAtBuildTime() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParRuntime global =
                    ParRuntime.builder().register(ParId.of("io"), executor).build();

            TaskBatchResult<Integer> result = global.par(ParId.of("io"))
                    .map(
                            Collections.singletonList(2),
                            value -> value + 1,
                            BatchOptions.timeout("increment", Duration.ofSeconds(30)));

            assertThat(result.results().get(0).get()).isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void topLevelBatchWithInheritedTimeoutIsRejectedWithoutAnEnclosingTask() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParRuntime global =
                    ParRuntime.builder().register(ParId.of("io"), executor).build();
            try {
                assertThatThrownBy(() -> global.par(ParId.of("io"))
                                .map(
                                        Collections.singletonList(1),
                                        value -> value + 1,
                                        BatchOptions.inheritTimeout("orphan")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("no enclosing deadline to inherit");
            } finally {
                global.close();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nullAndEmptyInputsProduceUsableEmptyBatchResults() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParRuntime global =
                    ParRuntime.builder().register(ParId.of("io"), executor).build();

            assertThat(global.par(ParId.of("io"))
                            .map(null, value -> value, BatchOptions.timeout("empty", Duration.ofSeconds(30)))
                            .results())
                    .isEmpty();
            assertThat(global.par(ParId.of("io"))
                            .map(
                                    Collections.<Integer>emptyList(),
                                    value -> value,
                                    BatchOptions.timeout("empty", Duration.ofSeconds(30)))
                            .results())
                    .isEmpty();
            global.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nestedBatchesAcrossExecutorsShareCancellationContextAndObservationGraph() throws Exception {
        ExecutorService outerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService innerExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outerExecutor)
                .register(ParId.of("inner"), innerExecutor)
                .build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphData expectedGraph = TaskGraphObservationScope.data();
            TaskBatchResult<Integer> outer = global.par(ParId.of("outer"))
                    .map(
                            Collections.singletonList(2),
                            value -> {
                                TaskBatchResult<Integer> inner = global.par(ParId.of("inner"))
                                        .map(
                                                Collections.singletonList(value),
                                                item -> item + 1,
                                                BatchOptions.timeout("inner", Duration.ofSeconds(30)));
                                try {
                                    return inner.results().get(0).get(2, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            BatchOptions.timeout("outer", Duration.ofSeconds(30)));

            assertThat(outer.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(TaskGraphObservationScope.data()).isSameAs(expectedGraph);
            assertThat(Objects.requireNonNull(expectedGraph).graph().edges()).isNotEmpty();
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
    }

    @Test
    void topLevelBatchInstallsObservationOnPreexistingWorkerAndPropagatesItToNestedBatch() throws Exception {
        ExecutorService outerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService innerExecutor = Executors.newSingleThreadExecutor();
        outerExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outerExecutor)
                .register(ParId.of("inner"), innerExecutor)
                .build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphData expectedGraph = TaskGraphObservationScope.data();
            java.util.concurrent.atomic.AtomicReference<TaskGraphData> graphOnOuterWorker =
                    new java.util.concurrent.atomic.AtomicReference<>();
            TaskBatchResult<Integer> outer = global.par(ParId.of("outer"))
                    .map(
                            Collections.singletonList(2),
                            value -> {
                                graphOnOuterWorker.set(TaskGraphObservationScope.data());
                                TaskBatchResult<Integer> inner = global.par(ParId.of("inner"))
                                        .map(
                                                Collections.singletonList(value),
                                                item -> item + 1,
                                                BatchOptions.timeout("inner", Duration.ofSeconds(30)));
                                try {
                                    return inner.results().get(0).get(2, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            BatchOptions.timeout("outer", Duration.ofSeconds(30)));

            assertThat(outer.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(graphOnOuterWorker.get()).isSameAs(expectedGraph);
            assertThat(Objects.requireNonNull(expectedGraph).graph().edges()).hasSize(2);
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
    }

    @Test
    void nestedSlidingWindowsDoNotSerializeTheirSubmitterLoops() throws Exception {
        ExecutorService outerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService innerExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outerExecutor)
                .register(ParId.of("inner"), innerExecutor)
                .build();
        try {
            TaskBatchResult<Integer> outer = global.par(ParId.of("outer"))
                    .map(
                            java.util.Arrays.asList(1, 99),
                            ignored -> {
                                TaskBatchResult<Integer> inner = global.par(ParId.of("inner"))
                                        .map(
                                                java.util.Arrays.asList(1, 2),
                                                value -> value + 1,
                                                BatchOptions.timeout("inner", Duration.ofSeconds(30))
                                                        .parallelism(1));
                                try {
                                    return inner.results().get(1).get(2, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            BatchOptions.timeout("outer", Duration.ofSeconds(30))
                                    .parallelism(1));

            assertThat(outer.results().get(0).get(3, TimeUnit.SECONDS)).isEqualTo(3);
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
    }

    @Test
    void closeFromCallerThreadFallbackTaskDoesNotDeadlockBatchAdmission() throws Exception {
        ExecutorService rejectedExecutor = Executors.newSingleThreadExecutor();
        rejectedExecutor.shutdown();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("cpu"), rejectedExecutor).build();
        try {
            TaskBatchResult<Integer> result = global.par(ParId.of("cpu"))
                    .map(
                            Collections.singletonList(1),
                            value -> {
                                global.close();
                                return value + 1;
                            },
                            BatchOptions.timeout("cpu", Duration.ofSeconds(30))
                                    .taskType(TaskType.CPU_BOUND)
                                    .runOnCallerThread(true));

            assertThat(result.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(global.closed()).isTrue();
        } finally {
            global.close();
            rejectedExecutor.shutdownNow();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void validatesPoliciesNamesAndStaticGlobalInstallation() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TaskListener listener = event -> {};
            ParRuntime.Builder builder =
                    ParRuntime.builder().taskListener(listener).register(ParId.of("io"), executor);
            assertThatThrownBy(() -> builder.parTaskListener(ParId.of("missing"), listener)
                            .build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ParRuntime.builder().register(ParId.of(""), executor))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ParRuntime.builder().register(ParId.of("null"), null))
                    .isInstanceOf(NullPointerException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void installsAndReturnsTheProcessGlobalOnlyOnce() {
        ParRuntime installed = ParRuntime.builder().build();
        try {
            ParRuntime.installGlobal(installed);

            assertThat(ParRuntime.global()).isSameAs(installed);
            assertThatThrownBy(
                            () -> ParRuntime.installGlobal(ParRuntime.builder().build()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            installed.close();
        }
    }

    @Test
    void closingTheInstalledInstanceReleasesTheGlobalSlot() {
        ParRuntime first = ParRuntime.builder().build();
        ParRuntime.installGlobal(first);
        first.close();

        ParRuntime second = ParRuntime.builder().build();
        try {
            ParRuntime.installGlobal(second);
            assertThat(ParRuntime.global()).isSameAs(second);
        } finally {
            second.close();
        }
    }

    @Test
    void awaitQuiescenceWaitsForCloseAndDrain() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        try {
            assertThat(global.awaitQuiescence(Duration.ofMillis(20))).isFalse();

            TaskBatchResult<String> batch = global.par(ParId.of("io"))
                    .map(
                            java.util.Arrays.asList("a", "b"),
                            x -> x,
                            BatchOptions.timeout("quiesce", Duration.ofSeconds(30)));
            batch.valuesOrThrow();
            global.close();

            assertThat(global.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitQuiescenceSaturatesAstronomicTimeouts() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            global.close();

            // Duration.ofSeconds(Long.MAX_VALUE) overflows toNanos(); the wait must saturate and
            // report quiescence immediately instead of throwing ArithmeticException.
            long start = System.nanoTime();
            assertThat(global.awaitQuiescence(Duration.ofSeconds(Long.MAX_VALUE)))
                    .isTrue();
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitQuiescenceWaitsForTaskBodiesThatOutliveTheirCancelledFutures() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatchResult<String> batch = global.par(ParId.of("io"))
                    .map(
                            Collections.singletonList("a"),
                            x -> {
                                entered.countDown();
                                // Ignore interruption: the future is cancelled immediately, but
                                // the body stays inside user code until released.
                                boolean interrupted = false;
                                while (true) {
                                    try {
                                        release.await();
                                        break;
                                    } catch (InterruptedException ignored) {
                                        interrupted = true;
                                    }
                                }
                                if (interrupted) Thread.currentThread().interrupt();
                                return x;
                            },
                            BatchOptions.timeout("body-quiesce", Duration.ofSeconds(30)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            batch.results().get(0).cancel(true);
            assertThat(batch.results().get(0).isDone()).isTrue();
            global.close();

            // Services have drained and the future is terminal, but the body is still running:
            // quiescence must not be reported.
            assertThat(global.awaitQuiescence(Duration.ofMillis(200))).isFalse();

            release.countDown();
            assertThat(global.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitQuiescenceCoversSingleSubmittedTaskBodies() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskFuture<String> task = global.par(ParId.of("io"))
                    .submit(
                            "parked",
                            () -> {
                                entered.countDown();
                                boolean interrupted = false;
                                while (true) {
                                    try {
                                        release.await();
                                        break;
                                    } catch (InterruptedException ignored) {
                                        interrupted = true;
                                    }
                                }
                                if (interrupted) Thread.currentThread().interrupt();
                                return "done";
                            },
                            TaskOptions.timeout(Duration.ofSeconds(30)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            task.cancel(true);
            global.close();

            assertThat(global.awaitQuiescence(Duration.ofMillis(200))).isFalse();

            release.countDown();
            assertThat(global.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void observationIsOwnedAndClosedExactlyOnce() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        try {
            io.github.monadrome.parallelinscope.TaskGraphObservationScope observation =
                    global.openTaskGraphObservation();
            assertThat(observation.owner()).isSameAs(global);
            assertThat(observation.closed()).isFalse();
            assertThat(TaskGraphObservationScope.current()).isSameAs(observation);
            observation.close();
            observation.close();
            assertThat(observation.closed()).isTrue();
            assertThat(TaskGraphObservationScope.current()).isNull();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNewBatchesAndObservationsAfterCloseWithoutClosingBorrowedExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        try {
            global.close();

            assertThat(global.closed()).isTrue();
            assertThat(executor.isShutdown()).isFalse();
            assertThatThrownBy(() -> global.openTaskGraphObservation()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> global.par(ParId.of("io"))
                            .map(
                                    Collections.singletonList(1),
                                    value -> value + 1,
                                    BatchOptions.timeout("closed", Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ParRuntime is closed");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeRejectsNewWorkWhileAnAdmittedBatchCompletesSetup() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch setupEntered = new CountDownLatch(1);
        CountDownLatch releaseSetup = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            Future<?> setup = callers.submit(() -> global.whileOpen(() -> {
                setupEntered.countDown();
                awaitLatch(releaseSetup);
                return null;
            }));
            assertThat(setupEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> close = callers.submit(() -> {
                global.close();
                closeReturned.countDown();
            });
            assertThat(closeReturned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(global.closed()).isTrue();

            releaseSetup.countDown();
            setup.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertThat(global.closed()).isTrue();
            assertThatThrownBy(() -> global.par(ParId.of("io"))
                            .map(
                                    Collections.singletonList(1),
                                    value -> value + 1,
                                    BatchOptions.timeout("closed", Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            releaseSetup.countDown();
            global.close();
            callers.shutdownNow();
            executor.shutdownNow();
        }
    }

    @Test
    void closeLetsAnAdmittedBatchDrainWithoutClosingItsBorrowedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        try {
            TaskBatchResult<Integer> result = global.par(ParId.of("io"))
                    .map(
                            java.util.Arrays.asList(1, 2, 3),
                            value -> {
                                if (value == 1) {
                                    firstTaskStarted.countDown();
                                    awaitLatch(releaseFirstTask);
                                }
                                return value + 1;
                            },
                            BatchOptions.timeout("drain", Duration.ofSeconds(30))
                                    .parallelism(1));
            assertThat(firstTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

            global.close();
            releaseFirstTask.countDown();

            assertThat(executor.isShutdown()).isFalse();
            assertThat(result.results().get(0).get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(result.results().get(1).get(5, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(result.results().get(2).get(5, TimeUnit.SECONDS)).isEqualTo(4);
        } finally {
            releaseFirstTask.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test latch", e);
        }
    }

    @Test
    void purgePolicyAndDeadlockDetectionListenersAreImmutableAndIdentityDeduplicated() {
        AtomicInteger calls = new AtomicInteger();
        io.github.monadrome.parallelinscope.DeadlockDetectionListener listener = event -> calls.incrementAndGet();
        ParRuntimeDeadlockPolicy deadlock = ParRuntimeDeadlockPolicy.builder()
                .enabled(true)
                .listener(listener)
                .listener(listener)
                .build();
        assertThat(deadlock.listeners()).hasSize(1);
        assertThatThrownBy(() -> deadlock.listeners().clear()).isInstanceOf(UnsupportedOperationException.class);
        ParRuntimePurgePolicy purge = ParRuntimePurgePolicy.builder()
                .enabled(true)
                .queuePressureThreshold(1.0)
                .canceledTaskRatioThreshold(0.5)
                .build();
        assertThat(purge.enabled()).isTrue();
        assertThat(purge.queuePressureThreshold()).isEqualTo(1.0);
        assertThat(purge.canceledTaskRatioThreshold()).isEqualTo(0.5);
        assertThat(ParRuntimePurgePolicy.builder().build().enabled()).isFalse();
        assertThat(ParRuntimeDeadlockPolicy.builder().build().enabled()).isFalse();
    }

    @Test
    void exposesImmutableTopologyAndConfiguredPolicies() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TaskListener listener = event -> {};
            ParRuntimeDeadlockPolicy deadlock =
                    ParRuntimeDeadlockPolicy.builder().enabled(true).build();
            ParRuntimePurgePolicy purge =
                    ParRuntimePurgePolicy.builder().enabled(true).build();
            ParRuntime global = ParRuntime.builder()
                    .taskListener(listener)
                    .deadlockPolicy(deadlock)
                    .purgePolicy(purge)
                    .register(ParId.of("one"), executor)
                    .build();

            assertThat(global.taskListeners()).containsExactly(listener);
            assertThat(global.taskListenersFor(ParId.of("one"))).containsExactly(listener);
            assertThat(global.deadlockPolicy()).isSameAs(deadlock);
            assertThat(global.purgePolicy()).isSameAs(purge);
            assertThat(global.find(ParId.of("one"))).contains(global.par(ParId.of("one")));
            assertThat(global.find(ParId.of("missing"))).isEmpty();
            assertThat(global.pars()).containsOnlyKeys(ParId.of("one"));
            assertThat(global.runtimes()).containsOnlyKeys(ParId.of("one"));
            assertThat(global.runtimesByIdentity()).hasSize(1);
            assertThat(global.purger()).isNotNull();
            assertThatThrownBy(() -> global.pars().clear()).isInstanceOf(UnsupportedOperationException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executorRuntimeKeepsSuppliedIdentityAndCreatesOnlyNeededAdapter() {
        ExecutorService plain = Executors.newSingleThreadExecutor();
        com.google.common.util.concurrent.ListeningExecutorService listening =
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            ExecutorRuntime plainRuntime = new ExecutorRuntime(plain);
            ExecutorRuntime listeningRuntime = new ExecutorRuntime(listening);
            ExecutorIdentity samePlain = new ExecutorIdentity(plain);
            ExecutorIdentity different = new ExecutorIdentity(listening);

            assertThat(plainRuntime.suppliedExecutor()).isSameAs(plain);
            assertThat(plainRuntime.submissionExecutorIsAdapter()).isTrue();
            assertThat(listeningRuntime.submissionExecutor()).isSameAs(listening);
            assertThat(listeningRuntime.submissionExecutorIsAdapter()).isFalse();
            assertThat(plainRuntime.identity()).isEqualTo(samePlain).isNotEqualTo(different);
            assertThat(plainRuntime.identity().hashCode()).isEqualTo(samePlain.hashCode());
            assertThat(plainRuntime.identity().suppliedExecutor()).isSameAs(plain);
            assertThat(plainRuntime.identity().toString()).contains("@");
            assertThat(plainRuntime.blockingRisk()).isEqualTo(BlockingRisk.UNKNOWN);
        } finally {
            plain.shutdownNow();
            listening.shutdownNow();
        }
    }

    @Test
    void batchReportAttributesDeadlineCancellationAsTimeout() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("io"), executor).build();
        try {
            TaskBatchResult<Integer> result = global.par(ParId.of("io"))
                    .map(
                            java.util.Arrays.asList(1, 2),
                            ignored -> {
                                try {
                                    Thread.sleep(10_000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return ignored;
                            },
                            BatchOptions.timeout("timeout-batch", Duration.ofMillis(100)));

            for (Future<Integer> future : result.results()) {
                assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.CancellationException.class);
            }
            // The token commits TIMEOUT before cancelling the element futures, so once every
            // future is cancelled the attribution is already stable.
            assertThat(result.report().stateCounts())
                    .containsOnlyKeys(TaskOutcome.TIMEOUT)
                    .containsEntry(TaskOutcome.TIMEOUT, 2);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}

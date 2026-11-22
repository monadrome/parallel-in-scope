package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Contract tests for runtime-registered application context and its CRR kernel. */
class ContextPropagationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ThreadLocal<String> VALUE = new ThreadLocal<>();

    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<ParRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        VALUE.remove();
        for (ParRuntime runtime : runtimes) {
            runtime.close();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void registeredContextReachesBatchAndRestoresBorrowedWorker() throws Exception {
        ExecutorService pool = pool(Executors.newSingleThreadExecutor());
        pool.submit(() -> VALUE.set("worker-original")).get(2, TimeUnit.SECONDS);
        ParRuntime runtime = runtime(pool, propagator(VALUE));
        VALUE.set("request");

        TaskBatchResult<String> result = runtime.par(ParId.of("worker"))
                .map(
                        Arrays.asList("a", "b"),
                        item -> VALUE.get() + ":" + item,
                        BatchOptions.timeout("batch", TIMEOUT).parallelism(1));

        assertThat(result.valuesOrThrow()).containsExactly("request:a", "request:b");
        assertThat(VALUE.get()).isEqualTo("request");
        assertThat(pool.submit(VALUE::get).get(2, TimeUnit.SECONDS)).isEqualTo("worker-original");
        pool.submit(VALUE::remove).get(2, TimeUnit.SECONDS);
    }

    @Test
    void deferredElementUsesAdmissionSnapshotNotRefillThread() throws Exception {
        ExecutorService pool = pool(Executors.newSingleThreadExecutor());
        ParRuntime runtime = runtime(pool, propagator(VALUE));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        VALUE.set("request");

        TaskBatch<String> batch = runtime.par(ParId.of("worker"))
                .submitBatch(
                        Arrays.asList(1, 2),
                        item -> {
                            if (item == 1) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            }
                            return VALUE.get() + ":" + item;
                        },
                        BatchOptions.timeout("deferred", TIMEOUT).parallelism(1));
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        VALUE.set("changed-after-admission");
        releaseFirst.countDown();

        assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo("request:1");
        assertThat(batch.results().get(1).get(2, TimeUnit.SECONDS)).isEqualTo("request:2");
        assertThat(VALUE.get()).isEqualTo("changed-after-admission");
    }

    @Test
    void nestedInlineExecutionCapturesCurrentValueAndRestoresOuterScope() {
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        ParRuntime runtime = runtime(direct, propagator(VALUE));
        Par par = runtime.par(ParId.of("worker"));
        VALUE.set("outer-request");

        TaskBatchResult<String> outer = par.map(
                Collections.singletonList("outer"),
                item -> {
                    assertThat(VALUE.get()).isEqualTo("outer-request");
                    VALUE.set("outer-modified");
                    TaskBatchResult<String> inner = par.map(
                            Collections.singletonList("inner"),
                            ignored -> VALUE.get(),
                            BatchOptions.timeout("inner", TIMEOUT));
                    assertThat(inner.valuesOrThrow()).containsExactly("outer-modified");
                    assertThat(VALUE.get()).isEqualTo("outer-modified");
                    return VALUE.get();
                },
                BatchOptions.timeout("outer", TIMEOUT));

        assertThat(outer.valuesOrThrow()).containsExactly("outer-modified");
        assertThat(VALUE.get()).isEqualTo("outer-request");
    }

    @Test
    void groupMembersAndTerminalCombineShareTheSameProtocol() throws Exception {
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        ParRuntime runtime = runtime(direct, propagator(VALUE));
        Par par = runtime.par(ParId.of("worker"));
        TaskKey<String> member = TaskKey.named("member");
        TaskKey<String> combine = TaskKey.named("combine");
        VALUE.set("group-request");

        TaskGroupResult<String> result = runtime.group("group", TIMEOUT)
                .par(member, par, () -> VALUE.get())
                .combine(combine, par, values -> VALUE.get() + ":" + values.valueOf(member))
                .runAll();

        assertThat(result.resultOf(member).valueOrThrow()).isEqualTo("group-request");
        assertThat(result.terminalValueOrThrow()).isEqualTo("group-request:group-request");
        assertThat(VALUE.get()).isEqualTo("group-request");
    }

    @Test
    void rejectedTaskNeverInstallsOrRuns() {
        ExecutorService rejected = pool(new RejectingExecutor());
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        ContextPropagator propagator = () -> {
            captures.incrementAndGet();
            return () -> {
                installs.incrementAndGet();
                return () -> {};
            };
        };
        ParRuntime runtime = runtime(rejected, propagator);

        TaskBatchResult<String> result = runtime.par(ParId.of("worker"))
                .map(
                        Collections.singletonList("item"),
                        item -> {
                            runs.incrementAndGet();
                            return item;
                        },
                        BatchOptions.timeout("rejected", TIMEOUT));

        assertThatThrownBy(result::valuesOrThrow).isInstanceOf(Exception.class);
        assertThat(captures).hasValue(1);
        assertThat(installs).hasValue(0);
        assertThat(runs).hasValue(0);
    }

    @Test
    void cancellationWhileRunningStillClosesScopeAndRestoresWorker() throws Exception {
        ExecutorService pool = pool(Executors.newSingleThreadExecutor());
        pool.submit(() -> VALUE.set("worker-original")).get(2, TimeUnit.SECONDS);
        ParRuntime runtime = runtime(pool, propagator(VALUE));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VALUE.set("request");

        TaskFuture<String> task = runtime.par(ParId.of("worker"))
                .submit(
                        "cancelled",
                        () -> {
                            started.countDown();
                            try {
                                await(release);
                            } catch (RuntimeException failure) {
                                throw failure;
                            }
                            return VALUE.get();
                        },
                        TaskOptions.timeout(TIMEOUT));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(task.cancel(true)).isTrue();
        release.countDown();
        assertThatThrownBy(() -> task.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThat(pool.submit(VALUE::get).get(2, TimeUnit.SECONDS)).isEqualTo("worker-original");
        pool.submit(VALUE::remove).get(2, TimeUnit.SECONDS);
    }

    @Test
    void installFailureUnwindsOpenedScopesAndSkipsTaskCode() {
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("install");
        ContextPropagator first = () -> () -> {
            events.add("install-first");
            return () -> events.add("close-first");
        };
        ContextPropagator second = () -> () -> {
            events.add("install-second");
            throw failure;
        };
        AtomicInteger runs = new AtomicInteger();
        Callable<String> task = ContextPropagation.ttlThen(Arrays.asList(first, second))
                .capturing(() -> {
                    runs.incrementAndGet();
                    return "not-run";
                });

        assertThatThrownBy(task::call).isSameAs(failure);
        assertThat(runs).hasValue(0);
        assertThat(events).containsExactly("install-first", "install-second", "close-first");
    }

    @Test
    void taskFailureRemainsPrimaryAndCloseFailureIsSuppressed() {
        IllegalStateException taskFailure = new IllegalStateException("task");
        IllegalStateException closeFailure = new IllegalStateException("close");
        ContextPropagator propagator = () -> () -> () -> {
            throw closeFailure;
        };
        Callable<String> task = ContextPropagation.ttlThen(Collections.singletonList(propagator))
                .capturing(() -> {
                    throw taskFailure;
                });

        assertThatThrownBy(task::call).satisfies(thrown -> {
            assertThat(thrown).isSameAs(taskFailure);
            assertThat(thrown.getSuppressed()).containsExactly(closeFailure);
        });
    }

    @Test
    void successfulTaskWithCloseFailureFailsAndClosesEveryScope() {
        List<String> events = new ArrayList<>();
        IllegalStateException firstClose = new IllegalStateException("first-close");
        IllegalStateException secondClose = new IllegalStateException("second-close");
        ContextPropagator first = () -> () -> {
            events.add("install-first");
            return () -> {
                events.add("close-first");
                throw firstClose;
            };
        };
        ContextPropagator second = () -> () -> {
            events.add("install-second");
            return () -> {
                events.add("close-second");
                throw secondClose;
            };
        };
        Callable<String> task =
                ContextPropagation.ttlThen(Arrays.asList(first, second)).capturing(() -> "value");

        assertThatThrownBy(task::call).satisfies(thrown -> {
            assertThat(thrown).isSameAs(secondClose);
            assertThat(thrown.getSuppressed()).containsExactly(firstClose);
        });
        assertThat(events).containsExactly("install-first", "install-second", "close-second", "close-first");
    }

    @SuppressWarnings("NullAway")
    @Test
    void nullStagesAreRejectedAtTheStageThatProducesThem() {
        ContextPropagator nullCapture = () -> null;
        assertThatThrownBy(() -> ContextPropagation.ttlThen(Collections.singletonList(nullCapture))
                        .capture())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capture() returned a null snapshot");

        ContextPropagator nullInstall = () -> () -> null;
        ContextPropagator.Snapshot snapshot = ContextPropagation.ttlThen(Collections.singletonList(nullInstall))
                .capture();
        assertThatThrownBy(snapshot::install)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("install() returned a null scope");
    }

    @SuppressWarnings("NullAway")
    @Test
    void registrationRejectsNullAndFreezesEachRuntimeSet() {
        assertThatThrownBy(() -> ParRuntime.builder().propagate(null)).isInstanceOf(NullPointerException.class);
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        ParRuntime.Builder builder =
                ParRuntime.builder().register(ParId.of("worker"), direct).propagate(propagator(VALUE));
        ParRuntime first = runtime(builder.build());
        VALUE.set("first");
        assertThat(first.par(ParId.of("worker"))
                        .map(
                                Collections.singletonList("x"),
                                ignored -> VALUE.get(),
                                BatchOptions.timeout("first", TIMEOUT))
                        .valuesOrThrow())
                .containsExactly("first");
        first.close();
        runtimes.remove(first);

        AtomicInteger captures = new AtomicInteger();
        ContextPropagator second = () -> {
            captures.incrementAndGet();
            return () -> () -> {};
        };
        builder.propagate(second);
        ParRuntime secondRuntime = runtime(builder.build());
        VALUE.set("second");
        assertThat(secondRuntime
                        .par(ParId.of("worker"))
                        .map(
                                Collections.singletonList("x"),
                                ignored -> VALUE.get(),
                                BatchOptions.timeout("second", TIMEOUT))
                        .valuesOrThrow())
                .containsExactly("second");
        assertThat(captures).hasValue(1);
    }

    @Test
    void captureFailureCancelsEarlierPreparedElementsWithoutRunningTaskCode() {
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("capture");
        ContextPropagator propagator = () -> {
            if (captures.incrementAndGet() == 2) {
                throw failure;
            }
            return () -> () -> {};
        };
        ParRuntime runtime = runtime(direct, propagator);

        assertThatThrownBy(() -> runtime.par(ParId.of("worker"))
                        .map(
                                Arrays.asList(1, 2),
                                item -> {
                                    runs.incrementAndGet();
                                    return item;
                                },
                                BatchOptions.timeout("capture-failure", TIMEOUT)))
                .isSameAs(failure);
        assertThat(captures).hasValue(2);
        assertThat(runs).hasValue(0);
    }

    @Test
    void captureFailureInGroupPreparationDoesNotRunMembers() {
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("group-capture");
        ContextPropagator propagator = () -> {
            if (captures.incrementAndGet() == 2) {
                throw failure;
            }
            return () -> () -> {};
        };
        ParRuntime runtime = runtime(direct, propagator);
        Par par = runtime.par(ParId.of("worker"));
        TaskKey<Integer> first = TaskKey.named("first");
        TaskKey<Integer> second = TaskKey.named("second");

        assertThatThrownBy(() -> runtime.group("capture-failure", TIMEOUT)
                        .par(first, par, () -> {
                            runs.incrementAndGet();
                            return 1;
                        })
                        .par(second, par, () -> {
                            runs.incrementAndGet();
                            return 2;
                        })
                        .runAll())
                .isSameAs(failure);
        assertThat(runs).hasValue(0);
    }

    @Test
    void installFailureIsAUserFailureOnTheRealBatchPath() {
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        IllegalStateException failure = new IllegalStateException("install");
        ContextPropagator propagator = () -> () -> {
            throw failure;
        };
        ParRuntime runtime = runtime(direct, propagator);

        TaskBatchResult<String> result = runtime.par(ParId.of("worker"))
                .map(Collections.singletonList("item"), item -> item, BatchOptions.timeout("install-failure", TIMEOUT));

        assertThatThrownBy(result::valuesOrThrow).isSameAs(failure);
        TerminationAssertions.assertUserFailure(result.completions().get(0).termination());
    }

    @Test
    void closeFailureAfterSuccessIsVisibleOnTheRealBatchPath() {
        ExecutorService direct = pool(com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService());
        IllegalStateException failure = new IllegalStateException("close");
        ContextPropagator propagator = () -> () -> () -> {
            throw failure;
        };
        ParRuntime runtime = runtime(direct, propagator);

        TaskBatchResult<String> result = runtime.par(ParId.of("worker"))
                .map(Collections.singletonList("item"), item -> item, BatchOptions.timeout("close-failure", TIMEOUT));

        assertThatThrownBy(result::valuesOrThrow).isSameAs(failure);
        TerminationAssertions.assertUserFailure(result.completions().get(0).termination());
    }

    private ParRuntime runtime(ExecutorService executor, ContextPropagator propagator) {
        return runtime(ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .propagate(propagator)
                .build());
    }

    private ParRuntime runtime(ParRuntime runtime) {
        runtimes.add(runtime);
        return runtime;
    }

    private ExecutorService pool(ExecutorService executor) {
        executors.add(executor);
        return executor;
    }

    private static ContextPropagator propagator(ThreadLocal<String> local) {
        return () -> {
            String captured = local.get();
            return () -> {
                String previous = local.get();
                if (captured == null) {
                    local.remove();
                } else {
                    local.set(captured);
                }
                return () -> {
                    if (previous == null) {
                        local.remove();
                    } else {
                        local.set(previous);
                    }
                };
            };
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new java.util.concurrent.RejectedExecutionException("rejected");
        }
    }
}

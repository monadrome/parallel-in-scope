package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Bindings and payload-ownership contract (decision §7, §9, §16.6-§16.17): one-shot synchronous
 * collection, freeze validation before admission, deterministic reference release on every path —
 * proven with holder probes, never GC timing.
 */
class TaskGroupBindingsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // ==================== freeze validation ====================

    @Test
    void missingBindingRejectsTheSubmissionBeforeAdmission() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<Integer> two = builder.task("two", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> bindings.task(one, () -> 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two");
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateBindingRejectsTheSubmissionBeforeAdmission() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(one, () -> 1);
                        bindings.task(one, () -> 2);
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("one");
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void foreignAndWrongKindHandlesRejectTheSubmissionBeforeAdmission() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<Integer> two = builder.task("two", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<String> assemble = builder.combine("assemble", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();
            TaskGroupDefinition.Member<Integer> foreign =
                    global.defineGroup("other", TIMEOUT).task("one", global.par(ParId.of("worker")));

            // A handle of another definition: foreign.
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(one, () -> 1);
                        bindings.task(foreign, () -> 2);
                        bindings.combine(assemble, values -> "x");
                    }))
                    .isInstanceOf(IllegalArgumentException.class);
            // A combine handle bound through task(): wrong kind.
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(one, () -> 1);
                        bindings.task(assemble, () -> "x");
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("combine");
            // A member handle bound through combine(): wrong kind.
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(one, () -> 1);
                        bindings.task(two, () -> 2);
                        bindings.combine(one, values -> 3);
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a combine");
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void missingCombineBodyRejectsTheSubmissionBeforeAdmission() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            builder.combine("assemble", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> bindings.task(one, () -> 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("assemble");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void nullBodiesAreRejectedAtTheBindingCall() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<String> assemble = builder.combine("assemble", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(null, () -> 1);
                        bindings.combine(assemble, values -> "x");
                    }))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> bindings.task(one, null)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(one, () -> 1);
                        bindings.combine(null, values -> "x");
                    }))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        bindings.task(one, () -> 1);
                        bindings.combine(assemble, null);
                    }))
                    .isInstanceOf(NullPointerException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // ==================== binder failure and bindings lifetime ====================

    @Test
    void binderFailureRunsNoExecutorAndClearsEveryRegisteredBody() {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        AtomicInteger executeCalls = new AtomicInteger();
        ExecutorService wrapped = new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void shutdown() {
                delegate.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return delegate.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return delegate.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return delegate.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }

            @Override
            public void execute(Runnable command) {
                executeCalls.incrementAndGet();
                delegate.execute(command);
            }
        };
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), wrapped).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();
            AtomicReference<TaskGroup.Bindings> escaped = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();
            IllegalStateException boom = new IllegalStateException("binder failed");

            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        escaped.set(bindings);
                        bindings.task(one, runs::incrementAndGet);
                        throw boom;
                    }))
                    .isSameAs(boom);

            // No admission, no executor call, no future, no timer/retain, and the payload that the
            // binder registered was released before the exception escaped.
            assertThat(executeCalls).hasValue(0);
            assertThat(runs).hasValue(0);
            assertThat(global.inFlight()).isZero();
            assertThat(escaped.get().isDiscarded()).isTrue();
            assertThat(escaped.get().payloadsCleared()).isTrue();
        } finally {
            global.close();
            wrapped.shutdownNow();
        }
    }

    @Test
    void admissionRejectionAfterFreezeStillClearsTheTransferredPayloads() {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        AtomicInteger executeCalls = new AtomicInteger();
        ExecutorService wrapped = new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void shutdown() {
                delegate.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return delegate.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return delegate.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return delegate.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }

            @Override
            public void execute(Runnable command) {
                executeCalls.incrementAndGet();
                delegate.execute(command);
            }
        };
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), wrapped).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();
            AtomicReference<TaskGroup.Bindings> escaped = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();

            // Closing the owner inside the binder loses the admission race deterministically: the
            // binder completes, freeze validates and transfers the payload, and only then
            // whileOpen rejects the run. The untaken payload must still be cleared, with no
            // executor call and no user code run.
            assertThatThrownBy(() -> global.submitGroup(definition, bindings -> {
                        escaped.set(bindings);
                        bindings.task(one, runs::incrementAndGet);
                        global.close();
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");

            assertThat(executeCalls).hasValue(0);
            assertThat(runs).hasValue(0);
            assertThat(global.inFlight()).isZero();
            // Freeze already drained the bindings before admission rejected the run: the payloads
            // left this instance and the bindings are drained, not discarded.
            assertThat(escaped.get().payloadsCleared()).isTrue();
            assertThat(escaped.get().isDrained()).isTrue();
            assertThat(escaped.get().isDiscarded()).isFalse();
        } finally {
            global.close();
            wrapped.shutdownNow();
        }
    }

    @Test
    void escapedBindingsAreUnusableAfterTheBinderReturns() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();
            AtomicReference<TaskGroup.Bindings> escaped = new AtomicReference<>();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                escaped.set(bindings);
                bindings.task(one, () -> 1);
            });
            TaskGroup.Bindings leaked = escaped.get();

            assertThat(leaked.isDrained()).isTrue();
            assertThat(leaked.payloadsCleared()).isTrue();
            assertThatThrownBy(() -> leaked.task(one, () -> 2)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> leaked.combine(one, values -> 2)).isInstanceOf(IllegalStateException.class);
            assertThat(group.future(one).get(2, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void bindingsAreUsableOnlyOnTheCreatingThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();
            TaskGroup.Bindings bindings = new TaskGroup.Bindings(definition);
            CountDownLatch callable = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    bindings.task(one, () -> 1);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
                callable.countDown();
            });
            other.start();
            assertThat(callable.await(2, TimeUnit.SECONDS)).isTrue();
            other.join(2000);
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);

            // The owner thread can still use it; the framework freeze drains it.
            bindings.task(one, () -> 1);
            TaskGroup.RunBindings run = bindings.freeze();
            assertThat(run.taskSlotCleared(0)).isFalse();
            run.discard();
            assertThat(run.taskSlotCleared(0)).isTrue();
            assertThat(bindings.isDrained()).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // ==================== payload transfer and release ====================

    @Test
    void freezeTransfersPayloadsAndClearsTheBindingsSourceSlots() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<String> assemble = builder.combine("assemble", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            TaskGroup.Bindings bindings = new TaskGroup.Bindings(definition);
            bindings.task(one, () -> 1);
            bindings.combine(assemble, values -> "x");
            TaskGroup.RunBindings run = bindings.freeze();

            // The source slots are clear even though the run still holds the payloads: a leaked
            // Bindings retains nothing, and the transfer itself is observable slot by slot.
            assertThat(bindings.payloadsCleared()).isTrue();
            assertThat(bindings.isDrained()).isTrue();
            assertThat(run.taskSlotCleared(0)).isFalse();
            assertThat(run.combineSlotCleared()).isFalse();

            Callable<Object> taken = run.takeCallable(0);
            assertThat(taken.call()).isEqualTo(1);
            assertThat(run.taskSlotCleared(0)).isTrue();
            TaskGroup.CombineBody<?> body = run.takeCombineBody();
            assertThat(run.combineSlotCleared()).isTrue();
            assertThat(body.apply(null)).isEqualTo("x");

            // Freeze failure clears both the source and the would-be payloads.
            TaskGroup.Bindings failing = new TaskGroup.Bindings(definition);
            failing.task(one, () -> 1);
            failing.task(one, () -> 2);
            assertThatThrownBy(failing::freeze).isInstanceOf(IllegalStateException.class);
            assertThat(failing.isDiscarded()).isTrue();
            assertThat(failing.payloadsCleared()).isTrue();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedCancelledAndFailFastMembersReleaseTheirBodyHolders() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService direct = com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), worker)
                .register(ParId.of("direct"), direct)
                .build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // The victim stays queued behind the parked blocker on the single worker. The failing
            // member runs inline on the direct Par during the submit loop, so the fail-fast
            // cascade reaches the not-yet-submitted victim before it can ever run: deterministic.
            TaskGroupDefinition.Builder builder = global.defineGroup("fail-fast-release", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocker = builder.task("blocker", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<Integer> failing = builder.task("failing", global.par(ParId.of("direct")));
            TaskGroupDefinition.Member<Integer> victim = builder.task("victim", global.par(ParId.of("worker")));
            AtomicInteger victimRuns = new AtomicInteger();
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(blocker, () -> {
                    started.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return 0;
                });
                bindings.task(failing, () -> {
                    // The blocker was submitted to the worker pool earlier in the submit loop, so
                    // it is already running on the worker thread; waiting here only aligns the
                    // failure with "blocker entered", not with the blocker finishing.
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                    throw new IllegalStateException("boom");
                });
                bindings.task(victim, victimRuns::incrementAndGet);
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // The never-run victim released its holder through the cancel-before-run path.
            org.awaitility.Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> group.future(victim).isDone());
            assertThat(victimRuns).hasValue(0);
            assertThat(group.callableReleased(victim)).isTrue();

            release.countDown();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);

            // The entered members released theirs through run()'s finally.
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(group.callableReleased(blocker)).isTrue();
                assertThat(group.callableReleased(failing)).isTrue();
            });
        } finally {
            release.countDown();
            global.close();
            worker.shutdownNow();
            direct.shutdownNow();
        }
    }

    @Test
    void timeoutBeforeRunReleasesTheMemberBodyHolders() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            // The deadline expires before the submission loop: the bind cancels every member
            // before any of them is submitted, and the holders are released without running.
            TaskGroupDefinition.Builder builder = global.defineGroup("timeout-release", Duration.ofNanos(1));
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<Integer> two = builder.task("two", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(one, () -> 1);
                bindings.task(two, () -> 2);
            });
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(group.callableReleased(one)).isTrue();
            assertThat(group.callableReleased(two)).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // ==================== per-submission isolation ====================

    @Test
    void bindingOrderDoesNotChangeDeclarationOrderExecution() throws Exception {
        ExecutorService direct = com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            List<String> executionOrder = new ArrayList<>();
            TaskGroupDefinition.Builder builder = global.defineGroup("order", TIMEOUT);
            TaskGroupDefinition.Member<Integer> a = builder.task("a", global.par(ParId.of("direct")));
            TaskGroupDefinition.Member<Integer> b = builder.task("b", global.par(ParId.of("direct")));
            TaskGroupDefinition.Member<Integer> c = builder.task("c", global.par(ParId.of("direct")));

            // Bind in reverse declaration order: execution still follows the definition.
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(c, record(executionOrder, "c", 3));
                bindings.task(a, record(executionOrder, "a", 1));
                bindings.task(b, record(executionOrder, "b", 2));
            });

            assertThat(executionOrder).containsExactly("a", "b", "c");
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    private static Callable<Integer> record(List<String> order, String name, int value) {
        return () -> {
            order.add(name);
            return value;
        };
    }

    @Test
    void twoSubmissionsOfOneDefinitionCaptureDifferentRequestsWithoutCrossTalk() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> load = builder.task("load", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            TaskGroup first = global.submitGroup(definition, bindings -> bindings.task(load, () -> "request-1"));
            TaskGroup second = global.submitGroup(definition, bindings -> bindings.task(load, () -> "request-2"));

            assertThat(first.future(load).get(2, TimeUnit.SECONDS)).isEqualTo("request-1");
            assertThat(second.future(load).get(2, TimeUnit.SECONDS)).isEqualTo("request-2");
            // Futures are per-run objects even though the handle is shared.
            assertThat(first.future(load)).isNotSameAs(second.future(load));
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSubmissionsOfOneDefinitionAreFullyIsolated() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> load = builder.task("load", global.par(ParId.of("worker")));
            TaskGroupDefinition definition = builder.build();

            int submissions = 16;
            List<Thread> threads = new ArrayList<>();
            List<Integer> observed = java.util.Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(submissions);
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < submissions; i++) {
                int request = i;
                Thread thread = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        TaskGroup group =
                                global.submitGroup(definition, bindings -> bindings.task(load, () -> request));
                        observed.add(group.future(load).get(5, TimeUnit.SECONDS));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                threads.add(thread);
                thread.start();
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Thread thread : threads) {
                thread.join(10_000);
            }

            // Every run saw exactly its own request: closures, futures, tokens, and results never
            // crossed submissions.
            assertThat(observed)
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.range(0, submissions)
                            .boxed()
                            .collect(java.util.stream.Collectors.toList()));
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}

package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * One-shot draft lifecycle and payload ownership: declaration failures run no user code, the draft
 * is usable only from its creating thread, every earlier stage goes stale once the chain advances or
 * is submitted, the payload carrier releases bodies deterministically, and independent chains never
 * cross-talk.
 *
 * <p>This replaces the former bindings contract. Two-phase binding is gone, so the assertions about
 * missing/duplicate/foreign/wrong-kind bindings have no subject; what survives is the ownership and
 * lifecycle discipline, proven with holder probes rather than GC timing.
 */
class GroupDraftLifecycleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // ==================== stale stages and thread ownership ====================

    @Test
    void everyEarlierStageGoesStaleOnceTheChainAdvancesOrIsSubmitted() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            GroupStart start = global.group("page", TIMEOUT);
            GroupStep<String> first = start.par("one", par, String.class, () -> "1");

            // A saved reference to an earlier stage is not a second draft: Java has no move
            // semantics, so the stale-stage check is the correctness guard behind the compile-time
            // shape of the chain.
            assertThatThrownBy(() -> start.par("two", par, String.class, () -> "2"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(start::submitAll).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> start.closeGrace(Duration.ZERO)).isInstanceOf(IllegalStateException.class);

            GroupStep<Tuple2<String, Integer>> second = first.par("two", par, Integer.class, () -> 2);
            assertThatThrownBy(() -> first.par("three", par, Integer.class, () -> 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(first::submitAll).isInstanceOf(IllegalStateException.class);

            TaskGroup<Tuple2<String, Integer>, Void> group = second.submitAll();
            // Submission consumes the draft: the last live stage is stale too, and a second
            // submission cannot replay it.
            assertThatThrownBy(() -> second.par("three", par, Integer.class, () -> 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(second::submitAll).isInstanceOf(IllegalStateException.class);
            assertThat(group.futureOf("one", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("1");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aDraftIsUsableOnlyOnTheThreadThatCreatedIt() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            GroupStart start = global.group("page", TIMEOUT);
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    start.par("one", par, String.class, () -> "1");
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
                attempted.countDown();
            });
            other.start();
            assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
            other.join(2000);
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);

            // The owner thread can still use the stage: the rejected call never advanced it.
            TaskGroup<String, Void> group =
                    start.par("one", par, String.class, () -> "1").submitAll();
            assertThat(group.groupName()).isEqualTo("page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // ==================== failures before any user code ====================

    @Test
    void declarationFailureRunsNoExecutorAndReachesNoAdmission() {
        AtomicInteger executeCalls = new AtomicInteger();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), new CountingExecutor(executeCalls))
                .build();
        try {
            Par par = global.par(ParId.of("worker"));
            // The second declaration is rejected after the first one registered a body: nothing has
            // been admitted, no executor has been called, and no cell of the draft is reachable from
            // the framework.
            assertThatThrownBy(() -> global.group("page", TIMEOUT)
                            .par("one", par, Integer.class, () -> 1)
                            .par("one", par, Integer.class, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one");
            assertThat(executeCalls).hasValue(0);
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
        }
    }

    @Test
    void admissionRejectionAfterSubmitAllRunsNoUserCode() {
        AtomicInteger executeCalls = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), new CountingExecutor(executeCalls))
                .build();
        try {
            Par par = global.par(ParId.of("worker"));
            GroupStep<Integer> step =
                    global.group("page", TIMEOUT).par("one", par, Integer.class, runs::incrementAndGet);

            // Closing the owner before the submission loses the admission race deterministically:
            // the declaration is complete and the bodies have moved into the kernel, and only then
            // does whileOpen reject the run.
            global.close();
            assertThatThrownBy(step::submitAll)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");

            assertThat(executeCalls).hasValue(0);
            assertThat(runs).hasValue(0);
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
        }
    }

    // ==================== payload carrier ====================

    @SuppressWarnings("unchecked")
    @Test
    void payloadCarrierClearsEachSlotOnTakeAndEverythingOnDiscard() throws Exception {
        Callable<Object> member = () -> 1;
        CombineBody<Object, Object> combine = values -> "x";
        TaskGroup.RunBindings payloads = new TaskGroup.RunBindings(new Callable<?>[] {member}, combine);

        assertThat(payloads.taskSlotCleared(0)).isFalse();
        assertThat(payloads.combineSlotCleared()).isFalse();
        assertThat(payloads.takeCallable(0).call()).isEqualTo(1);
        assertThat(payloads.taskSlotCleared(0)).isTrue();

        CombineBody<Object, Object> taken =
                (CombineBody<Object, Object>) Objects.requireNonNull(payloads.takeCombineBody());
        assertThat(payloads.combineSlotCleared()).isTrue();
        assertThat(taken.apply(null)).isEqualTo("x");

        // A never-consumed carrier is cleared wholesale, so a rejected submission retains nothing.
        TaskGroup.RunBindings discarded = new TaskGroup.RunBindings(new Callable<?>[] {member}, combine);
        discarded.discard();
        assertThat(discarded.taskSlotCleared(0)).isTrue();
        assertThat(discarded.combineSlotCleared()).isTrue();
    }

    // ==================== payload release ====================

    @Test
    void rejectedCancelledAndFailFastMembersReleaseTheirBodyHolders() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
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
            AtomicInteger victimRuns = new AtomicInteger();
            TaskGroup<Tuple2<Tuple2<Integer, Integer>, Integer>, Void> group = global.group(
                            "fail-fast-release", TIMEOUT)
                    .par("blocker", global.par(ParId.of("worker")), Integer.class, () -> {
                        started.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 0;
                    })
                    .par("failing", global.par(ParId.of("direct")), Integer.class, () -> {
                        // The blocker was submitted to the worker pool earlier in the submit loop,
                        // so it is already running on the worker thread; waiting here only aligns the
                        // failure with "blocker entered", not with the blocker finishing.
                        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                        throw new IllegalStateException("boom");
                    })
                    .par("victim", global.par(ParId.of("worker")), Integer.class, victimRuns::incrementAndGet)
                    .submitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // The never-run victim released its holder through the cancel-before-run path.
            Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> group.futureOf("victim").isDone());
            assertThat(victimRuns).hasValue(0);
            assertThat(group.callableReleased("victim")).isTrue();

            release.countDown();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);

            // The entered members released theirs through run()'s finally.
            Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(group.callableReleased("blocker")).isTrue();
                assertThat(group.callableReleased("failing")).isTrue();
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
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.group("timeout-release", Duration.ofNanos(1))
                    .par("one", global.par(ParId.of("worker")), Integer.class, () -> 1)
                    .par("two", global.par(ParId.of("worker")), Integer.class, () -> 2)
                    .submitAll();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(group.callableReleased("one")).isTrue();
            assertThat(group.callableReleased("two")).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aRunningBodyHasNotYetReleasedItsHolder() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TaskGroup<String, Void> group = global.group("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> {
                    started.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return "value";
                })
                .submitAll()) {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // The probes are not constants: a body still inside its finally has not released yet, so
            // both the positional and the named probe have to report false here. Without this the
            // release contract could be satisfied by a probe that always answers true.
            assertThat(group.callableReleasedAt(0)).isFalse();
            assertThat(group.callableReleased("user")).isFalse();

            release.countDown();
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            // The body exits after the future settles, so the release is awaited rather than assumed.
            Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(group.callableReleasedAt(0)).isTrue();
                assertThat(group.callableReleased("user")).isTrue();
            });
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    // ==================== per-run isolation ====================

    @Test
    void declarationOrderDrivesSubmissionOrder() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            List<String> executionOrder = new ArrayList<>();
            Par par = global.par(ParId.of("direct"));
            TaskGroup<Tuple2<Tuple2<Integer, Integer>, Integer>, Void> group = global.group("order", TIMEOUT)
                    .par("a", par, Integer.class, record(executionOrder, "a", 1))
                    .par("b", par, Integer.class, record(executionOrder, "b", 2))
                    .par("c", par, Integer.class, record(executionOrder, "c", 3))
                    .submitAll();

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
    void twoChainsCaptureDifferentRequestsWithoutCrossTalk() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            TaskGroup<String, Void> first = global.group("page", TIMEOUT)
                    .par("load", par, String.class, () -> "request-1")
                    .submitAll();
            TaskGroup<String, Void> second = global.group("page", TIMEOUT)
                    .par("load", par, String.class, () -> "request-2")
                    .submitAll();

            assertThat(first.futureOf("load").get(2, TimeUnit.SECONDS)).isEqualTo("request-1");
            assertThat(second.futureOf("load").get(2, TimeUnit.SECONDS)).isEqualTo("request-2");
            // Futures are per-run objects even though both runs declared the same member name.
            assertThat(first.futureOf("load")).isNotSameAs(second.futureOf("load"));
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentChainsAreFullyIsolated() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            int runs = 16;
            List<Thread> threads = new ArrayList<>();
            List<Integer> observed = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(runs);
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < runs; i++) {
                int request = i;
                Thread thread = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        // Each run declares its own chain on its own thread: the draft is never
                        // shared, so closures, futures, tokens, and results cannot cross runs.
                        TaskGroup<Integer, Void> group = global.group("page", TIMEOUT)
                                .par("load", par, Integer.class, () -> request)
                                .submitAll();
                        observed.add(group.futureOf("load", TypeToken.of(Integer.class))
                                .get(5, TimeUnit.SECONDS));
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

            assertThat(observed)
                    .containsExactlyInAnyOrderElementsOf(
                            IntStream.range(0, runs).boxed().collect(Collectors.toList()));
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /** Counts every handoff so a test can prove that no user code reached an executor. */
    private static final class CountingExecutor extends AbstractExecutorService {
        private final AtomicInteger executeCalls;
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();

        CountingExecutor(AtomicInteger executeCalls) {
            this.executeCalls = executeCalls;
        }

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
    }
}

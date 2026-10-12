package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TtlRunnable;
import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Lifecycle semantics of {@link TaskGraphObservationScope}: ownership, polarity, idempotence. */
// JUnit-style: 'global' is assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class TaskGraphObservationScopeTest {

    private ParRuntime global;

    @AfterEach
    void cleanUp() {
        TaskGraphObservationScope.restore(null);
        if (global != null) {
            global.close();
        }
    }

    @Test
    void openTaskGraphObservationExposesOwnerDataAndCurrentPolarity() {
        global = ParRuntime.builder().build();
        TaskGraphObservationScope context = global.openTaskGraphObservation();
        try {
            assertThat(context.owner()).isSameAs(global);
            assertThat(context.closed()).isFalse();
            assertThat(TaskGraphObservationScope.current()).isSameAs(context);
            assertThat(context.graph()).isEqualTo(TaskGraph.empty());
        } finally {
            context.close();
        }

        assertThat(context.closed()).isTrue();
        assertThat(TaskGraphObservationScope.current()).isNull();

        // current() stays null after closing even without a fresh thread-local reset.
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void nestedObservationsRestoreTheOuterGraphData() throws Exception {
        global = ParRuntime.builder()
                .register(ParId.of("io"), Executors.newSingleThreadExecutor())
                .build();
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            TaskNode outerUnit = TaskGraphFixtures.node("outer");
            outer.record(TaskGraph.builder().admit(null, outerUnit).build());

            TaskGraphObservationScope inner = global.openTaskGraphObservation();
            assertThat(TaskGraphObservationScope.current()).isSameAs(inner);
            assertThat(inner.graph().asValueGraph().nodes()).isEmpty();
            inner.close();

            assertThat(TaskGraphObservationScope.current()).isSameAs(outer);
            assertThat(outer.graph().asValueGraph().nodes()).containsExactly(outerUnit);
        }
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void observationPropagatesAcrossTtlEnhancedSubmission() throws Exception {
        global = ParRuntime.builder().build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (TaskGraphObservationScope context = global.openTaskGraphObservation()) {
                AtomicReference<TaskGraphObservationScope> seen = new AtomicReference<>();

                executor.submit(TtlRunnable.get(() -> seen.set(TaskGraphObservationScope.current())))
                        .get(2, TimeUnit.SECONDS);

                assertThat(seen.get()).isSameAs(context);
            }

            // TTL restores the worker's previous value after the task: no scope leaks.
            AtomicReference<TaskGraphObservationScope> afterRestore = new AtomicReference<>();
            executor.submit(TtlRunnable.get(() -> afterRestore.set(TaskGraphObservationScope.current())))
                    .get(2, TimeUnit.SECONDS);
            assertThat(afterRestore.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Closing a scope on a different thread than the one that opened it is supported: the closed
     * scope stays installed on the opening thread, but resolution must skip it and reach the open
     * outer scope, and the closed scope itself records nothing.
     */
    @Test
    void innerScopeClosedOnAnotherThreadLeavesTheOuterScopeRecording() throws Exception {
        global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        TaskGraphObservationScope outer = global.openTaskGraphObservation();
        TaskGraphObservationScope inner = global.openTaskGraphObservation();

        Thread closer = new Thread(inner::close, "inner-close");
        closer.start();
        closer.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(closer.isAlive()).isFalse();
        assertThat(inner.closed()).isTrue();

        TaskGraphFixtures.Pool first = TaskGraphFixtures.pronePool("pool-a");
        TaskGraphFixtures.Pool second = TaskGraphFixtures.pronePool("pool-b");
        TaskGraphObservationScope resolved = TaskGraphObservationScope.resolveFor(null, global);
        assertThat(resolved).isSameAs(outer);
        Objects.requireNonNull(resolved)
                .record(TaskGraph.builder()
                        .admit(first.node("a"), second.node("b"))
                        .build());
        TaskGraphObservationScope resolvedAgain = TaskGraphObservationScope.resolveFor(null, global);
        assertThat(resolvedAgain).as("the first read repaired the binding").isSameAs(outer);
        Objects.requireNonNull(resolvedAgain)
                .record(TaskGraph.builder()
                        .admit(second.node("b"), first.node("c"))
                        .build());
        inner.record(TaskGraphFixtures.twoPoolCycle());
        assertThat(inner.graph().asValueGraph().nodes()).isEmpty();

        outer.close();
        assertThat(Objects.requireNonNull(Futures.getDone(outer.reportFuture())).status())
                .isEqualTo(TaskGraphReport.Status.ISSUE);
    }

    @Test
    void crossThreadCloseHealsToTheOpenOuterScope() throws Exception {
        global = ParRuntime.builder().build();
        TaskGraphObservationScope outer = global.openTaskGraphObservation();
        TaskGraphObservationScope inner = global.openTaskGraphObservation();

        Thread closer = new Thread(inner::close, "inner-close");
        closer.start();
        closer.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(closer.isAlive()).isFalse();

        // The opening thread still carries the closed inner binding; reading skips it and lands on
        // the open outer scope.
        assertThat(TaskGraphObservationScope.current()).isSameAs(outer);

        outer.close();
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void nonLifoCloseUnwindsClosedOuterBeforeClosingTheInner() {
        global = ParRuntime.builder().build();
        TaskGraphObservationScope outer = global.openTaskGraphObservation();
        TaskGraphObservationScope inner = global.openTaskGraphObservation();

        // Closing an outer scope while a newer inner scope is still installed must not clobber
        // the inner binding. Closing that inner later then skips the closed outer remnant.
        outer.close();
        assertThat(TaskGraphObservationScope.current()).isSameAs(inner);

        inner.close();
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void reentrantLoggingHandlerCannotDeadlockClose() throws Exception {
        // A JUL handler is user-replaceable code: if it re-enters the same scope's close(), the
        // re-entrant call must observe an already-published report instead of waiting for the
        // publication the outer close can only make after the handler returns.
        global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        scope.record(TaskGraphFixtures.twoPoolCycle());

        AtomicReference<Throwable> reentrantFailure = new AtomicReference<>();
        java.util.logging.Logger scopeLogger =
                java.util.logging.Logger.getLogger(TaskGraphObservationScope.class.getName());
        java.util.logging.Handler reentrant = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                try {
                    scope.close();
                } catch (Throwable failure) {
                    reentrantFailure.compareAndSet(null, failure);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        scopeLogger.addHandler(reentrant);
        // Close on a daemon thread with a bounded join: the pre-fix deadlock would otherwise hang
        // the test thread forever instead of failing.
        Thread closer = new Thread(scope::close, "reentrant-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            scopeLogger.removeHandler(reentrant);
        }

        assertThat(closer.isAlive())
                .as("close must not wait on a report its own logging handler is waiting for")
                .isFalse();
        assertThat(reentrantFailure.get()).isNull();
        assertThat(scope.reportFuture().isDone()).isTrue();
        assertThat(Objects.requireNonNull(Futures.getDone(scope.reportFuture())).status())
                .isEqualTo(TaskGraphReport.Status.ISSUE);
    }

    @Test
    void doubleClosePublishesTheReportOnlyOnce() throws Exception {
        global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();

        TaskGraphObservationScope context = global.openTaskGraphObservation();
        context.record(TaskGraphFixtures.twoPoolCycle());

        context.close();
        assertThat(context.reportFuture().isDone()).isTrue();
        TaskGraphReport first = Objects.requireNonNull(Futures.getDone(context.reportFuture()));
        assertThat(first.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        context.close(); // Idempotent: no second detection pass.
        assertThat(Futures.getDone(context.reportFuture())).isSameAs(first);
    }

    @Test
    // deliberately passes null to verify the constructor's null rejection
    @SuppressWarnings("NullAway")
    void constructorRejectsNullOwner() {
        assertThatThrownBy(() -> new TaskGraphObservationScope(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * A top-level group with two members and a combine records its members and combine as nodes and
     * each member's join into the combine, and nothing else: membership is no edge and no caller
     * forks a top-level group, so the graph stays clean.
     */
    @Test
    void aMultiMemberGroupInsideAScopeRecordsItsJoinsAndLeavesTheGraphClean() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        Par par = global.par(ParId.of("worker"));

        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        try {
            try (TaskGroup<Tuple2<Integer, Integer>, Integer> group = global.groupDraft("page", Duration.ofSeconds(30))
                    .par("left", par, Integer.class, () -> 1)
                    .par("right", par, Integer.class, () -> 2)
                    .combine("join", par, Integer.class, values -> {
                        Tuple2<Integer, Integer> members = Objects.requireNonNull(values);
                        return Objects.requireNonNull(members.first()) + Objects.requireNonNull(members.second());
                    })
                    .submitAll()) {
                assertThat(group.completionFuture().get(5, TimeUnit.SECONDS).outcome())
                        .isEqualTo(TaskOutcome.SUCCESS);
                assertThat(group.valuesFuture().get(5, TimeUnit.SECONDS).typedValues())
                        .isEqualTo(Tuple2.of(1, 2));
            }
            assertThat(scope.closed()).isFalse();
            TaskGraph graph = scope.graph();
            TaskNode left = TaskGraphFixtures.named(graph, "left");
            TaskNode right = TaskGraphFixtures.named(graph, "right");
            TaskNode join = TaskGraphFixtures.named(graph, "join");
            assertThat(graph.asValueGraph().nodes()).containsExactly(left, right, join);
            assertThat(graph.asValueGraph().edgeValue(left, join)).contains(TaskGraph.Dependency.JOIN);
            assertThat(graph.asValueGraph().edgeValue(right, join)).contains(TaskGraph.Dependency.JOIN);
            assertThat(graph.asValueGraph().edges()).hasSize(2);
        } finally {
            scope.close();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
    }
}

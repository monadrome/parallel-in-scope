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
            assertThat(TaskGraphObservationScope.data()).isNotNull();
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
            TaskGraphData outerData = TaskGraphObservationScope.data();
            assertThat(outerData).isNotNull();

            TaskGraphObservationScope inner = global.openTaskGraphObservation();
            assertThat(TaskGraphObservationScope.current()).isSameAs(inner);
            assertThat(TaskGraphObservationScope.data()).isNotSameAs(outerData);
            inner.close();

            assertThat(TaskGraphObservationScope.current()).isSameAs(outer);
            assertThat(TaskGraphObservationScope.data()).isSameAs(outerData);
        }
        assertThat(TaskGraphObservationScope.current()).isNull();
    }

    @Test
    void observationPropagatesAcrossTtlEnhancedSubmission() throws Exception {
        global = ParRuntime.builder().build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TaskGraphData expectedData;
            try (TaskGraphObservationScope context = global.openTaskGraphObservation()) {
                expectedData = TaskGraphObservationScope.data();
                AtomicReference<TaskGraphObservationScope> seen = new AtomicReference<>();
                AtomicReference<TaskGraphData> dataSeen = new AtomicReference<>();

                executor.submit(TtlRunnable.get(() -> {
                            seen.set(TaskGraphObservationScope.current());
                            dataSeen.set(TaskGraphObservationScope.data());
                        }))
                        .get(2, TimeUnit.SECONDS);

                assertThat(seen.get()).isSameAs(context);
                assertThat(dataSeen.get()).isSameAs(expectedData);
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

    @Test
    void reentrantLoggingHandlerCannotDeadlockClose() throws Exception {
        // A JUL handler is user-replaceable code: if it re-enters the same scope's close(), the
        // re-entrant call must observe an already-published report instead of waiting for the
        // publication the outer close can only make after the handler returns.
        global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        TaskGraphObservationScope.logTaskPair(
                "a", "a", "b", "b", new TaskEdge(1, TaskType.IO_BOUND, "e1", "e2", 1, Duration.ofMillis(10)));
        TaskGraphObservationScope.logTaskPair(
                "b", "b", "a", "a", new TaskEdge(1, TaskType.IO_BOUND, "e2", "e1", 1, Duration.ofMillis(10)));

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
        TaskGraphObservationScope.logTaskPair(
                "a", "a", "b", "b", new TaskEdge(1, TaskType.IO_BOUND, "e1", "e2", 1, Duration.ofMillis(10)));
        TaskGraphObservationScope.logTaskPair(
                "b", "b", "a", "a", new TaskEdge(1, TaskType.IO_BOUND, "e2", "e1", 1, Duration.ofMillis(10)));

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

    @Test
    void aMultiMemberGroupInsideAScopeRecordsEveryForkAndLeavesTheGraphClean() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        Par par = global.par(ParId.of("worker"));

        TaskGraphObservationScope scope = global.openTaskGraphObservation();
        try {
            // Two members plus a terminal combine. The group's forking instrumentation walks its
            // members by declaration position, so a single-member group never reaches the second
            // iteration and never records the combine's own fork edge.
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
        } finally {
            scope.close();
        }

        // Running a group inside the scope is recorded as ordinary forking, not as a cycle.
        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
    }
}

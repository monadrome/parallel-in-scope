package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.node;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.twoPoolCycle;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Node identity through the real admission path, and what a closing scope publishes and restores. */
class TaskGraphBatchIdentityTest {

    @AfterEach
    void clearThreadState() {
        TaskGraphObservationScope.restore(null);
    }

    @Test
    void sameTaskNameInIndependentBatchesDoesNotCollapseNodes() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("pool"), pool).build();
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            Par par = global.par(ParId.of("pool"));
            par.map(Arrays.asList(1, 2), value -> value, BatchOptions.timeout("same-name", Duration.ofSeconds(30)));
            par.map(Arrays.asList(3, 4), value -> value, BatchOptions.timeout("same-name", Duration.ofSeconds(30)));

            assertThat(scope.graph().asValueGraph().nodes())
                    .hasSize(2)
                    .allMatch(unit -> unit.name().equals("same-name"))
                    .extracting(TaskNode::id)
                    .doesNotHaveDuplicates();
            assertThat(scope.graph().asValueGraph().edges()).isEmpty();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    @Test
    void outsideAnyScopeTheExecutorQueriesAreFalse() {
        TaskGraphObservationScope.restore(null);
        assertThat(TaskGraphObservationScope.current()).isNull();
        assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
        assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();
    }

    @Test
    void closingObservationPublishesReportAndRestoresOuterGraph() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            outer.record(TaskGraph.builder().admit(null, node("outer")).build());
            TaskGraph outerGraph = outer.graph();
            TaskGraphObservationScope inner = global.openTaskGraphObservation();
            inner.record(twoPoolCycle());
            inner.close();

            TaskGraphReport report = Objects.requireNonNull(Futures.getDone(inner.reportFuture()));
            assertThat(report.executorCycle()).isTrue();
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
            assertThat(TaskGraphObservationScope.current()).isSameAs(outer);
            assertThat(outer.graph()).isSameAs(outerGraph);
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
        } finally {
            global.close();
        }
    }

    @Test
    void closingObservationPublishesExecutorCycleEdges() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            scope.record(twoPoolCycle());
            reportFuture = scope.reportFuture();
        } finally {
            global.close();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.executorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
        assertThat(report.taskEdges()).contains("task-a[a] -> task-b[b]", "task-b[b] -> task-c[c]");
    }
}

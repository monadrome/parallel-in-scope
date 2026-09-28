package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.named;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.pronePool;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.safePool;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.twoPoolCycle;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.monadrome.parallelinscope.TaskGraphFixtures.Pool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the task graph and its executor projection across representative scenarios, and exports
 * each scenario as JSON to {@code target/graph-export/} for offline visualization.
 */
class TaskGraphExportTest {

    private static final Path EXPORT_DIR = Paths.get("target", "graph-export");

    @AfterEach
    void clearThreadState() {
        TaskGraphObservationScope.restore(null);
    }

    @Test
    void acyclicChainAndBranchExportCleanGraph() throws Exception {
        Pool a = pronePool("pool-a");
        Pool b = pronePool("pool-b");
        Pool c = safePool("pool-c");
        Pool d = pronePool("pool-d");
        TaskGraph tasks = TaskGraph.builder()
                .admit(null, a.node("a"))
                .admit(a.node("a"), b.node("b"))
                .admit(b.node("b"), c.node("c"))
                .admit(a.node("a"), d.node("d"))
                .build();

        ExecutorGraph executors = ExecutorGraph.projectedFrom(tasks);
        assertThat(tasks.asValueGraph().nodes()).hasSize(4);
        assertThat(tasks.asValueGraph().edges()).hasSize(3);
        assertThat(executors.asValueGraph().edges()).hasSize(2);
        assertThat(executors.cycle()).isFalse();
        assertThat(executors.selfLoop()).isFalse();

        exportScenario("acyclic-chain", tasks);
    }

    @Test
    void executorCycleAcrossDistinctPoolsIsDetectedAndExported() throws Exception {
        TaskGraph tasks = twoPoolCycle();

        ExecutorGraph executors = ExecutorGraph.projectedFrom(tasks);
        assertThat(executors.cycle()).isTrue();
        assertThat(executors.selfLoop()).isFalse();
        assertThat(executors.asValueGraph().nodes()).hasSize(2);
        assertThat(executors.asValueGraph().edges()).hasSize(2);

        exportScenario("executor-cycle", tasks);
    }

    @Test
    void sameLabelOnDistinctPoolsDoesNotReportCycle() throws Exception {
        Pool first = pronePool("pool");
        Pool second = pronePool("pool");
        TaskGraph tasks = TaskGraph.builder()
                .admit(first.node("a"), second.node("b"))
                .admit(first.node("c"), second.node("d"))
                .build();

        ExecutorGraph executors = ExecutorGraph.projectedFrom(tasks);
        assertThat(executors.asValueGraph().nodes()).containsExactly(first.identity, second.identity);
        assertThat(executors.cycle()).isFalse();
        assertThat(executors.selfLoop()).isFalse();

        exportScenario("executor-same-label", tasks);
    }

    @Test
    void realNestedParMapPathIsRecordedAndExported() throws Exception {
        ExecutorService outerExecutor =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        ExecutorService innerExecutor =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outerExecutor)
                .register(ParId.of("inner"), innerExecutor)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        TaskGraph captured;
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
            TaskBatch<Integer> outer = submitNested(global);
            assertThat(outer.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);

            captured = observation.graph();
            assertThat(captured.asValueGraph().nodes()).hasSize(2);
            assertThat(captured.asValueGraph().edgeValue(named(captured, "outer"), named(captured, "inner")))
                    .contains(TaskGraph.Dependency.FORK);
            ExecutorGraph executors = ExecutorGraph.projectedFrom(captured);
            assertThat(executors.asValueGraph().nodes()).hasSize(2);
            assertThat(executors.cycle()).isFalse();

            exportScenario("real-path", captured);
            reportFuture = observation.reportFuture();
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
        assertThat(Objects.requireNonNull(Futures.getDone(reportFuture)).status())
                .isEqualTo(TaskGraphReport.Status.NO_ISSUE);
    }

    /**
     * Documents a known blind spot: {@code Executors.newSingleThreadExecutor()} returns a
     * DelegatedExecutorService wrapper, which {@code ExecutorRuntime.detectStarvationProne} does not
     * recognize — the wrapper is not a {@code ThreadPoolExecutor}. The nested unit is therefore never
     * starvation-prone, so the same nested-map shape as {@link #realNestedParMapPathIsRecordedAndExported()}
     * projects to an EMPTY executor graph.
     */
    @Test
    void wrappedExecutorsAreInvisibleToExecutorGraphs() throws Exception {
        ExecutorService outerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService innerExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outerExecutor)
                .register(ParId.of("inner"), innerExecutor)
                .build();
        try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
            TaskBatch<Integer> outer = submitNested(global);
            assertThat(outer.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);

            TaskGraph graph = observation.graph();
            assertThat(graph.asValueGraph().edges()).hasSize(1);
            assertThat(ExecutorGraph.projectedFrom(graph).asValueGraph().nodes())
                    .isEmpty();
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
    }

    /**
     * A user-supplied direct executor ({@link MoreExecutors#newDirectExecutorService()}) runs tasks
     * inline on the caller thread. The task graph still records the unit — recording is
     * submission-side and executor-agnostic — but the unit is not starvation-prone, since inline
     * execution holds no pool resource that could starve, so nothing reaches the projection.
     */
    @Test
    void directExecutorServiceRecordsTaskGraphButSkipsExecutorGraphs() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
            TaskBatch<Integer> batch = global.par(ParId.of("direct"))
                    .submitBatch(
                            Collections.singletonList(1),
                            value -> value + 1,
                            BatchOptions.timeout("direct", Duration.ofSeconds(30)));

            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(2);

            TaskGraph graph = observation.graph();
            assertThat(graph.asValueGraph().nodes()).hasSize(1);
            assertThat(named(graph, "direct").starvationProne()).isFalse();
            assertThat(ExecutorGraph.projectedFrom(graph).cycle()).isFalse();

            exportScenario("direct-executor", graph);
        } finally {
            global.close();
        }
    }

    @Test
    void groupWithCombineExportsItsJoins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
            Par par = global.par(ParId.of("worker"));
            global.group("page", Duration.ofSeconds(10))
                    .par("left", par, Integer.class, () -> 1)
                    .par("right", par, Integer.class, () -> 2)
                    .combine("sum", par, Integer.class, values -> 3)
                    .runAll();

            TaskGraph graph = observation.graph();
            TaskNode sum = named(graph, "sum");
            assertThat(graph.asValueGraph().edges()).hasSize(2);
            assertThat(graph.asValueGraph().edgeValue(named(graph, "left"), sum))
                    .contains(TaskGraph.Dependency.JOIN);
            assertThat(graph.asValueGraph().edgeValue(named(graph, "right"), sum))
                    .contains(TaskGraph.Dependency.JOIN);

            exportScenario("group-combine", graph);
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }

    private static TaskBatch<Integer> submitNested(ParRuntime global) {
        return global.par(ParId.of("outer"))
                .submitBatch(
                        Collections.singletonList(2),
                        value -> {
                            TaskBatch<Integer> inner = global.par(ParId.of("inner"))
                                    .submitBatch(
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
    }

    private static void exportScenario(String scenario, TaskGraph tasks) throws IOException {
        ExecutorGraph executors = ExecutorGraph.projectedFrom(tasks);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        indent(json, 1).append("\"scenario\": ").append(quote(scenario)).append(",\n");
        indent(json, 1)
                .append("\"tasks\": ")
                .append(taskGraphJson(tasks.asValueGraph(), 2))
                .append(",\n");
        indent(json, 1)
                .append("\"executors\": ")
                .append(executorGraphJson(executors, 2))
                .append(",\n");
        indent(json, 1).append("\"predicates\": {");
        json.append("\"executorCycle\": ").append(executors.cycle());
        json.append(", \"executorSelfLoop\": ").append(executors.selfLoop());
        json.append("}\n}");
        Files.createDirectories(EXPORT_DIR);
        Files.write(EXPORT_DIR.resolve(scenario + ".json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String taskGraphJson(ValueGraph<TaskNode, TaskGraph.Dependency> graph, int level) {
        StringBuilder json = new StringBuilder("{\n");
        indent(json, level).append("\"nodes\": [");
        String separator = "";
        for (TaskNode node : graph.nodes()) {
            json.append(separator).append(nodeJson(node));
            separator = ", ";
        }
        json.append("],\n");
        indent(json, level).append("\"edges\": [");
        separator = "";
        for (EndpointPair<TaskNode> edge : graph.edges()) {
            json.append(separator)
                    .append("{\"source\": ")
                    .append(quote(edge.source().id()))
                    .append(", \"target\": ")
                    .append(quote(edge.target().id()))
                    .append(", \"kind\": ")
                    .append(quote(String.valueOf(graph.edgeValueOrDefault(edge.source(), edge.target(), null))))
                    .append('}');
            separator = ", ";
        }
        json.append("]\n");
        indent(json, level - 1).append('}');
        return json.toString();
    }

    private static String nodeJson(TaskNode node) {
        ExecutorIdentity executor = node.executor();
        return "{\"id\": " + quote(node.id())
                + ", \"name\": " + quote(node.name())
                + ", \"executor\": " + quote(executor == null ? null : executor.toString())
                + ", \"executorLabel\": " + quote(node.executorLabel())
                + ", \"starvationProne\": " + node.starvationProne()
                + ", \"taskType\": " + quote(node.taskType().name())
                + ", \"parallelism\": " + node.parallelism()
                + ", \"taskCount\": " + node.taskCount()
                + ", \"budgetMillis\": " + node.budget().toMillis()
                + '}';
    }

    private static String executorGraphJson(ExecutorGraph executors, int level) {
        StringBuilder json = new StringBuilder("{\n");
        indent(json, level).append("\"nodes\": [");
        String separator = "";
        for (ExecutorIdentity node : executors.asValueGraph().nodes()) {
            json.append(separator)
                    .append("{\"id\": ")
                    .append(quote(node.toString()))
                    .append(", \"label\": ")
                    .append(quote(executors.label(node)))
                    .append('}');
            separator = ", ";
        }
        json.append("],\n");
        indent(json, level).append("\"edges\": [");
        separator = "";
        for (EndpointPair<ExecutorIdentity> edge : executors.asValueGraph().edges()) {
            ImmutableList.Builder<String> evidence = ImmutableList.builder();
            for (EndpointPair<TaskNode> fork : executors.evidence(edge)) {
                evidence.add(quote(fork.source().id() + " -> " + fork.target().id()));
            }
            json.append(separator)
                    .append("{\"source\": ")
                    .append(quote(edge.source().toString()))
                    .append(", \"target\": ")
                    .append(quote(edge.target().toString()))
                    .append(", \"evidence\": [")
                    .append(String.join(", ", evidence.build()))
                    .append("]}");
            separator = ", ";
        }
        json.append("]\n");
        indent(json, level - 1).append('}');
        return json.toString();
    }

    private static StringBuilder indent(StringBuilder json, int level) {
        for (int i = 0; i < level; i++) json.append("  ");
        return json;
    }

    private static String quote(@Nullable String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.append('"').toString();
    }
}

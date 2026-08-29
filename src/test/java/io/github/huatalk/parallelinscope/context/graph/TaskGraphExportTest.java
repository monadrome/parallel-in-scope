package io.github.huatalk.parallelinscope.context.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.huatalk.parallelinscope.context.GlobalParObservationContext;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.ExecutorIdentity;
import io.github.huatalk.parallelinscope.scope.GlobalPar;
import io.github.huatalk.parallelinscope.scope.GlobalParLivelockPolicy;
import io.github.huatalk.parallelinscope.scope.TaskType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three graph views built by {@link TaskGraph.Data} (task graph, executor-name graph,
 * executor-identity graph) across representative scenarios, and exports each scenario as JSON to
 * {@code target/graph-export/} for offline visualization.
 */
class TaskGraphExportTest {

    private static final Path EXPORT_DIR = Paths.get("target", "graph-export");

    @AfterEach
    void clearThreadState() {
        TaskGraph.restore(null);
    }

    @Test
    void acyclicChainAndBranchExportCleanGraph() throws Exception {
        GlobalPar global = GlobalPar.builder().build();
        try (GlobalParObservationContext ignored = global.openObservation()) {
            TaskGraph.logTaskPair(null, "root", "a", "task-a", legacyEdge("root-exec", "pool-a", true));
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", legacyEdge("pool-a", "pool-b", true));
            TaskGraph.logTaskPair("b", "task-b", "c", "task-c", legacyEdge("pool-b", "pool-c", false));
            TaskGraph.logTaskPair("a", "task-a", "d", "task-d", legacyEdge("pool-a", "pool-d", true));

            TaskGraph.Data data = TaskGraph.data();
            assertThat(data.isTaskCycle()).isFalse();
            assertThat(data.isSelfLoop()).isFalse();
            assertThat(data.isExecutorCycle()).isFalse();
            assertThat(data.isExecutorSelfLoop()).isFalse();
            assertThat(data.getGraph().nodes()).containsExactlyInAnyOrder("root", "a", "b", "c", "d");
            assertThat(data.getGraph().edges()).hasSize(4);
            // Edges with null identities never enter the identity graph.
            assertThat(identityGraphOf(data).nodes()).isEmpty();
            // Only the three deadlock-prone edges land in the executor-name graph.
            assertThat(data.getExecutorGraph().edges()).hasSize(3);

            exportScenario("acyclic-chain", data);
        } finally {
            global.close();
        }
    }

    @Test
    void taskCycleAndSelfLoopAreDetectedAndExported() throws Exception {
        GlobalPar global = GlobalPar.builder().build();
        try (GlobalParObservationContext ignored = global.openObservation()) {
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", legacyEdge("pool-a", "pool-b", true));
            TaskGraph.logTaskPair("b", "task-b", "a", "task-a", legacyEdge("pool-b", "pool-a", true));
            TaskGraph.logTaskPair("a", "task-a", "a", "task-a", legacyEdge("pool-a", "pool-a", true));
            // Parallel edge: same endpoint pair as the first entry, kept as a second TaskEdge.
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", legacyEdge("pool-a", "pool-b", true));

            TaskGraph.Data data = TaskGraph.data();
            assertThat(data.isTaskCycle()).isTrue();
            assertThat(data.isSelfLoop()).isTrue();
            assertThat(data.isExecutorCycle()).isTrue();
            assertThat(data.isExecutorSelfLoop()).isTrue();
            assertThat(data.getGraph().edges()).hasSize(3);
            assertThat(data.getGraph().edgeValueOrDefault("a", "b", Collections.emptyList())).hasSize(2);

            exportScenario("task-cycle", data);
        } finally {
            global.close();
        }
    }

    @Test
    void executorCycleAcrossDistinctIdentitiesIsDetectedAndExported() throws Exception {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().build();
        try (GlobalParObservationContext ignored = global.openObservation()) {
            ExecutorIdentity firstIdentity = new ExecutorIdentity(first);
            ExecutorIdentity secondIdentity = new ExecutorIdentity(second);
            TaskGraph.logTaskPair(
                    "a", "task-a", "b", "task-b", identityEdge(firstIdentity, secondIdentity, "pool-b", "pool-a"));
            TaskGraph.logTaskPair(
                    "b", "task-b", "a", "task-a", identityEdge(secondIdentity, firstIdentity, "pool-a", "pool-b"));

            TaskGraph.Data data = TaskGraph.data();
            assertThat(data.isTaskCycle()).isTrue();
            assertThat(data.isExecutorCycle()).isTrue();
            assertThat(data.isExecutorSelfLoop()).isFalse();

            ValueGraph<ExecutorIdentity, List<TaskEdge>> identityGraph = identityGraphOf(data);
            assertThat(identityGraph.nodes()).containsExactlyInAnyOrder(firstIdentity, secondIdentity);
            assertThat(identityGraph.edges()).hasSize(2);
            assertThat(data.getExecutorGraph().edges()).hasSize(2);

            exportScenario("executor-cycle", data);
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    void sameNameExecutorsWithDistinctIdentitiesDoNotReportCycle() throws Exception {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().build();
        try (GlobalParObservationContext ignored = global.openObservation()) {
            ExecutorIdentity firstIdentity = new ExecutorIdentity(first);
            ExecutorIdentity secondIdentity = new ExecutorIdentity(second);
            // Same executor NAME on both ends, but two distinct executor objects and a single
            // direction: the name graph must not be mistaken for a real resource cycle.
            TaskGraph.logTaskPair(
                    "a", "task-a", "b", "task-b", identityEdge(firstIdentity, secondIdentity, "pool", "pool"));
            TaskGraph.logTaskPair(
                    "b", "task-b", "c", "task-c", identityEdge(firstIdentity, secondIdentity, "pool", "pool"));

            TaskGraph.Data data = TaskGraph.data();
            assertThat(data.isTaskCycle()).isFalse();
            assertThat(data.isExecutorCycle()).isFalse();
            assertThat(data.isExecutorSelfLoop()).isFalse();

            // The legacy name graph DOES collapse both pools into one "pool" node with a self-loop;
            // the identity graph is what keeps detection accurate.
            assertThat(data.getExecutorGraph().nodes()).containsExactly("pool");
            assertThat(data.getExecutorGraph().edges()).hasSize(1);
            ValueGraph<ExecutorIdentity, List<TaskEdge>> identityGraph = identityGraphOf(data);
            assertThat(identityGraph.nodes()).containsExactlyInAnyOrder(firstIdentity, secondIdentity);
            assertThat(identityGraph.edges()).hasSize(1);

            exportScenario("executor-same-name", data);
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    void realNestedParMapPathIsRecordedAndExported() throws Exception {
        // Raw ThreadPoolExecutors: ExecutorRuntime.detectRisk only recognizes the concrete class,
        // so bounded pools are marked deadlock-prone and identities land in the identity graph.
        ExecutorService outerExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        ExecutorService innerExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        java.util.concurrent.atomic.AtomicInteger detections = new java.util.concurrent.atomic.AtomicInteger();
        GlobalPar global = GlobalPar.builder()
                .register("outer", outerExecutor)
                .register("inner", innerExecutor)
                .livelockPolicy(GlobalParLivelockPolicy.builder()
                        .enabled(true)
                        .listener(event -> detections.incrementAndGet())
                        .build())
                .build();
        TaskGraph.Data captured;
        try (GlobalParObservationContext observation = global.openObservation()) {
            AsyncBatchResult<Integer> outer = global.par("outer")
                    .map(
                            Collections.singletonList(2),
                            value -> {
                                AsyncBatchResult<Integer> inner = global.par("inner")
                                        .map(
                                                Collections.singletonList(value),
                                                item -> item + 1,
                                                ExecutionOptions.of("inner").build());
                                try {
                                    return inner.getResults().get(0).get(2, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            ExecutionOptions.of("outer").build());

            assertThat(outer.getResults().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);

            captured = observation.data();
            // root -> outer batch, outer batch -> inner batch.
            assertThat(captured.getGraph().nodes()).hasSize(3);
            assertThat(captured.getGraph().edges()).hasSize(2);
            assertThat(captured.isTaskCycle()).isFalse();
            assertThat(captured.isSelfLoop()).isFalse();
            assertThat(captured.isExecutorCycle()).isFalse();
            assertThat(captured.isExecutorSelfLoop()).isFalse();
            // Real path captures executor identities on both ends.
            assertThat(identityGraphOf(captured).nodes()).hasSize(2);

            exportScenario("real-path", captured);
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
        // The acyclic production path must not raise any livelock event.
        assertThat(detections.get()).isZero();
    }

    /**
     * Documents a known blind spot: {@code Executors.newSingleThreadExecutor()} returns a
     * DelegatedExecutorService wrapper, which ExecutorRuntime.detectRisk classifies as UNKNOWN.
     * UNKNOWN edges are never marked deadlock-prone, so the exact same nested-map shape as
     * {@link #realNestedParMapPathIsRecordedAndExported()} produces an EMPTY identity graph.
     */
    @Test
    void wrappedExecutorsFallIntoUnknownRiskAndAreInvisibleToExecutorGraphs() throws Exception {
        ExecutorService outerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService innerExecutor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("outer", outerExecutor)
                .register("inner", innerExecutor)
                .build();
        try (GlobalParObservationContext observation = global.openObservation()) {
            AsyncBatchResult<Integer> outer = global.par("outer")
                    .map(
                            Collections.singletonList(2),
                            value -> {
                                AsyncBatchResult<Integer> inner = global.par("inner")
                                        .map(
                                                Collections.singletonList(value),
                                                item -> item + 1,
                                                ExecutionOptions.of("inner").build());
                                try {
                                    return inner.getResults().get(0).get(2, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            ExecutionOptions.of("outer").build());

            assertThat(outer.getResults().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(3);

            TaskGraph.Data data = observation.data();
            // Task graph is still recorded, but executor-level detection sees nothing.
            assertThat(data.getGraph().edges()).hasSize(2);
            assertThat(data.getExecutorGraph().edges()).isEmpty();
            assertThat(identityGraphOf(data).nodes()).isEmpty();
        } finally {
            global.close();
            outerExecutor.shutdownNow();
            innerExecutor.shutdownNow();
        }
    }

    /**
     * A user-supplied direct executor ({@link MoreExecutors#newDirectExecutorService()}) runs tasks
     * inline on the caller thread. The task graph still records the fork edge (recording is
     * submission-side and executor-agnostic), but the edge is not deadlock-prone — semantically
     * correct here, since inline execution holds no extra pool resource that could starve — so the
     * executor-level graphs stay empty.
     */
    @Test
    void directExecutorServiceRecordsTaskGraphButSkipsExecutorGraphs() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        GlobalPar global = GlobalPar.builder().register("direct", direct).build();
        try (GlobalParObservationContext observation = global.openObservation()) {
            AsyncBatchResult<Integer> batch = global.par("direct")
                    .map(
                            Collections.singletonList(1),
                            value -> value + 1,
                            ExecutionOptions.of("direct").build());

            assertThat(batch.getResults().get(0).get(2, TimeUnit.SECONDS)).isEqualTo(2);

            TaskGraph.Data data = observation.data();
            // Fork edge is recorded regardless of executor implementation.
            assertThat(data.getGraph().nodes()).hasSize(2);
            assertThat(data.getGraph().edges()).hasSize(1);
            // But the executor-level views stay empty: UNKNOWN risk is never deadlock-prone.
            assertThat(data.getExecutorGraph().edges()).isEmpty();
            assertThat(identityGraphOf(data).nodes()).isEmpty();
            assertThat(data.isExecutorCycle()).isFalse();
            assertThat(data.isExecutorSelfLoop()).isFalse();

            exportScenario("direct-executor", data);
        } finally {
            global.close();
        }
    }

    // ==================== JSON export ====================

    private static void exportScenario(String scenario, TaskGraph.Data data) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "scenario", quote(scenario));
        json.append(",\n");
        indent(json, 1).append("\"graphs\": {\n");
        field(json, 2, "task", graphJson(data.getGraph(), labelsOf(data), 3));
        json.append(",\n");
        field(json, 2, "executorName", graphJson(data.getExecutorGraph(), null, 3));
        json.append(",\n");
        field(json, 2, "executorIdentity", identityGraphJson(identityGraphOf(data), 3));
        json.append('\n');
        indent(json, 1).append("},\n");
        indent(json, 1).append("\"predicates\": {");
        json.append("\"taskCycle\": ").append(data.isTaskCycle());
        json.append(", \"selfLoop\": ").append(data.isSelfLoop());
        json.append(", \"executorCycle\": ").append(data.isExecutorCycle());
        json.append(", \"executorSelfLoop\": ").append(data.isExecutorSelfLoop());
        json.append("}\n}");
        Files.createDirectories(EXPORT_DIR);
        Files.write(EXPORT_DIR.resolve(scenario + ".json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String graphJson(
            ValueGraph<String, List<TaskEdge>> graph, Map<String, String> labels, int level) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        indent(json, level).append("\"nodes\": [");
        boolean first = true;
        for (String node : graph.nodes()) {
            if (!first) json.append(", ");
            first = false;
            String label = labels == null ? node : labels.getOrDefault(node, "NA");
            json.append("{\"id\": ").append(quote(node)).append(", \"label\": ").append(quote(label)).append('}');
        }
        json.append("],\n");
        indent(json, level).append("\"edges\": [");
        first = true;
        for (EndpointPair<String> pair : graph.edges()) {
            if (!first) json.append(", ");
            first = false;
            List<TaskEdge> edges = graph.edgeValueOrDefault(pair.source(), pair.target(), Collections.emptyList());
            json.append("{\"source\": ").append(quote(pair.source()));
            json.append(", \"target\": ").append(quote(pair.target()));
            json.append(", \"metadata\": ").append(metadataJson(edges));
            json.append('}');
        }
        json.append("]\n");
        indent(json, level - 1).append('}');
        return json.toString();
    }

    private static String identityGraphJson(ValueGraph<ExecutorIdentity, List<TaskEdge>> graph, int level) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        indent(json, level).append("\"nodes\": [");
        boolean first = true;
        for (ExecutorIdentity node : graph.nodes()) {
            if (!first) json.append(", ");
            first = false;
            json.append("{\"id\": ").append(quote(node.toString()));
            json.append(", \"label\": ").append(quote(node.toString())).append('}');
        }
        json.append("],\n");
        indent(json, level).append("\"edges\": [");
        first = true;
        for (EndpointPair<ExecutorIdentity> pair : graph.edges()) {
            if (!first) json.append(", ");
            first = false;
            List<TaskEdge> edges = graph.edgeValueOrDefault(pair.source(), pair.target(), Collections.emptyList());
            json.append("{\"source\": ").append(quote(pair.source().toString()));
            json.append(", \"target\": ").append(quote(pair.target().toString()));
            json.append(", \"metadata\": ").append(metadataJson(edges));
            json.append('}');
        }
        json.append("]\n");
        indent(json, level - 1).append('}');
        return json.toString();
    }

    private static String metadataJson(List<TaskEdge> edges) {
        StringBuilder json = new StringBuilder();
        json.append('[');
        boolean first = true;
        for (TaskEdge edge : edges) {
            if (!first) json.append(", ");
            first = false;
            json.append('{');
            json.append("\"parallelism\": ").append(edge.getParallelism());
            json.append(", \"taskType\": ").append(quote(String.valueOf(edge.getTaskType())));
            json.append(", \"executorName\": ").append(quote(edge.getExecutorName()));
            json.append(", \"sourceExecutorName\": ").append(quote(edge.getSourceExecutorName()));
            json.append(", \"taskCount\": ").append(edge.getTaskCount());
            json.append(", \"timeoutMillis\": ").append(edge.getTimeoutMillis());
            json.append(", \"deadlockProne\": ").append(edge.isExecutorDeadlockProne());
            json.append(", \"executorIdentity\": ")
                    .append(quote(edge.getExecutorIdentity() == null ? null : edge.getExecutorIdentity().toString()));
            json.append(", \"sourceExecutorIdentity\": ")
                    .append(quote(edge.getSourceExecutorIdentity() == null
                            ? null
                            : edge.getSourceExecutorIdentity().toString()));
            json.append('}');
        }
        json.append(']');
        return json.toString();
    }

    private static StringBuilder indent(StringBuilder json, int level) {
        for (int i = 0; i < level; i++) json.append("  ");
        return json;
    }

    private static void field(StringBuilder json, int level, String name, String value) {
        indent(json, level).append(quote(name)).append(": ").append(value);
    }

    private static String quote(String value) {
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

    // ==================== helpers ====================

    private static TaskEdge legacyEdge(String source, String target, boolean deadlockProne) {
        return new TaskEdge(1, TaskType.CPU_BOUND, target, source, 1, 0, deadlockProne);
    }

    private static TaskEdge identityEdge(
            ExecutorIdentity source, ExecutorIdentity target, String targetName, String sourceName) {
        return new TaskEdge(1, TaskType.CPU_BOUND, target, source, targetName, sourceName, 1, 0, true);
    }

    @SuppressWarnings("unchecked")
    private static ValueGraph<ExecutorIdentity, List<TaskEdge>> identityGraphOf(TaskGraph.Data data) {
        try {
            Method method = TaskGraph.Data.class.getDeclaredMethod("generateExecutorIdentityGraph");
            method.setAccessible(true);
            return (ValueGraph<ExecutorIdentity, List<TaskEdge>>) method.invoke(data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labelsOf(TaskGraph.Data data) {
        try {
            Field field = TaskGraph.Data.class.getDeclaredField("nodeLabels");
            field.setAccessible(true);
            return (Map<String, String>) field.get(data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

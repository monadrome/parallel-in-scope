package integrations.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Checkpoints;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SentinelGuardTest {

    private static final List<Integer> SIX = Arrays.asList(0, 1, 2, 3, 4, 5);

    private ExecutorService pool;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void setUp() {
        pool = new ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        runtime = ParRuntime.builder().register(ParId.of("io"), pool).build();
        par = runtime.par(ParId.of("io"));
    }

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(Collections.emptyList());
        runtime.close();
        pool.shutdownNow();
    }

    @Test
    void threadRuleDegradesTheElementsBeyondTheLimit() throws Exception {
        FlowRule rule = new FlowRule("thread-limited");
        rule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        rule.setCount(2);
        FlowRuleManager.loadRules(Collections.singletonList(rule));
        CountDownLatch held = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holders = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> holding = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                holding.add(holders.submit(() -> SentinelGuard.call(
                        "thread-limited",
                        () -> {
                            held.countDown();
                            awaitRelease(release);
                            return "live";
                        },
                        rejection -> "fallback")));
            }
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            TaskBatchResult<String> batch = par.map(
                    SIX,
                    i -> SentinelGuard.call("thread-limited", () -> "live", rejection -> "fallback"),
                    BatchOptions.timeout("thread-limited", Duration.ofSeconds(5))
                            .parallelism(6));

            assertThat(batch.outcomeCounts()).containsEntry(TaskOutcome.SUCCESS, 6);
            assertThat(batch.valuesOrThrow()).containsOnly("fallback");
            release.countDown();
            for (Future<String> holder : holding) {
                assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("live");
            }
        } finally {
            release.countDown();
            holders.shutdownNow();
        }
        assertThat(SentinelGuard.call("thread-limited", () -> "live", rejection -> "fallback"))
                .isEqualTo("live");
    }

    @Test
    void failFastSiblingsAreNotTracedAsExceptions() {
        CountDownLatch siblingsBlocked = new CountDownLatch(3);

        TaskBatchResult<String> batch = par.map(
                Arrays.asList(0, 1, 2, 3),
                i -> SentinelGuard.call(
                        "traced",
                        () -> {
                            if (i == 0) {
                                Checkpoints.checkAwait(siblingsBlocked, Duration.ofSeconds(5));
                                throw new IllegalStateException("downstream failed for element 0");
                            }
                            siblingsBlocked.countDown();
                            Checkpoints.checkSleep(20_000, TimeUnit.MILLISECONDS);
                            return "unreachable";
                        },
                        rejection -> "fallback"),
                BatchOptions.timeout("traced", Duration.ofSeconds(10))
                        .parallelism(4)
                        .closeGrace(Duration.ofSeconds(5)));

        assertThat(batch.outcomeCounts())
                .containsEntry(TaskOutcome.USER_FAILURE, 1)
                .containsEntry(TaskOutcome.FAIL_FAST, 3);
        assertThat(batch.bodyCompletionConfirmed()).isTrue();
        ClusterNode node = ClusterBuilderSlot.getClusterNode("traced");
        assertThat(node.totalException()).isEqualTo(1);
        assertThat(node.curThreadNum()).isZero();
    }

    @Test
    void originRulesApplyOnWorkersOnlyWhenTheOriginIsPassedIn() {
        FlowRule rule = new FlowRule("by-origin");
        rule.setLimitApp("batch-job");
        rule.setCount(0);
        FlowRuleManager.loadRules(Collections.singletonList(rule));

        List<String> withOrigin = par.map(
                        Arrays.asList("batch-job", "web"),
                        caller -> SentinelGuard.callWithOrigin(
                                "orders-api",
                                caller,
                                "by-origin",
                                () -> caller + ":live",
                                rejection -> caller + ":blocked"),
                        BatchOptions.timeout("with-origin", Duration.ofSeconds(5)))
                .valuesOrThrow();
        List<String> withoutOrigin = par.map(
                        Arrays.asList("batch-job"),
                        caller -> SentinelGuard.call(
                                "by-origin", () -> caller + ":live", rejection -> caller + ":blocked"),
                        BatchOptions.timeout("without-origin", Duration.ofSeconds(5)))
                .valuesOrThrow();

        assertThat(withOrigin).containsExactly("batch-job:blocked", "web:live");
        assertThat(withoutOrigin).containsExactly("batch-job:live");
    }

    @Test
    void enteredContextDoesNotStayOnThePooledThread() throws Exception {
        ExecutorService single = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ParRuntime singleRuntime =
                ParRuntime.builder().register(ParId.of("single"), single).build();
        try {
            singleRuntime
                    .par(ParId.of("single"))
                    .map(
                            Arrays.asList("a", "b"),
                            id -> SentinelGuard.callWithOrigin(
                                    "orders-api", "web", "pooled", () -> id, rejection -> "fallback"),
                            BatchOptions.timeout("pooled", Duration.ofSeconds(5)))
                    .orThrow();

            Context leftover = single.submit(ContextUtil::getContext).get(5, TimeUnit.SECONDS);
            assertThat(leftover).isNull();
        } finally {
            singleRuntime.close();
            single.shutdownNow();
        }
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the entry", interrupted);
        }
    }

    @Test
    void existingCallerContextIsKeptAndLeftInPlace() {
        ContextUtil.enter("outer", "app");
        try {
            String origin = SentinelGuard.callWithOrigin(
                    "orders-api",
                    "web",
                    "inline",
                    () -> ContextUtil.getContext().getOrigin(),
                    rejection -> "fallback");

            assertThat(origin).isEqualTo("app");
            assertThat(ContextUtil.getContext().getName()).isEqualTo("outer");
        } finally {
            ContextUtil.exit();
        }
    }
}

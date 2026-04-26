package demo.article;

import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5 分钟快速上手——配套测试代码。
 *
 * <p>单个测试方法，按文章的 5 个步骤顺序执行，验证每一步都能正常工作。
 */
class QuickStartTest {

    private ExecutorService pool;
    private Par par;

    @BeforeEach
    void setUp() {
        // 步骤 1 的准备工作：创建线程池和 Par 实例
        pool = Executors.newFixedThreadPool(4);
        ParConfig config = ParConfig.builder()
                .executor("my-pool", pool)
                .build();
        par = new Par(config);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickStart_walkthrough() throws Exception {
        List<Integer> numbers = Arrays.asList(1, 2, 3);

        // ---- 步骤 2：最小示例 ----
        ParOptions minimalOpts = ParOptions.of("square").build();
        AsyncBatchResult<Integer> result1 = par.map("my-pool", numbers, n -> n * n, minimalOpts);

        // 验证：逐个获取结果
        assertThat(result1.getResults()).hasSize(3);
        assertThat(result1.getResults().get(0).get()).isEqualTo(1);
        assertThat(result1.getResults().get(1).get()).isEqualTo(4);
        assertThat(result1.getResults().get(2).get()).isEqualTo(9);

        // ---- 步骤 3：设置超时 ----
        ParOptions timeoutOpts = ParOptions.of("square")
                .timeout(500)
                .build();
        AsyncBatchResult<Integer> result2 = par.map("my-pool", numbers, n -> n * n, timeoutOpts);

        // 验证：超时设置下仍然能正常完成
        for (Future<Integer> future : result2.getResults()) {
            Integer value = future.get(5, TimeUnit.SECONDS);
            assertThat(value).isPositive();
        }

        // ---- 步骤 4：控制并发度 ----
        ParOptions limitedOpts = ParOptions.of("process")
                .parallelism(2)
                .timeout(5000)
                .build();
        List<Integer> bigList = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8);
        AsyncBatchResult<Integer> result3 = par.map("my-pool", bigList, n -> n * 2, limitedOpts);

        // 验证：并发限制下所有结果正确
        assertThat(result3.getResults()).hasSize(8);
        for (int i = 0; i < bigList.size(); i++) {
            assertThat(result3.getResults().get(i).get()).isEqualTo(bigList.get(i) * 2);
        }

        // ---- 步骤 5：查看结果 ----
        // reportString() 快速概览
        String report = result3.reportString();
        assertThat(report).contains("SUCCESS:8");

        // report() 结构化报告
        AsyncBatchResult.BatchReport batchReport = result3.report();
        assertThat(batchReport.getStateCounts()).containsEntry(
                io.github.huatalk.parallelinscope.internal.FutureState.SUCCESS, 8);
        assertThat(batchReport.getFirstException()).isNull();
    }
}

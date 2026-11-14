package demo.article;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
        ParRuntime config = ParRuntime.builder()
                .register(ParId.of("my-pool"), pool)
                .defaultPar(ParId.of("my-pool"))
                .build();
        par = config.defaultPar();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickStart_walkthrough() {
        List<Integer> numbers = Arrays.asList(1, 2, 3);

        // ---- 步骤 2：最小示例 ----
        BatchOptions minimalOpts = BatchOptions.timeout("square", java.time.Duration.ofMillis(5000));
        TaskBatchResult<Integer> result1 = par.map(numbers, n -> n * n, minimalOpts);

        // 验证：聚合读取 valuesOrThrow()（0.4 起为非受检），按输入顺序返回全部值
        assertThat(result1.valuesOrThrow()).containsExactly(1, 4, 9);

        // ---- 步骤 3：设置超时 ----
        BatchOptions timeoutOpts = BatchOptions.timeout("square", java.time.Duration.ofMillis(500));
        TaskBatchResult<Integer> result2 = par.map(numbers, n -> n * n, timeoutOpts);

        // 验证：超时设置下仍然能正常完成
        for (Integer value : result2.valuesOrThrow()) {
            assertThat(value).isPositive();
        }

        // ---- 步骤 4：控制并发度 ----
        BatchOptions limitedOpts = BatchOptions.timeout("process", java.time.Duration.ofMillis(5000))
                .parallelism(2);
        List<Integer> bigList = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8);
        TaskBatchResult<Integer> result3 = par.map(bigList, n -> n * 2, limitedOpts);

        // 验证：并发限制下所有结果正确
        List<Integer> expected = Arrays.asList(2, 4, 6, 8, 10, 12, 14, 16);
        assertThat(result3.valuesOrThrow()).containsExactlyElementsOf(expected);

        // ---- 步骤 5：查看结果 ----
        // reportString() 快速概览
        String report = result3.reportString();
        assertThat(report).contains("SUCCESS:8");

        // terminationKindCounts() / failedTaskResult() 结构化读取
        assertThat(result3.terminationKindCounts())
                .containsEntry(io.github.monadrome.parallelinscope.TaskTermination.Kind.SUCCESS, 8);
        assertThat(result3.failedTaskResult()).isEmpty();
    }
}

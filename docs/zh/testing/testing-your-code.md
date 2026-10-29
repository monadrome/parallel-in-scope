# 测试使用本库的代码

本库没有可注入的时钟，以后也不会提供：公开的时间源只能虚拟化一半相关时间，生产环境误配置还会拿走
同步 `map`/`runAll` 唯一的活性保证。理由见
[决策日志](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/design/decision-log.md)。
超时路径仍然可以确定性地测试，本页说明做法。

## 注入 timeout，而不是时钟

把 timeout 作为组件配置的一部分。生产传真实值；测试传几十毫秒，再配一个阻塞到被中断为止的桩。

```java
final class PriceService {
    static final String UNKNOWN = "unknown";

    private final Par par;
    private final PriceClient client;
    private final BatchOptions options;

    PriceService(Par par, PriceClient client, BatchOptions options) {
        this.par = par;
        this.client = client;
        this.options = options;
    }

    String price(String sku) throws ExecutionException {
        ImmediateResult<String> result =
                par.map(Collections.singletonList(sku), client::fetch, options).results().get(0);
        return result.outcome() == TaskOutcome.SUCCESS ? result.valueOrThrow() : UNKNOWN;
    }
}

@Test
void fallsBackWhenTheClientHangs() throws ExecutionException {
    PriceClient hanging = sku -> {
        Checkpoints.checkAwait(new CountDownLatch(1));
        throw new AssertionError("released");
    };
    PriceService service = new PriceService(
            runtime.defaultPar(), hanging, BatchOptions.timeout("price", Duration.ofMillis(50)));

    assertEquals(PriceService.UNKNOWN, service.price("sku-1"));
}
```

（`valueOrThrow()` 声明了 `ExecutionException`，按你的代码库惯例处理。）

保持确定性的规则：

- 桩通过 `Checkpoints.checkAwait` 阻塞在 latch 上，它会响应取消中断。不要用 `Thread.sleep` 模拟慢调用，
  也不要用不响应中断的等待：那样 body 会在 deadline 之后继续占着 worker。
- 断言结局（`TaskOutcome.TIMEOUT` 或你的回退值），不断言耗时。
- 测试需要知道 body 已经开始时，在 body 里释放第二个 latch 并等待它，不靠时序。
- body 在取消后失败时库会记一条 warning；那是桩经由中断退出，不是测试失败。

## 断言 body 已经退出

`bodyCompletionConfirmed()` 表示调用返回前所有 body 都已退出。超时后，由 deadline 派生的清理预算已经
用完，不显式设宽限期时，调用会在 deadline 触发后立即返回，这个检查会和桩的退出赛跑。测试要断言退出时，
显式设置 `closeGrace`：

```java
BatchOptions options = BatchOptions.timeout("price", Duration.ofMillis(50))
        .closeGrace(Duration.ofSeconds(1));
```

宽限期是等待上限而不是延迟：收到中断就退出的桩仍在几十毫秒内返回。

## 读取剩余预算的逻辑

按 `Checkpoints.remaining()` 分支的代码（例如剩余不足一秒时跳过可选调用）应通过自己的小接口读取，
测试里替换成固定值：

```java
interface Budget {
    Optional<Duration> remaining();
}

Budget production = Checkpoints::remaining;
Budget almostExpired = () -> Optional.of(Duration.ofMillis(10));
```

## 只消费结果的代码

只消费结果的组件，单测完全不需要 runtime。把 `Par` 放在你代码库自己的接口后面，测试替身直接返回
固定结果：

```java
ImmediateResult<String> timedOut =
        ImmediateResult.failed(TaskOutcome.TIMEOUT, new TimeoutException("stub"));
```

至少保留一个用真实 `Par` 加注入 timeout 的测试，覆盖你的选项与库之间的接线。

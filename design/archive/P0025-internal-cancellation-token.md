# CancellationToken 收为包私有内核类型

## 蒸馏摘要

- 核心出发点：`CancellationToken` 与 `CancellationToken.State` 是公开类型，但没有任何公开成员接收或
  返回它们。用户自建的 token 与库内工作没有连接：公开构造链传入的定时调度器为 `null`，deadline 永不
  触发；`cancel()` 停不了任何东西。公开的类型只制造「我手里有取消句柄 / deadline 载体」的错觉，并与
  `TaskOutcome` 形成两套重叠的归因词汇。
- 拟议 MUST / MUST NOT：`CancellationToken` 与 `State` MUST 为包私有；公开归因 MUST 只用
  `TaskOutcome`；公开 javadoc 与用户文档 MUST NOT 链接或教授 token；不提供公开替代类型。外部取消
  一次运行的需求由 [P0001](P0001-request-cancellation.md) 以显式取消输入承接，MUST NOT 通过重新公开
  token 实现。
- 被否决方向及理由：保持公开并在 javadoc 写「internal」——Java 8 没有模块边界，根包的包私有就是封装
  边界，公开却无入口的类型会继续误导；只删公开构造器——仍是拿不到实例的公开类型；补 `map(..., token)`
  入口——等于给同步出口加公开运行句柄，并把内核状态机（父链、deadline、bind、归因）暴露给用户。

## 用户代码

改前，能编译、能运行、但什么都不做的写法（类 javadoc 写着取消会传播到子任务组，读者有理由这么写）：

```java
CancellationToken request = new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
onClientDisconnect(request::cancel);
TaskBatchResult<Price> prices = par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(5)));
```

改后，同一意图的最好写法不碰 token（今天就能这样写）：

```java
TaskBatchResult<Price> prices = par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(2)));
```

body 内观察取消与剩余预算用 `Checkpoints.checkpoint()` / `Checkpoints.remaining()`；提前终止其余元素让
body 抛异常走 fail-fast。被消除的失败模式：以为手里有一个取消句柄或 deadline 载体，实际上它与执行无关。

## 迁移

破坏性变更（`CancellationToken` 自 0.2.0 公开），迁移条目进 `docs/{en,zh}/migration-v0.4.md`：deadline
改用 `BatchOptions.timeout` / `TaskOptions.timeout` / `ParRuntime.group(name, timeout)`；归因读
`TaskOutcome`；外部取消运行中的执行目前没有公开入口（P0001 在途）。

## 波及面

- `PublicApiSurfaceTest` 删除 `CancellationToken` 与 `CancellationToken$State` 两行；同包测试不受影响。
- 公开 javadoc 中指向 token 的 `{@link}`（`Par`、`Checkpoints`、`ParRuntime`）改为直接陈述。
- 用户文档：nullability 参考（中英）的公开 `@Nullable` 示例换掉；`cooperative-cancellation.md` 与
  `philosophy.md` 改称内部取消 token；已发布版本的迁移指南（0.2/0.3）作为历史记录保留。
- `create()` 与无参构造器随类型一起降为包私有并保留：同包测试有 72 处用它们构造根 token，删除只换来
  机械改写，不改变任何公开面。

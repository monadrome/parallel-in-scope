# FuturePath 分阶段结果处理 API

## 蒸馏摘要

- 核心出发点：用分阶段 API 取代调用方直接读取 `Future.State`、`resultNow()`、`exceptionNow()` 等状态查询方法，把一次结果处理表达为编译期可检查的完整路径。
- 形状：`FuturePath` 可以从 `ListenableFuture<T>` 或 `ImmediateResult<T>` 创建；两种入口共享结果分派阶段，但分别以异步 Future 或同步 Result 作为终点，不能互相伪装。
- 拟议 MUST：
  - 路径 MUST 依次处理 `SUCCESS`、`USER_FAILURE`、`SUBMISSION_FAILURE` 和取消类终态；每个阶段只能进入下一个阶段，不能跳过、重复或重排。
  - `SUCCESS` 阶段接收 nullable 值；两个失败阶段接收原始 `Throwable`；取消阶段接收 `TaskOutcome` 与可选的详细 Throwable。
  - 取消阶段 MUST 覆盖 `MEMBER_CANCELLED`、`GROUP_CANCELLED`、`FAIL_FAST`、`TIMEOUT`，但不得要求用户为每个内部取消原因分别注册处理器。
  - 从 Future 创建的路径 MUST 保持异步语义并产生 `ListenableFuture<R>`；从 Result 创建的路径 MUST 保持同步语义并产生 `ImmediateResult<R>`。
  - 阶段链在最后一个处理器注册前 MUST 不可结束，从而让遗漏分支成为编译期错误，而不是运行时默认分支。
- 被否决方向：继续暴露状态查询组合（仍要求用户记住状态读取顺序）；为每个取消枚举值建立独立阶段（把内部枚举变成不稳定的公开 API）；让同步 Result 自动包装成 Future（丢失同步语义）；单一 `OutcomePath` 终点同时返回 Future 和 Result（Java 类型表达不自然，调用方难以判断执行模型）。

本提案消费的任务终止语义由[任务终止与 scope 原因契约](task-termination-and-scope.md)定义。
`FuturePath` 的取消阶段处理 `CancellationReason` 和 `ExecutionDisposition`，不得重新公开
`GROUP_CANCELLED`、`MEMBER_CANCELLED` 或 `FAIL_FAST` 作为新的稳定原因。

## 1. 失败模式与改前最佳代码

当前调用方需要先读取状态，再调用与状态匹配的查询方法。异步场景还必须记住哪些方法允许调用、哪些方法会抛异常，以及取消原因是否携带可用的详细信息：

```java
if (future.state() == Future.State.SUCCESS) {
    Price price = future.resultNow();
    return price;
}
if (future.state() == Future.State.FAILED) {
    Throwable failure = future.exceptionNow();
    return fallback(failure);
}
return Price.UNKNOWN;
```

这段代码存在三个问题：状态读取与结果读取是两套 API；遗漏状态只能靠运行时兜底；用户还必须自行决定 `USER_FAILURE`、`SUBMISSION_FAILURE` 和取消类终态如何归类。

## 2. 改后用户代码

Future 入口：

```java
ListenableFuture<Price> price = FuturePath.fromFuture(future)
        .onSuccess(value -> value)
        .onUserFailure(error -> fallback(error))
        .onSubmissionFailure(error -> Price.UNKNOWN)
        .onCancelled((outcome, detail) -> Price.UNKNOWN)
        .toFuture();
```

Result 入口：

```java
ImmediateResult<Price> price = FuturePath.fromResult(result)
        .onSuccess(value -> value)
        .onUserFailure(error -> fallback(error))
        .onSubmissionFailure(error -> Price.UNKNOWN)
        .onCancelled((outcome, detail) -> Price.UNKNOWN)
        .toResult();
```

处理器注册顺序由返回接口限制。调用方不能在注册完 `onSuccess` 后直接结束，也不能跳过 submission failure 阶段；最后一个 `onCancelled` 注册完成后，路径才具备终点。

## 3. API 形状

公开入口保留同一概念名，内部阶段使用不同的返回接口：

```java
public final class FuturePath {
    public static <T> FutureSuccessStage<T> fromFuture(
            ListenableFuture<T> source);

    public static <T> ResultSuccessStage<T> fromResult(
            ImmediateResult<T> source);
}
```

阶段接口的约束形状如下，具体泛型边界和 JSpecify 标注以实现时的 Java 8 可表达性为准：

```java
interface FutureSuccessStage<T> {
    <R> FutureUserFailureStage<R> onSuccess(
            Function<? super @Nullable T, ? extends R> handler);
}

interface FutureUserFailureStage<R> {
    FutureSubmissionFailureStage<R> onUserFailure(
            Function<? super Throwable, ? extends R> handler);
}

interface FutureSubmissionFailureStage<R> {
    FutureCancelledStage<R> onSubmissionFailure(
            Function<? super Throwable, ? extends R> handler);
}

interface FutureCancelledStage<R> {
    FutureTerminalStage<R> onCancelled(
            BiFunction<? super CancellationReason, ? super ExecutionDisposition, ? extends R> handler);
}

interface FutureTerminalStage<R> {
    ListenableFuture<R> toFuture();
}
```

Result 入口使用相同的四个处理阶段，最后阶段返回只提供 `toResult()` 的终点接口，最终得到 `ImmediateResult<R>`。内部可以复用现有 `ImmediateResult` 的 outcome 分派，但不得复用 Future 的等待、取消或异常提取路径。

## 4. 终态分类与穷尽性边界

本提案的穷尽性针对用户可处理的稳定语义，而不是针对当前枚举的每一个常量：

| 公共阶段 | 覆盖的 `TaskOutcome` |
| --- | --- |
| `onSuccess` | `SUCCESS` |
| `onUserFailure` | `USER_FAILURE` |
| `onSubmissionFailure` | `SUBMISSION_FAILURE` |
| `onCancelled` | `CancellationReason`（含 source identity）与 `ExecutionDisposition` |

`RUNNING` 不是可交付给路径的终态。若内部出现未完成状态，路径实现 MUST 报告库内部错误，而不是调用用户处理器。

取消处理器收到稳定的 `CancellationReason` 和 claim disposition；业务失败保留在 `FailureCause` 和 scope
成员结果中，不通过取消阶段伪装成第二个 scope 原因。

## 5. 同步与异步语义

- `fromFuture` MUST 不阻塞调用线程；它注册一次完成驱动的分派，并返回派生的 `ListenableFuture<R>`。
- `fromResult` MUST 只读取不可变快照，不重新等待、不启动执行、不改变原 Result 的生命周期。
- 两种入口的 handler 调用顺序和 outcome 分类 MUST 一致；差异仅限于执行时机和终点类型。
- handler 抛出的异常遵循各自终点的既有语义：Future 入口将其转换为派生 Future 的失败，Result 入口不能在已构造的 Result 上伪造异步失败，应明确规定是直接抛出还是返回失败 Result。

## 6. 迁移路径

现有 `ImmediateResult.fold(onValue, onFailure, onCancelled)` 可以迁移为 staged API：

```java
FuturePath.fromResult(result)
        .onSuccess(onValue)
        .onUserFailure(onFailure)
        .onSubmissionFailure(onFailure)
        .onCancelled(onCancelled)
        .toResult();
```

迁移期间可以保留旧 `fold` 作为 deprecated 适配层，但新文档和示例只展示 `FuturePath`。适配层不得重新读取 Future 状态；它只把旧的三个 handler 转换为四阶段路径，其中两个 failure 阶段共用原 `onFailure`。

## 7. 被排除的替代方案

- **继续使用 `Future.State` 加状态查询**：不能消除调用方记忆状态与查询方法配对的错误，也不能形成编译期穷尽性检查。
- **每个取消原因一个阶段**：把内部归因枚举直接暴露给用户，新增取消原因会造成不必要的源代码破坏。
- **一个 `onFailure` 覆盖两种失败**：保留了当前 API 对 submission failure 与 user failure 的语义丢失，调用方仍需重新读取 outcome。
- **统一返回 `ListenableFuture<R>`**：Result 入口被迫异步化，破坏不可变同步快照的语义和调用方的性能预期。
- **允许任意顺序的链式注册**：只有语法变化，没有编译期完整性保证，并且会增加重复处理和覆盖处理的运行时规则。

## 8. 第一性原理评估

1. 消除的忘记：忘记处理失败类别、误用状态读取方法、在不合法状态上调用结果查询。
2. 机制复用：复用 `TaskOutcome`、`ImmediateResult` 和现有 Future 完成传播，不新增执行内核。
3. 结构化安全：Future 入口沿用既有取消与完成传播；Result 入口只处理终态快照，不引入第二套生命周期。
4. 无隐式状态：路径显式持有 source 和 handler，不使用 ambient 状态。
5. 归因一致：公共阶段建立在 `TaskTermination` 的三分支之上，取消原因和 claim disposition 分别表达
   触发来源与执行事实。
6. 抽象边界：该能力只负责结果投影与分派，不改变 Batch、Group、取消或 deadline 契约。

## 9. 落地前需要定稿的问题

- Future 入口的派生 Future 是否沿用现有监听器执行器，还是提供显式 executor 重载。
- Result 入口 handler 抛出异常时，是返回 `ImmediateResult` 失败快照，还是保持同步 API 的直接抛出语义。
- 旧 `fold` 的 deprecation 周期，以及是否在 `0.4.0` 直接移除。
- staged 接口是否作为公开顶层类型，还是作为 `FuturePath` 的嵌套接口以减少公共包表面积。

这些问题不影响本提案的核心约束：结果处理必须通过完整的 staged path 完成，Future 与 Result 的终点语义必须保持分离。

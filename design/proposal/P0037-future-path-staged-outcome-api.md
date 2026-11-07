# ImmediateResult 四类终态 fold

## 蒸馏摘要

- 核心出发点：结果处理需要直接表达成功、用户代码失败、提交失败和取消四种稳定公共语义，避免调用方在一个合并的 failure 分支中重新读取 `outcome()`。
- 形状：保留 `ImmediateResult.fold` 作为一次性纯值分派 API，将现有三参数签名扩展为四个处理器；不新增 `FuturePath`、staged builder 或普通 `ListenableFuture` 入口。
- 拟议 MUST：
  - `SUCCESS` MUST 调用 `onValue`，并允许传入 null 值。
  - `USER_FAILURE` MUST 只调用 `onUserFailure`；`SUBMISSION_FAILURE` MUST 只调用 `onSubmissionFailure`。
  - `MEMBER_CANCELLED`、`GROUP_CANCELLED`、`FAIL_FAST`、`TIMEOUT` MUST 统一调用 `onCancelled`，并传递具体 `TaskOutcome` 与存储的 Throwable。
  - 四个处理器 MUST 非 null；`fold` MUST 只读取冻结结果，不等待、不消费中断、不改变执行生命周期。
  - `RUNNING` 或未来未归类的 outcome MUST 触发 `AssertionError`，不得落入默认用户分支。
- 被否决方向：从普通 `ListenableFuture<T>` 推断 `TaskOutcome`（普通 Future 没有来源归因）；FuturePath staged 链（可在终点前被丢弃，不能提供真正的编译期穷尽性）；按每个取消枚举建立阶段（暴露不稳定内部枚举）；返回新的 `ImmediateResult<R>`（与现有 `fold` 的 `R` 返回语义不等价）。

## 1. 失败模式与改前最佳代码

现有 API 将 `USER_FAILURE` 与 `SUBMISSION_FAILURE` 合并到 `onFailure`。调用方要区分“用户代码抛错”和“用户代码根本没有运行”，必须再次读取 `outcome()`：

```java
Price price = result.fold(
        value -> value,
        failure -> {
            if (result.outcome() == TaskOutcome.SUBMISSION_FAILURE) {
                return Price.UNKNOWN;
            }
            return fallback(failure);
        },
        (outcome, detail) -> Price.UNKNOWN);
```

这会把一次结果分派变成两次状态读取，也容易让基础设施失败被按业务失败处理。

## 2. 改后用户代码

```java
Price price = result.fold(
        value -> value,
        userFailure -> fallback(userFailure),
        submissionFailure -> Price.UNKNOWN,
        (outcome, detail) -> Price.UNKNOWN);
```

`onUserFailure` 只接收用户 Callable 已经开始执行后抛出的异常；`onSubmissionFailure` 只接收用户代码运行前的提交、交接或 executor 拒绝失败。取消类终态继续保留具体 `TaskOutcome`，不要求调用方为每种取消原因分别注册处理器。

## 3. API 契约

```java
public <R> R fold(
        Function<? super @Nullable T, ? extends R> onValue,
        Function<? super Throwable, ? extends R> onUserFailure,
        Function<? super Throwable, ? extends R> onSubmissionFailure,
        BiFunction<? super TaskOutcome, ? super Throwable, ? extends R> onCancelled);
```

分派表：

| `TaskOutcome` | 处理器 |
| --- | --- |
| `SUCCESS` | `onValue(value)` |
| `USER_FAILURE` | `onUserFailure(failure)` |
| `SUBMISSION_FAILURE` | `onSubmissionFailure(failure)` |
| `MEMBER_CANCELLED`、`GROUP_CANCELLED`、`FAIL_FAST`、`TIMEOUT` | `onCancelled(outcome, failure)` |

所有非成功终态都携带非 null Throwable。取消处理器收到的 Throwable 不保证是 `CancellationException`；组级 `valuesResult()` 可能保留已记录的业务失败，手动 `failed(outcome, failure)` 保留调用方提供的异常。

## 4. 迁移路径

旧调用：

```java
result.fold(onValue, onFailure, onCancelled);
```

新调用：

```java
result.fold(onValue, onUserFailure, onSubmissionFailure, onCancelled);
```

`0.4.0` 不保留三参数兼容重载。若调用方没有区分两类失败的需求，可以显式复用同一个函数：

```java
result.fold(onValue, onFailure, onFailure, onCancelled);
```

## 5. 为什么不引入 FuturePath

普通 `ListenableFuture<T>` 只提供成功、失败和取消，不提供失败来源或取消归因。通过异常类型、`isCancelled()` 或 live token 状态反推 `TaskOutcome` 会破坏项目按收敛事件归因的契约。Future 入口还必须额外定义 executor、下游取消传播、handler 执行时机和重复终点调用。

staged 链也不能保证调用方写到最后一步：中途返回值可以被丢弃，Java 8 没有办法把“必须调用最终阶段”变成绝对编译错误。现有一次性 `fold` 更小、更直接，并且没有第二套生命周期。

## 6. 第一性原理评估

1. 消除的忘记：消除在统一 failure 分支里重新读取 outcome，以及把提交失败误当成业务失败。
2. 机制复用：只参数化现有 `ImmediateResult` 分派，不新增执行内核、Future 管道或公共阶段类型。
3. 结构化安全：只读取已冻结快照，不改变取消、deadline、上下文和执行退出语义。
4. 无隐式状态：所有输入显式传入，分派不依赖 ThreadLocal 或 live future 状态。
5. 归因一致：四个处理器直接对应现有 `TaskOutcome` 的稳定公共分类；取消原因仍通过 outcome 数据传递。
6. 抽象边界：能力只属于结果容器，不扩大 `ListenableFuture`、Batch 或 Group 的职责。

## 7. 验证与落地

实现必须覆盖成功（含 null）、用户失败、提交失败、四种取消、所有 handler 的 null 拒绝，以及未来未归类 outcome 的断言失败。用户指南和中英文 v0.4 迁移指南同步更新；现有三参数调用必须全部迁移。完成实现后运行格式检查和完整测试，并按公开 API 变更流程进行独立评审。

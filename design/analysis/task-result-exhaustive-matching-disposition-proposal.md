# `TaskResult` 穷尽匹配提案的处置

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未实现。`ImmediateResult` 没有 `successful()` 或 `fold`；剩余方向由在途提案 P0019 承接。

> 状态：**设计提案，待拍板**。对应最终清单 #22（采纳 / 收窄 / 关闭）。基线：dev/v0.3.0 HEAD dd12641。
> 处置对象是未跟踪的 `design/task-result-exhaustive-matching-proposal.md`（下称「原提案」，本文不改它）。
> 基于 #20（`ImmediateResult.toString()`）与 #1（`ImmediateResult.fromTask(task)` 单参）已落地来写。

## 0. 结论

**推荐收窄。** 关闭原提案的 `TaskResult` 新类型、`now/poll/await` 获取工厂、typestate 匹配链与
5 个可选细化；只在既有的 `ImmediateResult` 上加两个成员：`successful()` 与三路穷尽的
`fold(onValue, onFailure, onCancelled)`。不新增公开类型，纯增量，无迁移。

若维护者认为 `fold` 不值得加，退路是只加 `successful()`；整体关闭也说得通，理由见 6.3。

## 1. 原提案的前提今天是否成立

| 原提案前提 | 今天 | 处置 |
|---|---|---|
| 用户持有活体 `TaskFuture`（§1 例子是 `par.submit(...)` 返回 `TaskFuture`） | `TaskFuture`/`Task` 包私有；`Par.submit` 包私有且主源码零调用；`PublicApiSurfaceTest` 断言 `Par` 不暴露 `submit`/`submitBatch`、结果类型都不是 `Future` | 场景不存在 |
| §2.1 `failure()` 带中断标志抛异常 | HEAD 的 `FutureInspector.exceptionNow` 仍用 `future.get()`（`FutureInspector.java:31`），但只剩包私有路径，用户可达处经 `Par.map` 冻结——由 #1（修复树改读 `Futures.getDone`）承载 | 不需要新类型 |
| §2.2 `outcome()`/`failure()` 两次读取矛盾 | 冻结的 `ImmediateResult` 自洽（opus 探针 Q2）；活体双读只在包私有类型上。FINAL「不建议做」已 DROP | 不成立 |
| §7.1 需要一个不可变终态快照类型 | `ImmediateResult` 就是它（`outcome()/failure()/valueOrThrow()/asFuture()`，非成功必带 throwable） | 不另造 `TaskResult` |
| §7.1 对任意 `Future` 做 `now/poll/await` 快照 | 公开面上没有库产出的活体 future（`asFuture()` 是已完成的适配器）；对用户自己的 future 用 `isCancelled()` 等「保守映射」造 `TaskOutcome`，等于事后猜测归因——违反 `first-principles.md`「归因在收敛时读 token 状态，不靠 `isCancelled()` 事后猜测」 | 关闭 |
| §7.2 缺一个「漏分支编译不过」的穷尽消费结构 | 仍然缺：`ImmediateResult` 只有 `outcome()` 与带受检异常的 `valueOrThrow()` | 收窄后保留 |

另：原提案用 `GROUP_CANCELED`/`MEMBER_CANCELED`/`onCanceled`，代码里是 `GROUP_CANCELLED`/
`MEMBER_CANCELLED`；按 AGENTS.md 拼写规则，新增成员也用双 l。

## 2. 现状下用户能写出的最好代码

fail-fast 批次里「部分成功」是常态：一个元素失败，其余被级联取消。逐元素消费：

```java
Map<String, Price> prices = new LinkedHashMap<>();
List<ImmediateResult<Price>> results = batch.results();
for (int i = 0; i < results.size(); i++) {
    ImmediateResult<Price> result = results.get(i);
    Price price;
    switch (result.outcome()) {
        case SUCCESS:
            try {
                price = result.valueOrThrow();
            } catch (ExecutionException impossible) {
                throw new AssertionError(impossible);
            }
            break;
        case USER_FAILURE:
        case SUBMISSION_FAILURE:
            LOGGER.log(Level.WARNING, "price failed for " + skus.get(i), result.failure());
            price = Price.UNKNOWN;
            break;
        default:
            price = Price.UNKNOWN;
            break;
    }
    prices.put(skus.get(i), price);
}
```

调用方要自己知道哪两个值是「执行失败」、`valueOrThrow()` 的受检异常在成功分支不可能发生、
`default` 吸收了哪四个值。仓库自己的 demo 走了更短的路：`BatchProcessingDemo.java:76-84` 把
`asFuture().get()` 包进 `catch (Exception e)`，失败与取消一并记成 `-1`。

## 3. 改后的同一段代码

```java
Map<String, Price> prices = new LinkedHashMap<>();
List<ImmediateResult<Price>> results = batch.results();
for (int i = 0; i < results.size(); i++) {
    String sku = skus.get(i);
    Price price = results.get(i).fold(
            value -> value,
            failure -> {
                LOGGER.log(Level.WARNING, "price failed for " + sku, failure);
                return Price.UNKNOWN;
            },
            (outcome, detail) -> Price.UNKNOWN);
    prices.put(sku, price);
}
```

被消除的失败模式：

1. **把取消当失败。** fail-fast 下一次真失败带出 N−1 个级联取消；`!= SUCCESS` 或 `catch (Exception)`
   的写法把它们记成 N 次失败——告警与日志放大 N 倍，按「失败即重试」写的补偿逻辑把从未运行过的
   兄弟与真正失败的元素混在一起处理。实测（6.1）：6 元素批次，`catch (Exception)` 计 6 次失败，
   `fold` 得 `failure=1 cancelled=5`。
2. **成功分支的受检异常仪式。** 成功分支要处理一个不可能发生的 `ExecutionException`；这正是用户转向
   `catch (Exception)` 的原因。
3. **漏分支静默落空。** Java 8 的 enum `switch` 语句漏 case 不报错；`fold` 少一个参数编译不过。

迁移：无。纯新增，不删除、不改签名。

## 4. 确切签名

```java
public final class ImmediateResult<T> {

    public boolean successful() {
        return outcome == TaskOutcome.SUCCESS;
    }

    public <R> R fold(
            Function<? super @Nullable T, ? extends R> onValue,
            Function<? super Throwable, ? extends R> onFailure,
            BiFunction<? super TaskOutcome, ? super Throwable, ? extends R> onCancelled) {
        checkNotNull(onValue, "onValue cannot be null");
        checkNotNull(onFailure, "onFailure cannot be null");
        checkNotNull(onCancelled, "onCancelled cannot be null");
        switch (outcome) {
            case SUCCESS:
                return onValue.apply(value);
            case USER_FAILURE:
            case SUBMISSION_FAILURE:
                return onFailure.apply(verifyNotNull(failure));
            case MEMBER_CANCELLED:
            case GROUP_CANCELLED:
            case FAIL_FAST:
            case TIMEOUT:
                return onCancelled.apply(outcome, verifyNotNull(failure));
            case RUNNING:
            default:
                throw new AssertionError("immediate result has no terminal outcome: " + outcome);
        }
    }
}
```

设计要点：

- **分类不新造。** 「执行失败 = `USER_FAILURE`/`SUBMISSION_FAILURE`，其余非成功 = 取消类」是
  `TaskBatchResult.report()`/`valuesOrThrow()` 已经采用的切分（`TaskBatchResult.java:67-101`）；`fold`
  把它从聚合读取下放到逐元素。
- **分支依据是 outcome，throwable 是载荷。** `onCancelled` 拿到的 throwable 不保证是
  `CancellationException`：组级 `valuesResult()` 在已有记录失败时携带原业务异常，即使 outcome 是取消
  形状（`design/synchronous-scope-exit.md` §3 组级例外）；手工 `failed(outcome, failure)` 也保留给定异常。
- **细化不做成方法。** 原提案的 `onTimeout`/`onFailFast`/… 由 `onCancelled` 的 `TaskOutcome` 参数表达
  （`outcome == TaskOutcome.TIMEOUT ? … : …`）。新增 outcome 值只要落进三种形状之一，调用点不改——
  原提案「粗必填 + 细可选」的前向兼容性保留。
- **`onFailure` 不带 outcome。** 需要区分 `SUBMISSION_FAILURE` 时读 `result.outcome()`（`SubmissionException`
  包私有，不能 `instanceof`）。`onFailure` 与 `onCancelled` 形状不同，两者互换编译不过（6.1）。
- **`RUNNING` 与 `default` 显式落到 `AssertionError`**，不用 `default` 吸收取消类：新增 `TaskOutcome` 值时
  配一条遍历 `TaskOutcome.values()` 的测试先变红，迫使实现者显式归类。
- **命名 `fold`**：对齐 Vavr `Either.fold`、Kotlin `Result.fold` 的消去式语义。原提案的 `resolve()` 是 typestate
  终结方法，这里没有那一步。
- **空值**：`onValue` 接收可能为 null 的成功值（成功 null 合法）。NullAway 未开 JSpecify 模式
  （`pom.xml:141`），泛型实参上的 `@Nullable` 目前只起文档作用。
- **只放在 `ImmediateResult` 上。** `TaskCompletion` 是观测记录，已有 `successful()`；结局消费走对应的
  `ImmediateResult`（`results()`/`resultOf`），不开第二个消费入口。`successful()` 让两种逐任务记录共享
  同一个判定入口。

## 5. 备选形状：两步 typestate 链

```java
public <R> FailureStep<R> onValue(Function<? super @Nullable T, ? extends R> onValue);

public interface FailureStep<R> {
    CancellationStep<R> onFailure(Function<? super Throwable, ? extends R> handler);
}

public interface CancellationStep<R> {
    R onCancelled(BiFunction<? super TaskOutcome, ? super Throwable, ? extends R> handler);
}
```

调用点 `result.onValue(v -> v).onFailure(e -> UNKNOWN).onCancelled((o, d) -> UNKNOWN)`。两层嵌套 SAM，
用 lambda 实现，不需要实现类。实测（6.1）：步骤名防互换、跳步编译不过；但**在语句位置漏写
`.onCancelled(...)` 能编译，且一个 handler 都不执行**（赋值位置漏写才报类型不匹配）。`fold` 没有这个
洞，它的洞更窄（见 6.1 末行）。`fold` 少两个公开类型、洞更小，故推荐 `fold`。

## 6. 证据与不推荐的方向

### 6.1 实测

dd12641 归档副本加入第 4 节与第 5 节实现，JDK 25 `javac --release 8`：

| 形态 | 结果 |
|---|---|
| `r.fold(v -> v, e -> FALLBACK, (o, d) -> FALLBACK)`，成功值为 null | 编译通过，走 `onValue` 返回 null |
| `fold(String::valueOf, Throwable::getMessage, (o, d) -> o.name())` | 编译通过，`7` / `boom` / `TIMEOUT` |
| `fold(v -> v, e -> -1, (o, d) -> 0)` 推断 `R = int` 装箱 | 编译通过 |
| 少一个参数 | `method fold … cannot be applied to given types` |
| `onValue`/`onFailure` 互换且 lambda 用到参数 | `inference variable R has incompatible bounds` |
| 方法引用互换（`Throwable::getMessage` 放第一位） | `invalid method reference` |
| `onFailure`/`onCancelled` 互换 | `incompatible parameter types in lambda expression` |
| **`onValue`/`onFailure` 互换且两个 lambda 都不读参数**（`fold(e -> 1, v -> 0, (o, d) -> 2)`） | **编译通过**——`fold` 唯一的编译期洞 |
| 链：跳过 `onFailure` | `cannot find symbol` |
| 链：赋值位置漏写 `onCancelled` | `CancellationStep<Integer> cannot be converted to int` |
| **链：语句位置漏写 `onCancelled`** | **编译通过，handler 不执行** |
| 6 元素 fail-fast `Par.map`（1 个抛错，5 个被级联取消），逐元素消费 | `catch (Exception)` 计 6 次失败；`fold` 得 `value=0 failure=1 cancelled=5` |

`fold` 的洞只在「两个前置分支都不读参数且返回同型」时出现，典型是分类计数。javadoc 写明参数顺序；
调用点写成带名字的 lambda 参数（`value -> …`、`failure -> …`）能帮读者看出错位，但这只是文档，
不是类型保证。

### 6.2 不采纳原提案全文

- §1 场景不存在、§7.1 获取工厂对用户 future 伪造归因、`TaskResult` 与 `ImmediateResult` 重复（第 1 节）。
- §2 两处缺陷已分别由 #1 承载、或判定为非公开缺陷而 DROP。
- typestate 链 + 5 个细化 + `Matcher` 类型：细化表达的只是一个对 `TaskOutcome` 的三元判断；原提案自己
  实测的报错可读性代价（「找不到 `resolve()`」）对 4 个嵌套类型都成立。

### 6.3 不整体关闭

关闭的理由：逐元素消费是少数路径，聚合读取（`valuesOrThrow()`/`report()`/`outcomeCounts()`）覆盖多数
调用点；Java 14+ 用户可对 `TaskOutcome` 写穷尽的 `switch` 表达式。

不采纳的理由：库的源码基线是 Java 8，Java 8/11 调用方没有穷尽检查；穷尽 `switch` 表达式也要把
`RUNNING` 列进去（或写 `default`，穷尽性随之失去），且每新增一个 `TaskOutcome` 值就破坏所有调用点。
fail-fast 默认且不可关（idea-graveyard「可配置的失败策略」），「1 个失败 + N−1 个取消」是部分成功的
常态形状，而仓库自己的 demo 就把它压平了。

### 6.4 其他收窄方式

- **只加 `successful()` + 不受检的 `value()`。** `value()` 在失败时返回 null，与「成功 null 合法」冲突，
  调用方仍要先查 `successful()`，回到「记得先判断」。
- **给 `TaskOutcome` 加分类方法（`isCancellation()` 之类）。** 违反「不引入 `isX()` 访问器」约定；换名
  后也只是把 `switch` 换成 `if`，不提供穷尽性。

## 7. 六问（针对 `fold`）

| 判据 | 回答 |
|---|---|
| 1 消除哪类「忘记」 | 忘记区分取消与失败（fail-fast 下把一次失败放大为 N 次）；忘记某种结局形状 |
| 2 能否参数化现有机制 | 是：`ImmediateResult` 上的一个方法，复用 `TaskOutcome` 与库已有的「执行失败 / 取消」切分，不新增类型 |
| 3 自动继承结构化语义 | 只读一个已冻结的值，不参与取消、deadline、上下文 |
| 4 隐式状态 / 第二套管道 | 否：同步调用调用方传入的三个函数之一 |
| 5 失败形状 | 完全用 `TaskOutcome` 表达，不新增结局 |
| 6 撑破抽象边界 | 否：逐元素值容器上的方法，不碰 Batch/Group 聚合面。也不是 idea-graveyard 否决的「任务编排 / 链式组合」：它是终端、同步、不产出 future 的消费，不是 `thenApply`/`exceptionally` 式的 future 链 |

## 8. 原提案 §11 七个待拍板项的今日处置

| # | 原问题 | 处置 | 理由 |
|---|---|---|---|
| 1 | 选项 B（3 粗必填 + 细可选）vs 选项 C（7 分支全必填） | 收窄为 `fold` | 粗分支切法保留，细化由 `onCancelled` 的 `TaskOutcome` 参数表达 |
| 2 | 命名 `TaskResult`、终结方法 `resolve()` | 关闭 | 不新增类型，没有终结步 |
| 3 | `TaskResult` 与 `TaskCompletion` 关系 / `asResult()` 桥 | 关闭 | 没有 `TaskResult`；值容器是 `ImmediateResult`，消费入口只放在它上面（第 4 节末条） |
| 4 | 是否需要 `poll` | 关闭 | 公开面没有活体 future |
| 5 | 是否加 `awaitUninterruptibly` | 关闭 | 同上；同步入口本就不可中断地等待并恢复标志 |
| 6 | handler 是否允许抛受检异常（挂 `ThrowingFunction`） | 关闭 | `ThrowingFunction` 已被否决（`decision-log.md`、idea-graveyard）；用标准 `java.util.function` |
| 7 | 是否在 `TaskFuture` 上加便利方法 | 关闭 | `TaskFuture` 包私有 |

## 9. 落地清单（若收窄采纳）

- `ImmediateResult.java`：加第 4 节两个成员；javadoc 写明参数顺序、分支依据是 outcome、`onCancelled` 的
  throwable 不保证是 `CancellationException`。
- 测试：每个非 `RUNNING` 的 `TaskOutcome` 经 `failed(outcome, t)` 命中正确分支；成功 null；遍历
  `TaskOutcome.values()` 的守卫用例；真实 fail-fast `Par.map` 断言 `failure=1`、其余 `cancelled`。
- 文档：`docs/{en,zh}/user-guide.md` 的 Immediate results 一节加 `fold` 示例；`CHANGELOG.md`。纯新增，
  迁移文档不变（`scripts/check-api-surface.py` 只对删除报错）。
- 可选：`BatchProcessingDemo` 的 `catch (Exception)` 改为 `fold`。
- 原提案文件：拍板后把被关闭部分的否决理由（第 1、6.2 节）写进 `design/decision-log.md`，再删除原提案
  （`design/AGENTS.md` 文档维护原则 4）。
- 不新增公开类型，`PublicApiSurfaceTest` 不变。

## 10. 待拍板点

1. **#22 处置。** 推荐收窄：关闭 `TaskResult`/获取工厂/typestate 链/细化，`ImmediateResult` 加
   `successful()` 与 `fold(...)`。
2. **若不要 `fold`。** 只加 `successful()`（与 `TaskCompletion.successful()` 对称），或整体关闭。
3. **消费结构的形状。** 三参 `fold`（推荐）还是两步 typestate 链（第 5 节）。
4. **原提案文件的去向。** 按文档维护原则：先写 decision-log，再删除。

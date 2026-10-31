# 批/组结果读取面对称化提案

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未实现。`TaskBatchResult.valuesOrThrow()` 仍抛受检 `ExecutionException`，`report()` 仍返回 `BatchReport`，批侧没有 `orThrow()`。

> 状态：**设计提案，待拍板**。本文处理最终清单 #6：批侧（`TaskBatchResult`、`BatchCombinedResult`）
> 与组侧（`TaskGroupResult`）读取面的命名与抛出约定分歧，并覆盖 F10 的公开部分。拍板即重开
> `synchronous-scope-exit.md:107-108` 的约定。基线：dev/v0.3.0 HEAD dd12641。

## 0. 结论

**推荐方向 A：所有聚合读取统一为组侧现行约定（非受检）。** 每种结果有唯一的失败闸门
`orThrow()`；选中的已记录失败按 unchecked 原样抛出、checked 包成 `CompletionException`、纯取消抛
`CancellationException`。`BatchReport` 并入 `TaskBatchResult`，`stateCounts()` 改名
`outcomeCounts()`。单任务容器 `ImmediateResult.valueOrThrow()` 保持受检 `ExecutionException`。

主要依据不是命名洁癖，而是一个实测缺陷：`mapAndCombine` 的某个元素以业务异常失败时，
`BatchCombinedResult.terminalValueOrThrow()` 的异常链里**没有那个业务异常**，只有 `FAIL_FAST`
取消；同形的组 `terminalValueOrThrow()` 原样抛出业务异常（§1.3）。同名方法在受检性和根因两方面
都不一致。这条约定的书面依据只是「保留」：批侧的 `ExecutionException` 是同步化之前
`Futures.allAsList(results).get()` 的形状残留（§1.2）。

F10 公开部分：不做结构性删减。真正重复的只有约定分歧，本提案已经解决；其余看似重复的路径，
失败语义或类型来源各不相同（§5）。

## 1. 现状

### 1.1 读取面对照（dd12641）

| 读取 | 类型 | 已记录失败时 | 纯取消时 | 受检 |
|---|---|---|---|---|
| `valueOrThrow()` | `ImmediateResult`（`:63`） | `ExecutionException(cause)` | `ExecutionException(取消异常)` | 是 |
| `valuesOrThrow()` | `TaskBatchResult`（`:67`） | `ExecutionException(输入顺序首个失败)` | `CancellationException` | 是 |
| `terminalValueOrThrow()` | `BatchCombinedResult`（`:83`） | 只读 terminal：`ExecutionException(terminal.failure)` | 同左 | 是 |
| `valuesOrThrow()` / `terminalValueOrThrow()` / `orThrow()` | `TaskGroupResult`（`:209`、`:219`、`:231`） | unchecked 原样，checked 包 `CompletionException` | `CancellationException` | 否 |

| 计数与首个失败 | 批 | 组 |
|---|---|---|
| 计数 | `report().stateCounts()`（`TaskBatchResult:90,124`） | `outcomeCounts()`（`TaskGroupResult:248`） |
| 首个失败 | `report().firstException()`（`:128`） | `failedTaskResult()`（`:102`）、`failedTaskName()` |
| 返回自身的失败闸门 | 无 | `orThrow()` |

「symmetric with `stateCounts()`」这句 javadoc 只写在包私有的 `TaskGroupReport:122-124` 上，公开面
是两个名字。

### 1.2 约定的来历

`synchronous-scope-exit.md:107-108` 原文是「Batch 聚合**保留** ExecutionException 报执行失败、
CancellationException 报纯取消；Group 聚合**保留** unchecked 原样抛、checked 包装
CompletionException」。两个读取口在 `768437b` 同时加入，当时的方向（axiom-drift 报告 A2/B4/B5，
`backup/scratch-materials:reports/axiom-drift-2026-09-11.html`）是「mirror it on TaskBatchResult」与
「Two symmetric results should answer the same question the same way」。不对称来自当时的实现路径：
批 `valuesOrThrow()` 的主体是 `Futures.allAsList(results).get()`（`d01bf4e^:TaskBatchResult.java:159-161`），
受检性来自 `Future.get()` 的签名；组 `orThrow()` 读的是已冻结的报告。同步化（`d01bf4e`）之后批侧
同样读冻结数据，`ExecutionException` 已经没有任何来源，只剩形状。契约正文没有论证两侧为什么
应该不同，所以重开这条约定不推翻任何书面理由。

### 1.3 实测（探针在 `/tmp/vf-proposals/p3/probe/`，用 `javac --release 8` 对 dd12641 自编）

元素 `b` 抛 `PriceUnavailable`（`RuntimeException`），其余条件一致：

```text
batchCombine.terminalValueOrThrow: ExecutionException -> LeanCancellationException(task 'prices' ended with FAIL_FAST) -> CancellationException  业务异常不在链上
batchCombine.batchResult().valuesOrThrow: ExecutionException -> PriceUnavailable
group.terminalValueOrThrow: PriceUnavailable（原样，unchecked）
combine 抛 IOException：批 ExecutionException -> IOException；组 CompletionException -> IOException
```

在修复树 `fix/land-pr56-k3-onto-dev`（`4a66581`）上重跑，输出相同：遮蔽与 #1 无关，原因是
`BatchCombinedResult.terminalValueOrThrow()` 只读 terminal，而元素失败时 terminal 只是被级联取消的那个
任务。用户指南 batch-combine 示例（`docs/en/user-guide.md:123-127`）的写法
`Report report = run.terminalValueOrThrow();` 正好会遇到这个缺陷。

嵌套读取（BACKLOG O-8）：`Par.map` 元素函数内写 `par.map(...).valuesOrThrow()` 编译失败
（`unreported exception ExecutionException`）；同一位置写 `runtime.groupInheriting(...)...runAll().valuesOrThrow()`
可以编译。JDK 自身也做过同样的迁移（`javap`）：JDK 21 `StructuredTaskScope.ShutdownOnFailure.throwIfFailed()`
声明 `throws ExecutionException`；JDK 25 `StructuredTaskScope.join()` 只声明 `InterruptedException`，失败以
`StructuredTaskScope.FailedException extends RuntimeException` 报出。作用域级 join 是非受检的，单任务
`Future.get()` 仍是受检的。

### 1.4 发布边界

`v0.2.0` 已发布的只有 `report()`、`BatchReport`（含 `stateCounts()`、`firstException()`）与
`reportString()`。`valuesOrThrow()`、`BatchCombinedResult`、`GroupValues`、组的 `orThrow()`/`outcomeCounts()`
都是 0.3 快照期新增的，从未发布。因此迁移条目要同时写「From 0.2.x」与「From an earlier 0.3 snapshot」两处。

## 2. 方向选项

无论选哪个方向，§1.3 的根因遮蔽都要修：combine 结果的 terminal 读取必须先检查元素失败。
这一条不依赖方向，但它改变了 `terminalValueOrThrow()` 的文档化行为，所以列入待拍板点（§10 第 2 条）。

### 2.1 方向 A：全部非受检（推荐）

1. 批元素体是 `Function`，记录失败在构造上就是 unchecked（提交失败的 `SubmissionException` 也是
   `RuntimeException`）。受检的
   `ExecutionException` 包装不带任何类型信息，只多一次 `getCause()` 拆包；偷抛的 checked 异常走
   `CompletionException`，与组一致。
2. 嵌套批是元素体内的正常组合。组已经可以这么写（§1.3），`ThrowingFunction` 已被否决且不得重开
   （`decision-log.md:20`）。不改 `map` 签名而让嵌套批读取可写，A 是唯一的办法。
3. 三种结果上同名方法同义：在 `mapAndCombine` 与单 combine 组之间改写时，catch 块不用改。
4. 贴近现行 JDK 习语（§1.3），公理 4。
5. 改动面最小：组侧零改动，组的测试、指南、README 示例原样保留。

代价，如实列出：

- 去掉 `throws ExecutionException` 是源码破坏。只包住这次调用的 `catch (ExecutionException e)` 会变成
  编译错误（JLS：catch 一个 try 块不会抛出的受检异常），编译器能找出来。同一 try 块里还有别的
  `ExecutionException` 来源（如 `ImmediateResult.valueOrThrow()`）时不会报错，而以前被捕获的批失败会
  原样外抛：迁移时要人工复查这类混合 try 块。
- 原样重抛的异常栈只有 worker 帧，没有调用方帧；多次读取抛出同一个实例。这两点组侧已经如此。
- #2（批冻结按完成时刻分类）落地后，元素体自抛的 `CancellationException` 归为 `USER_FAILURE`，
  `orThrow()` 会把它原样抛出，单凭异常类型无法与框架纯取消区分，要读 `outcome()` 才能分清。组侧现在
  就是这样。

### 2.2 方向 B：全部受检（不推荐）

把组的 `valuesOrThrow()`/`terminalValueOrThrow()`/`orThrow()` 改为 `throws ExecutionException`。否决理由：

1. 打破现有的嵌套组合：元素函数内的 `runAll().valuesOrThrow()` 今天能编译，B 之后不能。O-8 的范围翻倍。
2. 抹掉组侧已经文档化、有测试锁定的「可以直接 catch 业务异常类型」（`SynchronousExecutionTest:203,220`
   的 `assertThatThrownBy(group::orThrow).isSameAs(failure)`）。每个调用点都要写 `catch (ExecutionException)` + `getCause()` + `instanceof`
   拆包，正是 §4 改前代码的样板。
3. 受检性消除不了「忘记」：`…OrThrow` 本来就是响亮路径，静默路径是只读 `outcome()`/`results()`，受检性
   对它不起作用。B 只增加编译期仪式。
4. 与 JDK 的演进方向相反：STS 从 JDK 21 的受检 `throwIfFailed()` 改为 JDK 25 的非受检 `FailedException`。
5. 改动面最大：三个组公开签名、组测试的同一性断言、指南与 README 示例全部要改。

### 2.3 方向 C：维持约定，只修根因遮蔽（不推荐）

1. 约定没有书面理由（§1.2）。维持它，等于把一次实现路径的残留固化成契约。
2. 同名不同义依旧：`terminalValueOrThrow()` 在一个类型上受检、在另一个上不受检。
3. O-8 永远不修：批元素体内嵌套读取批结果仍要 try/catch 样板，而唯一的替代方案 `ThrowingFunction`
   已被否决。
4. 命名分歧留在公开面上（`stateCounts`/`outcomeCounts`）。
5. 「批的 `ExecutionException` 与 `ImmediateResult.valueOrThrow()` 一致」是 C 最强的论据，但组的聚合同样
   建在 `ImmediateResult` 之上却是非受检的，所以这个论据对两侧同样成立，不能用来选 C。「破坏性」
   不构成理由：0.x 预稳定期允许无垫片破坏（`AGENTS.md` Key Conventions），而且受影响的读取口
   从未发布（§1.4）。

## 3. 推荐终态（方向 A）

### 3.1 规则（替换 `synchronous-scope-exit.md:107-108`）

> 聚合读取（`TaskBatchResult`、`BatchCombinedResult`、`TaskGroupResult`）共用一条规则。`orThrow()` 是每种
> 结果唯一的失败闸门；`valuesOrThrow()` 与 `terminalValueOrThrow()` 先经过它。选中的失败：批取输入顺序首个
> `USER_FAILURE`/`SUBMISSION_FAILURE` 元素；批+combine 先取元素，再取 combine 自身；组取已记录的失败成员或
> combine。选中失败是 `RuntimeException`/`Error` 时原样抛出，checked 时包成 `CompletionException`（消息含作用域名
> 与任务定位）；没有记录失败而 outcome 非 `SUCCESS` 时抛 `CancellationException`。单任务容器
> `ImmediateResult.valueOrThrow()` 不是聚合读取，保持 Left 一律包成 `ExecutionException`、cause 为存储的原对象。

### 3.2 成员变化

| 类型 | 现状 | 终态 |
|---|---|---|
| `TaskBatchResult` | `report()` → `BatchReport` | 删除；`BatchReport` 嵌套类型删除 |
| | `BatchReport.stateCounts()` | `outcomeCounts()`，与组同名 |
| | `BatchReport.firstException()`（`@Nullable Throwable`） | `failedTaskResult()`：`Optional<ImmediateResult<T>>`，输入顺序首个执行失败元素，与组同名同义 |
| | `valuesOrThrow() throws ExecutionException` | `valuesOrThrow()`，非受检，经过 `orThrow()` |
| | — | 新增 `orThrow()`，返回自身 |
| | `reportString()` | 保留，改为由 `outcomeCounts()`/`failedTaskResult()` 拼出（文本格式不是契约） |
| `BatchCombinedResult` | `terminalValueOrThrow() throws ExecutionException`，只读 terminal | `terminalValueOrThrow()`，非受检，经过 `orThrow()` |
| | — | 新增 `orThrow()`：元素失败优先，再看 combine，返回自身 |
| `TaskGroupResult` | — | 不变，它就是参照 |
| `ImmediateResult` | — | 不变 |

刻意不加的：批侧的 `failedTaskName()`（批元素共享批名，名字定位不到元素，定位靠 `failedTaskResult()` 与
`results()` 下标）；`BatchCombinedResult.outcomeCounts()`（`batchResult().outcomeCounts()` 加
`terminalResult().outcome()` 已经能表达，再加一个会出现第三种计数口径）。

原型：`/tmp/vf-proposals/p3/proto6/` 按上表改了两个类，`javac --release 8 -Xdoclint:reference/private` 通过。
同一探针在原型上的输出：三处 `…OrThrow()` 都原样抛出 `PriceUnavailable`；checked combine 两侧都是
`CompletionException -> IOException`；元素函数内嵌套 `map(...).valuesOrThrow()` 可以编译。

### 3.3 新成员的六问（`orThrow()`；`outcomeCounts()`/`failedTaskResult()` 是改名，不是新能力）

| 问 | 回答 |
|---|---|
| 1 消除哪类「忘记」 | 忘记「combine 结果的 terminal 读取不含元素失败」而丢根因；忘记两个入口的 catch 形状不同 |
| 2 能否参数化现有机制 | 能：它们都是同一冻结快照上的读取器，形状照搬组侧已有成员；净删一个公开类型（`BatchReport`） |
| 3 取消/deadline/上下文 | 不涉及：只读冻结数据，不等待、不消费中断标志（与 `synchronous-scope-exit.md:85` 一致） |
| 4 隐式状态/第二管道 | 无 |
| 5 失败形状 | 沿用 `TaskOutcome` 与已有的失败优先规则（输入顺序首个记录失败），不新增词汇 |
| 6 抽象边界 | 不撑破：Batch 仍是同构映射，没有引入按名定位；Batch 与 Group 对同一问题给出同一答案 |

## 4. 用户代码：现状最好写法 → 改后

下列片段分别用 dd12641 与 §3.2 原型以 `javac --release 8` 编译通过（`/tmp/vf-proposals/p3/probe/snip-*`）。

让业务异常原样外抛。现状要拆包；改后与组成员上的同一份工作写法相同：

```java
List<@Nullable Price> loadPrices(Par io, List<String> skus) {
    try {
        return io.map(skus, this::fetchPrice, PRICES).valuesOrThrow();
    } catch (ExecutionException failure) {
        Throwables.throwIfUnchecked(failure.getCause());
        throw new IllegalStateException(failure.getCause());
    }
}
```

```java
List<@Nullable Price> loadPrices(Par io, List<String> skus) {
    return io.map(skus, this::fetchPrice, PRICES).valuesOrThrow();
}
```

现状下 `IllegalStateException` 那一行在批路径上永远走不到（元素体是 `Function`），只是为了满足编译器。

批 + combine。现状必须先读一次元素，否则元素失败只剩 `FAIL_FAST` 取消；指南示例漏了这一行：

```java
Report priceReport(Par io, Par cpu, List<String> skus) throws ExecutionException {
    BatchCombinedResult<Price, Report> run = io.mapAndCombine(skus, this::fetchPrice, PRICES, cpu, this::summarize);
    run.batchResult().valuesOrThrow();
    return run.terminalValueOrThrow();
}
```

```java
Report priceReport(Par io, Par cpu, List<String> skus) {
    return io.mapAndCombine(skus, this::fetchPrice, PRICES, cpu, this::summarize).terminalValueOrThrow();
}
```

元素函数内的嵌套批：

```java
List<List<@Nullable Price>> pagePrices(Par io, List<List<String>> pages) throws ExecutionException {
    return io.<List<String>, List<@Nullable Price>>map(pages, page -> {
                try {
                    return io.map(page, this::fetchPrice, PAGE_PRICES).valuesOrThrow();
                } catch (ExecutionException failure) {
                    Throwables.throwIfUnchecked(failure.getCause());
                    throw new IllegalStateException(failure.getCause());
                }
            }, PAGES)
            .valuesOrThrow();
}
```

```java
List<List<@Nullable Price>> pagePrices(Par io, List<List<String>> pages) {
    return io.<List<String>, List<@Nullable Price>>map(
                    pages, page -> io.map(page, this::fetchPrice, PAGE_PRICES).valuesOrThrow(), PAGES)
            .valuesOrThrow();
}
```

计数与首个失败：

```java
TaskBatchResult.BatchReport report = batch.report();
metric(report.stateCounts());
Throwable first = report.firstException();
if (first != null) {
    alert(first);
}
```

```java
metric(batch.outcomeCounts());
batch.failedTaskResult().map(ImmediateResult::failure).ifPresent(this::alert);
```

**被消除的失败模式**：

1. `mapAndCombine` 的 terminal 读取在元素失败时只给出 `FAIL_FAST` 取消，业务根因丢失（§1.3 实测）。
2. 同一种失败经批与组两个入口读出时 catch 形状不同；在 `mapAndCombine` 与单 combine 组之间改写时
   catch 块必须同步修改。
3. 元素函数内嵌套批读取必须手写 try/catch 拆包（O-8）。
4. 同一个计数有两个名字和两条路径（`report().stateCounts()` / `outcomeCounts()`）。

## 5. F10 公开部分：逐项判定为保留

`javap` 计数：`GroupValues` 11 个公开方法，`TaskGroupResult` 26 个。F10 认为「同一份数据的等价访问路径
太多」。逐对核对：

| 看似重复的两条路径 | 实际差异 | 判定 |
|---|---|---|
| `typedValues()`（静态 `Tuple2`）↔ `valueAt`/`valueOf`（按位置/名） | 编译期类型 vs 动态寻址；三成员以上静态树难读，指南的处理是在 combine 里展平（`user-guide.md:192-215`） | 保留，`group-one-shot` §2 已论证（`:52-60`） |
| 每个查询的 untyped / `TypeToken` / `Class` 三个重载 | `Class` 只是 `TypeToken.of` 的简写，走同一校验路径（BACKLOG A-P7）；`TypeToken` 表达参数化类型；untyped 服务真正动态的调用方 | 保留 |
| `GroupValues.valueOf` ↔ `TaskGroupResult.resultOf` | 前者只在整组成功后存在、读取不抛异常；后者每个成员都有，是 Either（`valueOrThrow()` 受检）。成功路径上只有前者能免受检读取 | 保留，失败语义不同 |
| `typeAt`/`typeOf` | 动态调用方在读取前检查 schema（`group-one-shot:169`） | 保留 |
| `results()` ↔ `members()`（批：`results()` ↔ `completions()`） | 前者总是齐全；后者是最终观测，有界清理耗尽时可以缺项。合成「一条记录 + 可选计时」是数据模型决定 | 不在本文处理，归 #22（TaskResult 提案）；本文不预设它的结论 |
| 批没有 `orThrow()`，组有 | 真分歧 | §3 解决 |

不属于读取面对称、本文不处理：`TaskCompletion.enqueued()` 的 3ms 阈值；`TaskCompletion.succeeded/failed`
公开工厂（#7）；`ParRuntime` 取 `Par` 的三条路径。

结论：F10 公开部分真正的重复只有约定分歧。其余路径各自承载不同的失败语义或类型来源，合并只会
删掉一种合法的用户写法。这个否决结论应进 decision-log（§10 第 5 条）。

## 6. 迁移条目草稿

`scripts/check-api-surface.py` 会把 `report()`、`BatchReport`、两处签名变化报为删除，要求同一 PR 同时改
`docs/en/migration-v0.3.md` 与 `docs/zh/migration-v0.3.md`。

`docs/en/migration-v0.3.md`「From an earlier 0.3 snapshot」表格追加：

```markdown
| `batch.report().stateCounts()` | `batch.outcomeCounts()` (same name as `TaskGroupResult.outcomeCounts()`) |
| `batch.report().firstException()` | `batch.failedTaskResult().map(ImmediateResult::failure)`; empty when no element recorded a failure |
| `catch (ExecutionException e)` around batch `valuesOrThrow()` / combined `terminalValueOrThrow()` | catch the business exception itself; checked element or combine failures arrive as `CompletionException` |
| `run.batchResult().valuesOrThrow()` before `run.terminalValueOrThrow()` | `run.terminalValueOrThrow()` alone: element failures are checked first |
```

同文件 `:70-73` 原地改写为：

```markdown
Aggregate reads share one rule. `orThrow()` is the failure gate of `TaskBatchResult`,
`BatchCombinedResult`, and `TaskGroupResult`, and `valuesOrThrow()` / `terminalValueOrThrow()` pass
through it. The selected recorded failure (a batch's first in input order; a combined batch's elements
before its combine; a group's recorded member or combine) is rethrown as-is when unchecked and wrapped
in CompletionException when checked; pure cancellation throws CancellationException. Single
ImmediateResult Left reads uniformly throw ExecutionException with the stored cause, including
cancellation. A try block that also catches ExecutionException from another source compiles
unchanged, but a batch failure it used to catch now propagates: review such blocks by hand.
```

「From 0.2.x」表格追加：

```markdown
| `TaskBatchResult.report()` / `BatchReport.stateCounts()` / `BatchReport.firstException()` | `outcomeCounts()` / `failedTaskResult()` on the result itself |
```

`docs/zh/migration-v0.3.md` 对应位置：

```markdown
| `batch.report().stateCounts()` | `batch.outcomeCounts()`（与 `TaskGroupResult.outcomeCounts()` 同名） |
| `batch.report().firstException()` | `batch.failedTaskResult().map(ImmediateResult::failure)`；无元素记录失败时为空 |
| 批 `valuesOrThrow()` / 合并结果 `terminalValueOrThrow()` 外的 `catch (ExecutionException e)` | 直接 catch 业务异常；checked 的元素或 combine 失败以 `CompletionException` 到达 |
| `run.terminalValueOrThrow()` 之前的 `run.batchResult().valuesOrThrow()` | 只写 `run.terminalValueOrThrow()`：先检查元素失败 |
```

```markdown
聚合读取共用一条规则：`orThrow()` 是 `TaskBatchResult`、`BatchCombinedResult`、`TaskGroupResult` 的
失败闸门，`valuesOrThrow()`/`terminalValueOrThrow()` 先经过它。选中的已记录失败（批取输入顺序首个；
批+combine 先元素后 combine；组取已记录的失败成员或 combine）为 unchecked 时原样抛出，checked 时包装为
CompletionException；纯取消抛 CancellationException。ImmediateResult 的所有 Left 统一以 ExecutionException
包装原 cause。同一 try 块另有 ExecutionException 来源时不会编译报错，但原先被捕获的批失败会外抛，需人工复查。
```

`CHANGELOG.md` 0.3.0：

```markdown
- Batch aggregate reads follow the group convention. `TaskBatchResult.valuesOrThrow()` and
  `BatchCombinedResult.terminalValueOrThrow()` no longer declare ExecutionException: the selected recorded
  failure is rethrown as-is when unchecked and wrapped in CompletionException when checked, through a new
  `orThrow()` on both types. `terminalValueOrThrow()` now checks element failures first, so an element's
  business exception is no longer reported as the combine's FAIL_FAST cancellation. `TaskBatchResult.BatchReport`
  and `report()` are removed in favor of `outcomeCounts()` and `failedTaskResult()`, matching `TaskGroupResult`.
```

## 7. 波及面（同一提交，AGENTS.md「实现、测试、用户文档、迁移说明作为一次变更」）

- 主源码：`TaskBatchResult`、`BatchCombinedResult`；`TaskBatch` 冻结时把批名带进结果，供
  `CompletionException` 消息使用；`TaskGroupReport:122-124` 的 `{@link TaskBatchResult.BatchReport#stateCounts()}`
  会悬空：#10 未落地时同提交改指 `TaskBatchResult#outcomeCounts()`，#10 已落地时它已随包私有
  `TaskGroupReport.outcomeCounts()` 删除。
- 测试：对原型编译全部 77 个测试文件，编译器只报 3 个文件 9 处（`ParMapAndCombineTest` 4、
  `SynchronousExecutionTest` 3、`VirtualDeadlineTest` 2，全部是 `report()`/`stateCounts()`/`firstException()`）。
  编译器看不到的是运行期断言里的 `ExecutionException` 包装形状，要逐个复查；同时出现 `ExecutionException`
  与 `map`/`mapAndCombine` 的公开面测试有 `ParMapAndCombineTest`、`SynchronousExecutionTest`、`VirtualDeadlineTest`、
  `CallableReferenceReleaseTest`。`PublicApiSurfaceTest:54` 钉住的 `TaskBatchResult$BatchReport` 一行删除。
  新增回归测试：§1.3 的根因遮蔽（反向验证：恢复「只读 terminal」后测试失败）与嵌套批读取的编译形状。
- 契约：`synchronous-scope-exit.md:107-108` 按 §3.1 原地改写；`batch-terminal-combine.md:26` 补一句 terminal
  读取先看元素失败。
- 用户文档：`docs/en/user-guide.md:85-87`（批）、`:127`（combine 示例此后正确）、`:183-185`（组，改为指向同一
  规则）及 zh 对应段落；`docs/{en,zh}/reference/nullability-annotations.md` 以 `BatchReport.firstException()`
  为 `@Nullable` 示例（en `:32,45`），要换成别的公开成员；`docs/zh/testing/task-batch-result-report-test-design.md`；
  `docs/zh/design/idea-graveyard.md` 三处把 `BatchReport` 当概念提及（`:83,190,213`，改为「批次结果的计数」）；
  demo 的 `G2_BatchResultReportTest`、`QuickStartTest`、`H1_NullEmptyInputTest`、`G2-batch-result-report.md`、
  `BATCH-best-practices.md`、`QUICK-START-5-minutes.md`。`migration-v0.2.md` 是 dated record，不改。
- BACKLOG：O-7 关闭；O-8 的「是否补 unchecked 读取口」随之关闭，只剩「嵌套读取写法」的文档项。

## 8. 与其他条目的耦合

- **#10**：#10 删除的是包私有 `TaskBatch` 上同名的 `valuesOrThrow()`/`report()`/`BatchReport`，与本条改的公开成员
  不是同一个类，主源码互不阻塞。但两者都改测试里 `valuesOrThrow()` 周围的 catch 形状：本条与 #10 同批、且
  先于 #10 的测试迁移落地，避免 #10 先按受检形状迁移一遍再改一遍。
- **#1/#2**：正交。#2 让元素体自抛 CE 归为 `USER_FAILURE`，改变的是 `failedTaskResult()`/`orThrow()` 的输入，
  不改变本文的选取规则；§2.1 第三条代价就来自这里。
- **#12**：`TaskGroupResult` 的新 javadoc 指向 §3.1 的唯一规则，不要复述（`design/AGENTS.md` 单一事实源）。
- **#22**：`results()`/`members()` 是否合并归 #22（§5），本文不预设。

## 9. 已排除的替代方案（供 decision-log）

| 方案 | 否决理由 |
|---|---|
| `ImmediateResult.valueOrThrow()` 也改非受检 | 它是单任务 Either，形状对应 `Future.get()` 与 `asFuture().get()`；聚合读取对应作用域 join。JDK 同样是单任务受检、作用域非受检 |
| 为避免 `valueOrThrow`/`valuesOrThrow` 一字之差而改名 | 单任务/聚合的分界本身就清楚；改名会波及已稳定的 `ImmediateResult` |
| 保留 `BatchReport`，只把 `stateCounts()` 改名 | 只装两个可派生字段的值类型，与组的扁平形状不对称；删除后净减一个公开类型 |
| 统一包一层新的非受检异常（仿 JDK 25 `FailedException`） | 推翻组侧已文档化、有测试锁定的原样重抛，调用方失去直接 catch 业务类型的能力 |
| 批侧加 `failedTaskName()` | 批元素共享批名，名字定位不到元素 |
| `BatchCombinedResult.outcomeCounts()` | 会出现第三种计数口径，现有两个读取已经能表达 |
| 重开 `ThrowingFunction` 以解决 O-8 | 已被用户永久否决（`decision-log.md:20`）；方向 A 不改 `map` 签名就解决了 O-8 |
| F10：删 `GroupValues` 的动态查询或 `TaskGroupResult.resultAt/resultOf` 之一 | 两者失败语义不同（§5），删任何一个都会删掉一种合法写法 |

## 10. 待拍板点

1. **方向**：A（全部非受检，推荐）/ B（全部受检）/ C（维持约定，只修根因遮蔽）。选 A 或 B 即重开
   `synchronous-scope-exit.md:107-108`。
2. **根因遮蔽修复**（与方向无关，建议无论如何都做）：`BatchCombinedResult.terminalValueOrThrow()` 先检查元素
   失败。它改变一个文档化方法的行为，所以单列。
3. **`BatchReport`/`report()` 删除**，由 `TaskBatchResult.outcomeCounts()`/`failedTaskResult()` 取代（触及 v0.2.0
   已发布面，迁移条目见 §6）。
4. **`orThrow()` 加到 `TaskBatchResult` 与 `BatchCombinedResult`**（兑现 `768437b` 时「mirror it on
   TaskBatchResult」的方向）。
5. **F10 公开读取面不收敛**，把 §5 的逐项理由写进 decision-log。
6. **落地顺序**：与 #10 同批，先于 #10 的测试迁移（§8）。


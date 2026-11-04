# 以 execution / execution exit 统一任务执行术语

## 蒸馏摘要

- 核心出发点：`body` 不是用户熟悉的一次任务执行术语，`bodyCompletionConfirmed()` 还容易把
  “任务代码已退出执行线程”误读为“`Task` handle 已完成”。统一采用 **execution** 表示一次受库监督的
  任务执行，采用 **execution exit** 表示该执行已经退出或已被原子确定为永远不会进入任务代码。
- 术语不变量：**task code** 是用户代码及取消/中断响应职责的所有者；**execution** 是任务代码的一次运行；
  **Task** 是调用方可见的 future view。三者 MUST NOT 互相替代。execution exit MUST NOT 暗示成功、失败、
  取消、future settled、资源已经释放或嵌套执行已经退出。
- 拟议 MUST / MUST NOT：
  - 公开等待与结果 API MUST 使用 `awaitExecutionExit`、`executionExitConfirmed`、
    `unexitedExecutions`、`unexitedExecutionSignals`；负载统计 MUST 使用 `executions`、
    `unattributedExecutions`、`unmeasuredExecutions`。
  - `CombineBody` MUST 更名为 `Combiner`；它描述汇合函数，不是 execution 的同义词，`apply` 签名不变。
  - 当前维护的内核标识符、测试、Javadoc、用户文档、设计契约和有效工具配置 MUST 同步更新；
    MUST NOT 只改公开方法而留下同一概念的第二套现行词汇。
  - 本变更 MUST NOT 改变等待、取消、中断、落定、观测、清理、归因或统计语义，也不保留兼容别名。
  - 冻结档案、历史评审与已发布迁移记录 MUST NOT 为改名重写；HTTP body、issue body、语法 body
    等其他含义 MUST NOT 被误替换。
- 被否决方向及理由：`task exit` 较短但没有统一“一次运行”的名词；`completion`/`finish` 易与 future
  完成混淆；`termination` 容易暗示异常终止或资源销毁；`return` 不能覆盖异常与取消；`not running`
  描述瞬时调度状态而不是不可逆的退出确认；保留 `body` 作为内核专用词会继续维持两套现行术语。

## 1. 背景与目标

当前代码和文档用 `body` 同时指用户交给 `Par` / `TaskGroup` 的代码及其一次执行。这个用法不符合
JDK、Guava 等 API 的常见词汇；`body` 通常只是方法、循环或 lambda 的语法正文。更重要的是，
`bodyCompletionConfirmed()` 中的 `completion` 没有清楚地区分两个独立事实：

1. 调用方可见的 `Task` 已 settled 或 cancelled；
2. 执行任务代码的线程已经离开该代码，包括其 `finally`。

取消后第一件事可以先成立，而第二件事尚未成立。名称必须帮助用户保留这个边界，而不是让它看起来更模糊。

本提案采用讨论中选定的第二套方案：公开 API 和现行内部术语都围绕 **execution / execution exit**
统一，全面更新引用，但不改变任何并发协议。

## 2. 术语模型

| 术语 | 含义 | 明确不表示 |
|---|---|---|
| **task code** | 用户交给库执行的代码；其作者负责代码如何响应取消与中断 | 一次运行、运行状态或结果 |
| **execution** | 一个受库监督的任务槽位中的一次执行；普通元素、成员和 combine 都使用这一名词 | `Task` handle、结果落定或成功 |
| **execution exit** | 执行已经离开任务代码，或已原子确定永远不会进入任务代码 | future settled、线程销毁、资源释放、嵌套 execution 退出 |
| **Task** | 调用方可见的 future view，可完成或取消 | 执行线程已经离开任务代码 |

`executionExitConfirmed()` 仍只确认直接 batch 元素或 group 成员及既有 combine/adopt/closer 槽位，
不递归确认子调用。`unexitedExecutions()` 仍可包含尚未开始但尚未被判定为跳过的执行。`SKIPPED`
继续满足“不会进入任务代码”的退出确认条件，这些既有语义都不因名称改变。

中文文档优先使用“任务代码”“一次执行”“执行退出”，不再新增“任务体”作为同一概念的别名。

## 3. 改前与改后的最佳用户代码

改前：

```java
TaskBatchResult<Price> result = par.map(skus, this::fetchPrice, options);
if (!result.bodyCompletionConfirmed()) {
    Map<String, Integer> stillRunning = result.unfinishedBodies();
    log.warn("Executions have not exited: {}", stillRunning);
}

WorkloadFitReport.ParMeasurement measurement = runtime.workloadFitReport().pars().get(0);
long timed = measurement.bodies();
long unmeasured = measurement.unmeasuredBodies();

CombineBody<Tuple2<User, Account>, Profile> combine =
        values -> buildProfile(values.first(), values.second());
```

改后，同一段代码只改变名称：

```java
TaskBatchResult<Price> result = par.map(skus, this::fetchPrice, options);
if (!result.executionExitConfirmed()) {
    Map<String, Integer> stillRunning = result.unexitedExecutions();
    log.warn("Executions have not exited: {}", stillRunning);
}

WorkloadFitReport.ParMeasurement measurement = runtime.workloadFitReport().pars().get(0);
long timed = measurement.executions();
long unmeasured = measurement.unmeasuredExecutions();

Combiner<Tuple2<User, Account>, Profile> combine =
        values -> buildProfile(values.first(), values.second());
```

被消除的失败模式：

- 用户需要学习一个不常见的领域词才能理解基本执行语义；
- `bodyCompletionConfirmed()` 被误认为结果或 future 已完成，从而过早释放仍被任务代码使用的资源；
- 退出状态和负载统计分别使用 `body`/`bodies`，与内核已有的 execution 词汇不一致；
- `CombineBody` 看起来像 execution 的另一种形状，而实际上它是汇合函数 SPI。

## 4. 公开 API 映射

| 类型 | 当前名称 | 新名称 |
|---|---|---|
| `TaskBatch`、`TaskGroup` | `awaitBodyCompletion(Duration)` | `awaitExecutionExit(Duration)` |
| `TaskBatchResult`、`BatchCombinedResult`、`TaskGroupResult` | `bodyCompletionConfirmed()` | `executionExitConfirmed()` |
| 同上 | `unfinishedBodies()` | `unexitedExecutions()` |
| `ParRuntimeSnapshot` | `unexitedBodySignals()` | `unexitedExecutionSignals()` |
| `WorkloadFitReport.ParMeasurement`、`WorkloadFitReport.UnitMeasurement` | `bodies()` | `executions()` |
| `WorkloadFitReport.ParMeasurement` | `unattributedBodies()` | `unattributedExecutions()` |
| 同上 | `unmeasuredBodies()` | `unmeasuredExecutions()` |
| 顶层 SPI | `CombineBody<V, R>` | `Combiner<V, R>` |

除 `Combiner` 的类型名外，参数、返回类型、泛型、异常声明和访问器风格均保持不变；`Combiner.apply`
不改名。所有旧公开名称直接删除，不保留 deprecated 转发方法或同名别名。

## 5. 全面修改范围与引用规则

### 5.1 实现与内核

- 更新上述公开及包私有方法的声明、调用、Javadoc、`{@link}`、异常消息、日志和诊断文本。
- `BodyCompletionTracker` 更名为 `ExecutionExitTracker`，`TaskBodyState` 更名为
  `TaskExecutionState`；对应字段、构造器、工厂、状态说明和测试同步更名。
- `bodyExit`、`awaitBodyCompletion`、`cancelAndAwaitBodyExit`、`stuckBodySummary` 等内部名称分别采用
  `executionExit`、`awaitExecutionExit`、`cancelAndAwaitExecutionExit`、
  `unexitedExecutionSummary` 等同族名称。
- 更新 `WorkloadFit` 的计数字段、构造参数和 `toString()` 标签，使 `executions` 在访问器、内部统计和
  诊断输出中一致。`ExecutionPhaseHintFuture`、`TaskExecutionContext` 等已经准确命名且不包含 body
  的机制不借势重构。
- 所有涉及任务执行的 `body`/`bodies` 注释按第 2 节选择 `task code`、`execution` 或
  `execution exit`，不做逐词机械替换；保留原有排序、竞态、生命周期证明及注释分层。

### 5.2 测试、验证与工具引用

- 更新主库测试、公开 API 面清单及受影响的测试类名，包括 body-completion 跟踪、批次/组结果、
  快照和负载匹配测试。
- 更新 `verification/race-claims/claims.json` 中受影响的协议名、源码片段和测试名；这些是可执行
  校验输入，不能只改显示文案。
- 更新 `scripts/run-pit-core.sh`、现行 PIT 指引和 SpotBugs 排除项中的类型引用； dated PIT audit
  的原始 XML、清单与处置记录保持原样。
- 更新 Maven Central consumer、demo 和 integrations 的代码与测试引用。queue 包只处理确实指向
  本核心概念的引用，不因全库搜索改动力队列实现或 JDK-port 注释。

### 5.3 用户文档与现行设计资料

- 更新根 README、根 AGENTS、`docs/en`、`docs/zh`、现行 HTML 视觉页面、demo 文档和
  integrations 文档中的任务执行含义及代码链接。
- 更新现行设计契约、在途提案、设计路由和当前维护的说明页面；若在途提案已经落地并归档，则只改承接
  它的活契约，不回写档案。
- 在 `docs/{en,zh}/migration-v0.4.md` 增加完整的旧名→新名映射和 `CombineBody`→`Combiner`
  迁移说明，在 CHANGELOG 的 Unreleased breaking changes 中记录此次改名。
- `migration-v0.2`、已发布的 `migration-v0.3`、CHANGELOG 历史发布段、ADR、`design/archive`、
  `design/history`、`design/analysis/archive` 与 dated PIT audit 是历史记录，保留当时名称。
  非归档 analysis 草稿仍是临时输入，不为了词汇统一单独维护；其结论若继续承接，应先进入现行文档。

### 5.4 搜索边界

完成标准不是全仓库字符串零命中，而是：当前维护的执行语义中不再把一次运行称为 body。保留项必须
只属于冻结历史、迁移对照、第三方原文，或 HTTP/issue/语法等其他含义。实现完成后分别搜索公开旧名、
内部类型名和剩余自然语言用法，逐项确认没有遗漏的现行引用。

## 6. 迁移路径

这是 0.4 的破坏性改名，不提供兼容 shim：

1. 按第 4 节替换方法、类型和访问器调用，并重新编译全部下游模块；反射或按名称处理的集成也要更新。
2. 将检查 `bodyCompletionConfirmed()` 的资源释放逻辑改为 `executionExitConfirmed()`，不得改读
   `Task.isDone()`、结果 `outcome()` 或其他 settled 状态代替退出确认。
3. 将 `unfinishedBodies()` 的调用改为 `unexitedExecutions()`；返回的键、计数口径和浅不可变快照不变。
4. 负载报告改用 `executions()` / `unattributedExecutions()` / `unmeasuredExecutions()`；若已有诊断
   文本消费者依赖旧 `toString()` 字段名，也同步迁移，但该格式仍不是稳定解析契约。
5. 显式声明 `CombineBody` 的代码改用 `Combiner`；已有 lambda 和方法引用通常无需改变函数体。

## 7. 第一性原理评估

1. **减少忘记与误读：** 名称直接区分 settled 与 exited，降低提前释放共享资源的风险。
2. **复用而非新增机制：** 只替换现有词汇，不增加状态、入口、管道或兼容层。
3. **结构语义继承：** 取消、deadline、上下文、清理和退出信号保持原协议。
4. **一致性：** 公开 API、内核、测试和文档使用同一术语，不引入隐式状态。
5. **失败形状：** `TaskOutcome`、异常与归因均不变。
6. **抽象边界：** Batch、Group、queue 和 SPI 的职责边界不变；`Combiner` 更准确地表达既有 SPI。

## 8. 实施与验证计划

[P0034](../archive/P0034-checkpoint-cancellation-acknowledgement.md) 正在修改相邻的取消契约与公开 API；
实现从它已经落地的最新开发线开始，避免把其提案措辞误当作当前实现。实施时：

1. 先写清并保持执行退出、结果落定、观测发布和嵌套执行边界，再完成公开及内部改名和所有引用更新。
2. 运行受影响的行为测试、`mvn spotless:apply` 和完整 `mvn test`；公开 API 面测试必须准确列出
   新名称且不再列出旧名称。
3. 运行 `python3 scripts/check-race-claims.py`，确认改名后的真实源码片段仍参与竞态验证。
4. 按 PIT 契约对最终受影响的可执行类做无历史输入的变异验证；范围覆盖所有改名可执行类及相关契约
   测试，跨批次受影响时使用完整 core 范围，并逐项处置 `SURVIVED` / `NO_COVERAGE`。
5. 安装最终库后验证 demo、Maven Central consumer 和 integrations；分别报告主库行为测试、外部
   消费者、Java 8 runtime、独立评审和 PIT 状态，不用编译成功代替这些检查。
6. 运行文档路由、doc refs、本地链接、英文文档及 demo 文章检查；审查最终 diff 中是否有误改历史
   记录或遗漏的现行引用。
7. 初始实现提交后进行独立对抗性评审，重点检查公开契约与实现是否只改名、所有旧公开名称是否消失、
   Java 8 与泛型签名是否保持、测试和工具引用是否有效。此次没有新增行为回归测试，不伪造反向验证。
8. 落地时把蒸馏结论并入同步出口、中断、负载匹配及其他实际承接契约，写入历史回指，归档并删除
   本提案及其在途路由；实现、迁移说明和契约更新作为同一变更交付。

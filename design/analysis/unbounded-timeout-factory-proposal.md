# 无 deadline 工厂（`unbounded()`）与清理预算不连续

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：`unbounded()` 工厂已由维护者否决（2026-10-27），结论与理由在 decision-log 与 idea-graveyard；§5 的清理预算 D1 修法未实现，由 BACKLOG O-3 承接：`MultiTaskContext.resolveDeadlineNanos` 的显式分支仍未钳制在哨兵之下。

> 状态：**设计提案，待拍板**。本文覆盖最终清单 #8：是否补 `unbounded()` 工厂，以及
> 「清理预算不连续」是否单独作为缺陷处理。基线：dev/v0.3.0 HEAD dd12641。

## 0. 结论

- **推荐关闭**：不补 `BatchOptions.unbounded(name)` / `TaskOptions.unbounded()` /
  `ParRuntime.groupUnbounded(name)`。六问第 1、2 问答「否」，且无 deadline 的顶层同步调用失去
  提交路径唯一的活性来源。完整否决理由见 §4，供 decision-log。
- **清理预算不连续是独立缺陷**（P3），与 #8 的拍板结果无关，单独立项。推荐修法 D1：显式
  timeout 的解析结果钳制在哨兵之下，公开入口永远到不了「无 deadline」。本地原型已实测（§5）。

### 本文的自证边界

全部 `path:line` 在 dd12641 上亲自核对。§1.3/§5 的数字来自本轮探针：在
`/tmp/vf-proposals/p1/` 用 `javac --release 8` 自编 dd12641 的 `src/main/java`，探针只用公开 API
（`probe.p1.CleanupBudgetProbe`、`GroupSentinelProbe`）；D1 原型是在同一份拷贝上改一行后另编
一份库再跑同一探针。**未做**：D1 的 `mvn test`、PIT、独立评审。

## 1. 现状

### 1.1 「二选一、无第三态」是写明的不变量

- `TaskOptions.java:19-22`：「No third state exists: omitting the choice does not compile」。
- `task-group-api-and-options.md:129-133`：「timeout 的显式选择提升为入口/类型不变量……不存在
  隐式无界默认值」。
- `first-principles.md:55`：「必须显式的选择逼用户思考（timeout 二选一，无默认超时）」。
- `README.md:85-87`：「there is no third state, so a batch cannot run unbounded by omission」。

三个入口都只收正数：`BatchOptions.java:46,56`、`TaskOptions.java:38,48`、
`ParRuntime.java:295,301`（`Validation.requirePositive`）。所以缺 `unbounded()` 是有意设计。

### 1.2 但「无 deadline」哨兵经饱和算术可达

显式 timeout 走 `MultiTaskContext.resolveDeadlineNanos`（`:224-235`）：
`Deadlines.saturatedNanos`（`Deadlines.java:34-40`，约 292 年以上饱和为 `Long.MAX_VALUE`）→
`Deadlines.after(now, timeoutNanos)`（`:75-80`，和溢出时饱和为 `Long.MAX_VALUE`）。结果与
「无 deadline」哨兵同值。组 deadline 走同一方法（`TaskGroup.java:571-574`）。

哨兵的消费点都是有意为之：

- `CancellationToken.java:248`：哨兵不布防定时器。
- `BodyCompletionTracker.java:245-253`：哨兵的清理预算返回 **0**（只取消不等待），javadoc
  `:240-243` 明写这一点且区分「饱和的 `Long.MAX_VALUE` 预算」。
- `Checkpoints.java:99-100`：无 deadline 时 `remaining()` 恰为 `Duration.ofNanos(Long.MAX_VALUE)`。
- `Task.java:235`：诊断渲染为 `unbounded`。

### 1.3 实测：公开 API 能拿到哨兵，清理预算在饱和点跳到 0

body 内读 `Checkpoints.remaining()`（本机 `System.nanoTime()` ≈ 20 天）：

| `BatchOptions.timeout(...)` | `remaining()` |
|---|---|
| `Duration.ofDays(365 * 200)` | 199 年（有限） |
| `Duration.ofDays(365 * 291)` | 290 年（有限） |
| `Duration.ofNanos(Long.MAX_VALUE)` | **== 哨兵** |
| `Duration.ofSeconds(Long.MAX_VALUE)` | **== 哨兵** |
| `ChronoUnit.FOREVER.getDuration()` | **== 哨兵** |

`runtime.group("g", Duration.ofSeconds(Long.MAX_VALUE))` 的成员同样读到哨兵。分界是
`timeoutNanos > Long.MAX_VALUE - now`，随 `nanoTime()` 原点漂移；`Duration.ofNanos(Long.MAX_VALUE)`
在原点为正的 JVM 上落在哨兵，在原点为负的 JVM 上（规范允许，`Deadlines.java:10-11`）是有限值。

清理预算：两元素 `Par.map`，元素 1 抛错触发 fail-fast，元素 2 不理会中断
（`Uninterruptibles.sleepUninterruptibly` 1.5s）：

| timeout / closeGrace | `map` 返回耗时 | `bodyCompletionConfirmed()` | `unfinishedBodies()` |
|---|---|---|---|
| 10 秒 | 1511 ms | true | `{}` |
| 200 年 | 1514 ms | true | `{}` |
| 291 年 | 1519 ms | true | `{}` |
| `Duration.ofSeconds(Long.MAX_VALUE)` | **23 ms** | **false** | `{b=1}` + WARN |
| `ChronoUnit.FOREVER.getDuration()` | **1 ms** | **false** | `{b=1}` + WARN |
| `Long.MAX_VALUE` 秒 + `closeGrace(5s)` | 1508 ms | true | `{}` |
| 200 年 + `closeGrace(Duration.ZERO)` | 7 ms | false | `{b=1}` + WARN |

清理预算随 timeout 单调增大，越过饱和点后跳成 0。公开契约只说「未设置 closeGrace 时用剩余执行
deadline」（`BatchOptions.java:107-108`、`docs/en/user-guide.md:254-255`、
`docs/en/migration-v0.3.md:97-98`），没有说饱和这一档；「哨兵 → 0」只写在包私有
`BodyCompletionTracker` 的 javadoc 里。

## 2. 用户代码：现状与改后

今天想表达「不设上限」只能写天文数字，几种看起来等价的写法清理行为相反：

```java
TaskBatchResult<Report> a = par.map(shards, this::rebuild, BatchOptions.timeout("rebuild", Duration.ofSeconds(Long.MAX_VALUE)));
TaskBatchResult<Report> b = par.map(shards, this::rebuild, BatchOptions.timeout("rebuild", Duration.ofDays(36500)));
```

`a` 落在哨兵：不布防定时器，fail-fast 后不等被取消的 body 退出就返回；`b` 是有限 deadline：
fail-fast 后等 body 退出。补 `unbounded()` 后（不推荐）写法统一为：

```java
TaskBatchResult<Report> c = par.map(shards, this::rebuild, BatchOptions.unbounded("rebuild"));
```

但工厂只统一了写法；`a` 与 `b` 的不一致仍要靠 D1 修，与是否有工厂无关。D1 之后 `a`、`b` 都解析为
有限 deadline，fail-fast 后都等 body 退出；要「不等」的调用方显式写 `.closeGrace(Duration.ZERO)`。
推荐的写法始终是一个宽松但有限的运维上界：

```java
TaskBatchResult<Report> d = par.map(shards, this::rebuild, BatchOptions.timeout("nightly-rebuild", Duration.ofHours(6)));
```

被消除的失败模式（D1）：同一意图的两种写法得到相反的清理行为；更大的 timeout 换来更小的清理预算。

## 3. 六问（`first-principles.md` §四）

| 判据 | `unbounded()` 的答案 |
|---|---|
| 1 消除哪类「忘记」？ | **无**。漏写 timeout 本来就编译不过；`unbounded()` 不让任何遗漏变得不可能或显眼，只给「不设上限」一个被认可的名字，反而削弱二选一刻意保留的摩擦 |
| 2 能否参数化现有机制？ | **能，所以不需要新概念**：`timeout(Duration)` 已能表达任意长的显式预算。新增第三态还要把 `timeout()` 访问器的两态 `Optional<Duration>`（`BatchOptions.java:139-142`、`TaskOptions.java:67-70`，empty = 继承）拆成三态表示 |
| 3 自动继承结构化语义？ | 部分。嵌套层可继承「无 deadline」，但 `first-principles.md:30`「deadline 是端到端预算」在顶层失去来源；清理预算只能是 0 或显式 grace |
| 4 隐式状态或第二套管道？ | 否 |
| 5 失败形状能否用现有词汇？ | 能（只是永远不会有 `TIMEOUT`） |
| 6 撑破抽象边界？ | 否；但要在三个入口各加一个工厂 |

## 4. 否决理由（供 decision-log）

1. **违反写明的不变量**：「二选一、无第三态」写在 `TaskOptions` javadoc、
   `task-group-api-and-options.md`、`first-principles.md` §三与 README 四处；`unbounded()` 正是被
   排除的第三态。
2. **六问第 1、2 问不通过**（§3）：不消除任何「忘记」；`timeout(Duration)` 已覆盖表达力。
3. **安全**：deadline 是提交路径唯一的活性来源（根 `AGENTS.md` Architecture 节的
   bind-before-submit 不变量）；同步 `map`/`runAll` 不可中断地等待、调用方中断不取消执行（根
   `AGENTS.md` 中断规则的明示例外）；公开面没有运行句柄。无 deadline 的顶层 scope 里，一个不配合
   中断的 body 让调用线程永远挂住且无出口。`first-principles.md:13`：「宁可不提供某个能力，也不
   提供容易被误用的能力」。
4. **真实需求可用有限上界表达**：夜间批处理、迁移这类「长」任务写一个宽松的运维上界（小时级），
   它同时是生产防护；「永远」几乎从来不是真实需求。
5. **附带收益不成立**：BACKLOG O-3 设想的「哨兵时免调度定时器」已存在（`CancellationToken.java:248`），
   不构成采纳理由。

重开条件：出现「任何有限上界都有害（而非不便）」的具体用例，且同时给出无 deadline scope 的活性方案。

## 5. 清理预算不连续：单独立项的缺陷

**定性**：契约与实现不一致（公开文档说未设置时用剩余执行 deadline，饱和到哨兵时实际是 0）；非单调
（更大的 timeout 得到更小的清理预算）；分界随 `nanoTime()` 原点漂移，写不进文档；且让
「无第三态」的承诺被饱和算术悄悄打破。**级别 P3**：只影响写天文数字 timeout 的用户，不丢结果、
不抛未声明异常，后果是 `bodyCompletionConfirmed()=false` + WARN 后提前返回。与 #8 正交：即便
采纳 `unbounded()`，哨兵也应只能经该工厂到达，D1 同样需要。

### 5.1 推荐修法 D1：显式 timeout 的解析结果钳制在哨兵之下

`MultiTaskContext.resolveDeadlineNanos` 显式分支（`:233`）改为
`Math.min(Deadlines.after(nowNanos, timeoutNanos), Long.MAX_VALUE - 1)`。继承分支原样返回 ceiling，
内部「无 ceiling」仍是 `Long.MAX_VALUE`（`MultiTaskContext.java:186`、`TaskGroup.java:573`）。公开
入口由此到不了哨兵：定时器照常布防（约 292 年的延迟，`ScheduledThreadPoolExecutor` 自带溢出保护，
`ParRuntime.java:111` 的 `setRemoveOnCancelPolicy(true)` 保证落定即移除），清理预算随 timeout 单调。

原型实测（同一探针，只换库）：

| timeout / closeGrace | `map` 返回耗时 | `bodyCompletionConfirmed()` |
|---|---|---|
| `Duration.ofSeconds(Long.MAX_VALUE)` | 1511 ms | true |
| `ChronoUnit.FOREVER.getDuration()` | 1509 ms | true |
| 200 年 + `closeGrace(Duration.ZERO)` | 15 ms | false |

`Checkpoints.remaining()` 在 §1.3 的三种落在哨兵的写法下都不再等于哨兵（约 292 年的有限值）；组入口
同样。`closeGrace(Duration.ZERO)` 仍是显式的「不等」出口。

需要维护者接受的代价与连带：

- 写「永远」的用户在 fail-fast/取消后会等不配合中断的 body 退出，与写 200 年的用户一致。字面上
  仍满足 `synchronous-scope-exit.md:130`「无无限 join」（预算有限），但对这类用户等同无限；写进
  CHANGELOG。
- `Checkpoints.java:99-100` 的「无 deadline 时恰为哨兵」对公开 scope 不可达，删去该句。
- 包私有构造的无 deadline token 不受影响（`MultiTaskContextTest`、`CheckpointsRemainingTest` 用
  `inheritTimeout` + ceiling `Long.MAX_VALUE` 构造哨兵，走继承分支）；`CancellationToken.java:248`、
  `BodyCompletionTracker.java:249` 的哨兵分支保留为内部防御。
- 回归测试：显式 timeout 的解析结果 < 哨兵（`Duration.ofSeconds(Long.MAX_VALUE)`、
  `ChronoUnit.FOREVER`、组入口）；fail-fast + 不配合中断的 body 下，天文数字 timeout 与有限 timeout
  的清理行为一致。

### 5.2 否决的其他修法

- **D2 只写文档**（「deadline 饱和即无 deadline，不派生清理等待」）：分界随时钟原点漂移，写不出
  确定数字；「无第三态」仍被悄悄打破。仅作 D1 被否时的退路。
- **D3 删掉 `closeGraceBudgetNanos` 的哨兵分支**（哨兵 → `Long.MAX_VALUE` 预算）：对公开用户的清理
  效果与 D1 相同，但改的是哨兵的含义而不是它的可达性——公开 scope 仍读到哨兵、仍不布防定时器、
  「无第三态」仍被打破；且「无 deadline」从此字面上就是无限 join，直接违反
  `synchronous-scope-exit.md:130`。
- **D4 给派生预算设固定上限**：引入任意常数，为一个边角情形改变全部正常 timeout 的清理语义。
- **D5 工厂对 `toNanos()` 溢出的 `Duration` 抛 IAE**：`now + timeout` 仍可溢出（分界依赖 `nanoTime()`
  原点），D1 的钳制照样需要；拒绝阈值随时钟原点变化，写不成参数校验契约。D5 + D1 只是多一个对
  天文数字的拒绝，收益不足以新增一条参数契约。

## 6. 迁移路径

- **关闭 #8**：无公开面变化，无迁移。decision-log 记 §4；可在 idea-graveyard 记「无 deadline 工厂」
  及替代写法（宽松的有限上界）。
- **D1**：无签名变化，`scripts/check-api-surface.py` 不触发；但它是行为变更：PR 描述列出改动的
  MUST（根 `AGENTS.md`），`CHANGELOG.md` Unreleased「Fixes」加一条，`docs/en/migration-v0.3.md`
  「Waiting and resource ownership」节末尾补一句（`docs/zh` 同步）：

```markdown
An explicit timeout too large to represent resolves to the farthest representable deadline rather than
to "no deadline": its timer is armed, and when `closeGrace` is unset the cleanup wait after convergence
uses that remaining deadline like any other timeout. Set `closeGrace(Duration.ZERO)` to return without
waiting.
```

## 7. 待拍板点

1. #8：**关闭**（推荐）/ 采纳三入口 `unbounded()`。关闭则 §4 进 decision-log。
2. 清理预算不连续是否单独立项为 P3 缺陷（推荐：是，且与第 1 点无关）。
3. 修法：**D1**（推荐；接受「永远」写法在 fail-fast 后等 body 退出）/ D2（只写文档）。
4. D1 触及有文档契约的清理预算路径：按根 `AGENTS.md` 配回归测试、反向验证、`MultiTaskContext`
   的 PIT 与独立评审。

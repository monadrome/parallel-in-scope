# 公开 TimeSource SPI：是否把内部截止时钟缝公开

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：已收割。2026-10-27 否决公开 SPI，结论与理由见 decision-log 的 public-time-source-spi-2026-10-27 行；§7 的测试指南已落地为 `docs/{en,zh}/testing/testing-your-code.md`（示例对当前库实测：TIMEOUT 40/40，不设 `closeGrace` 时 `bodyCompletionConfirmed()` 38/40，设 1s 后 40/40）。§7 的可选内部跟进（`build()` 校验成对）未做。

> 状态：**设计提案，待拍板**。本文覆盖最终清单 #21：是否把 `ParRuntime.Builder` 的包私有
> 时钟缝（commit `568e2d0`）公开成用户可注入的 TimeSource SPI。基线：dev/v0.3.0 HEAD dd12641。

## 0. 结论

- **推荐关闭**公开 SPI，内部缝保持包私有。六问第 1 问答「否」、第 4 问部分「是」；生产误配置会
  拿走同步 `map`/`runAll` 唯一的活性来源。完整否决理由见 §6。
- 用户的真实诉求（确定性地测试自己的超时路径）用「注入 timeout + latch 桩」已能确定性满足（实测
  20/20，§2.1），但仓库里没有任何文档写过这套写法。替代交付物是 BACKLOG O-11 自己写的第一步：
  「如何测试使用本库的代码」指南（§7），纯文档，不依赖本条拍板。
- 先例：idea-graveyard 与 decision-log 无时钟/TimeSource 条目；形状相近的先例是暂缓的任务装饰器
  SPI 与已删除的两个 listener SPI（§5）。

### 本文的自证边界

代码事实在 dd12641 上亲自核对。行为结论来自本轮探针（`/tmp/vf-proposals/p1/`，`javac --release 8`
自编 dd12641）：「今天」只用公开 API；「公开后」用同包探针调包私有缝 + 仓库测试替身 `ManualClock`
的拷贝模拟，即一个公开 SPI 能让用户写出的形状（`TimeSeamProbe` 场景 A/A3/B/B2/C/D）。**未做**：
设计或原型化真正的公开 SPI；独立评审。FINAL-CONCLUSION 对本条要求「跨内核改动须独立对抗性
审查」，本文只是审查前的输入。

## 1. 现状

### 1.1 内部缝

- `CancellationToken` 持有 Guava `Ticker` 作为 deadline 时钟域（`CancellationToken.java:54-61`）：
  子 token 继承父时钟，只有根 token 接受显式时钟，公开构造恒为系统时钟（`:97-99`、`:120`）；
  `remaining()`、bind 的过期检查与调度延迟都读它（`:174,190,230,259`），`Checkpoints` 的到期
  backstop 也读它（`Checkpoints.java:577`）。
- 注入点是两个包私有 Builder 钩子 `ticker(Ticker)` 与 `timeoutScheduler(ScheduledExecutorService)`
  （`ParRuntime.java:647-657`，javadoc 写「Test seam ... not a supported public SPI」）。
- 有意直读真实时钟的地方：有界清理等待（`BodyCompletionTracker.java:282,318`，javadoc `:233-236`
  「no virtual clock may fabricate」）；观测时间戳（`ScopedCallable.java:47` 固定
  `Ticker.systemTicker()`，`TaskGroup.java:558,564-566`）；`ParRuntime.awaitQuiescence`
  （`:407-430`，`Monitor` 真实时间等待）。
- 契约：`cancellation-propagation.md` §5.2（`:156-189`）——时钟与调度器成对沿 token 树传播、
  替换必须成对；有界清理等待与观测时间戳 MUST 保持真实时钟；虚拟时间不运行真实 worker。
- 根包 71 个测试类里只有 3 个用这条缝（`VirtualDeadlineTest`、`CancellationTokenTest`、
  `ParMapAndCombineTest`），其余仍是真实时间。

### 1.2 一处契约与实现不符（只影响本仓测试）

`Builder.build()`（`ParRuntime.java:659-664`）不校验成对，构造器各自取值（`:105`、`:114-116`）。
探针：

```
C  只注入 timeoutScheduler（系统时钟），50ms deadline，body 阻塞：真实 2s 后仍在运行（deadline 永不触发）
D  只注入 ticker（真实调度器），300ms deadline：真实 304ms 后 TIMEOUT；另一 1s deadline 的 body
   在真实 400ms 后读 remaining=PT1S
```

C 丢失活性，D 让 deadline 与 `remaining()` 分属两个时间域。今天只是测试缝的卫生问题；任何公开
SPI 都必须让成对成为结构（§4）。

## 2. 用户测试代码：今天 vs 公开后

设应用里有 `PriceService.price(sku)`：用 `par.map` 调下游，`TIMEOUT` 时返回 `Price.UNKNOWN`。

### 2.1 今天的最好写法（只用公开 API）

timeout 由构造器注入，测试给几十毫秒；桩阻塞在 latch 上并响应中断：

```java
@Test
void fallsBackWhenTheClientHangs() {
    PriceClient hanging = sku -> {
        Checkpoints.checkAwait(new CountDownLatch(1));
        throw new AssertionError("released");
    };
    PriceService service = new PriceService(runtime, hanging, Duration.ofMillis(50));

    assertThat(service.price("sku-1")).isEqualTo(Price.UNKNOWN);
}
```

实测（场景 A，×20）：TIMEOUT 20/20，最坏 67 ms。坑：若还要断言 `bodyCompletionConfirmed()`，只有
15/20——deadline 触发后派生的清理预算恰为 0（剩余 deadline 已耗尽），单次检查与被中断 body 的退出
赛跑；加 `closeGrace(Duration.ofSeconds(1))` 后 20/20、最坏 61 ms（场景 A3）。这与契约「超时引发的
关闭在预算耗尽后直接返回」（`task-group-cancellation.md:166-167`）一致，但没有任何用户文档写过。

### 2.2 公开后（假想 SPI：一个成对对象 `ManualTimeSource`）

```java
@Test
void fallsBackWhenTheClientHangs() throws Exception {
    ManualTimeSource time = new ManualTimeSource();
    ParRuntime runtime = ParRuntime.builder()
            .register(ParId.of("io"), pool)
            .timeSource(time)
            .build();
    CountDownLatch armed = new CountDownLatch(1);
    PriceClient hanging = sku -> {
        armed.countDown();
        Checkpoints.checkAwait(new CountDownLatch(1));
        throw new AssertionError("released");
    };
    PriceService service = new PriceService(runtime, hanging);
    CompletableFuture<Price> price = CompletableFuture.supplyAsync(() -> service.price("sku-1"));

    assertThat(armed.await(10, TimeUnit.SECONDS)).isTrue();
    time.advance(Duration.ofSeconds(30));

    assertThat(price.get(10, TimeUnit.SECONDS)).isEqualTo(Price.UNKNOWN);
}
```

对应探针场景 B：`outcome=TIMEOUT`，body 读到 `remaining=PT30S`（冻结），真实耗时 4 ms。

| | 今天 | 公开后 |
|---|---|---|
| 生产 timeout 是否要参数化 | 要 | 不要 |
| 真实等待 | 约 50–70 ms | 约 0 |
| 额外线程 | 无 | **必需**（`map`/`runAll` 同步阻塞调用线程，推进时钟的只能是另一个线程） |
| 同步协议 | 无 | **必需**（先等 deadline 已解析，再推进；§3 第 6 条） |
| 断言 body 退出 | 加 `closeGrace` | 同样要加 `closeGrace`（§3 第 2 条） |

公开后消掉了 timeout 参数化与真实等待，代价是每个测试多一个线程和一个同步协议。后者是同步出口
设计的直接后果，任何时钟 SPI 都去不掉。被消除的失败模式：生产路径上**没有**；测试里只消除了「真实
时间下的偶发超时/不超时」这类时序抖动，同时引入「先推进后解析」的新失误（§3 第 6 条）。（两段里的
`runtime`、`pool` 是测试夹具字段。）

## 3. 虚拟时钟的边界：哪些等待永远不受它控制

虚拟时钟只管辖 **deadline 何时触发**。以下等待恒为真实时间：

1. **body 的执行与 body 内阻塞**：`Checkpoints.checkSleep`、`checkAwait(latch, timeout)`、HTTP 客户端
   超时都是真实线程上的 JDK 超时。虚拟 deadline 只能**中断**它们，不能让它们更快流逝。
2. **有界清理等待**：预算在 token 时钟域推导，按真实时间花掉（`cancellation-propagation.md` §5.2
   MUST，`BodyCompletionTracker.java:282,318`）。理由：虚拟时钟不能让一个线程更快退出，伪造的
   流逝要么跳过等待（误报未退出），要么永不结束。后果：结果在虚拟 deadline 之前就收敛（例如兄弟
   失败触发 fail-fast）而时钟冻结时，派生预算是整段虚拟剩余时间；一个不配合中断的桩会让清理等待
   真的花掉它——生产尺寸的虚拟 deadline 变成生产尺寸的真实挂起，除非显式 `closeGrace`，而这又把
   §2.2 省掉的参数化请了回来。（`VirtualDeadlineTest.cleanupBudgetDerivesFromTheTokenClock` 正是靠
   「虚拟一分钟预算覆盖真实 200ms 退出」才通过。）
3. **观测时间戳**：submit/start/end 是跨线程的真实时间观测（`ScopedCallable.java:47`、
   `TaskGroup.java:564-566`）。结果里报告的时长不会与虚拟 deadline 对齐。
4. **`ParRuntime.awaitQuiescence`、executor 队列与拒绝**：真实时间。
5. **`Checkpoints.remaining()` 读的是 token 的虚拟时钟**：用户代码把它当超时交给真实客户端时，
   等于把虚拟域的预算按真实时间花——与库对自己清理预算的规则（域内推导、真实时间花）同形，只是
   现在推到了用户代码里。
6. **`advance()` 只推动已经解析出来的 deadline**：场景 B2——在调用解析 deadline 之前
   `advance(30s)`，deadline 从推进后的读数算起，这次推进作废，调用在真实 1s 后仍在运行，第二次
   `advance` 才 TIMEOUT。所以 §2.2 的 `armed` latch 省不掉。替代方案「暴露 `awaitScheduled(n)`」要求
   测试知道一次调用布防几个 timer，而组内只有严于组 deadline 的成员单独 bind
   （`TaskGroup.java:806-827`）——这是不该泄漏给测试的实现细节。

## 4. 与时钟域配对继承的关系

`cancel-intent-clock-domain-validation-2026-10-08.md` 的发现 4、5（`:50-61`）把「时钟 + 调度器」
定为成对沿 token 树继承的一个域，并修掉了只继承时钟时嵌套 deadline 在受控时间下永不触发的缺陷。
这条规则对公开 SPI 的约束：

1. **必须是一个成对对象，不能是两个 setter**。现有包私有钩子恰是两个 setter 且 `build()` 不校验
   （§1.2），C/D 两种不成对状态都是错的。
2. **成对即传染**：嵌套单元继承祖先的时钟与调度器（`MultiTaskContext.java:187-192`、
   `TaskGroup.java:567-569`）。应用注入的 TimeSource 会穿透到第三方库内部另建的 `ParRuntime`——
   一致性上正确，但对只想在自己测试里用虚拟时间的用户是意外。
3. **退役回退的论证依赖时钟种类**：`cancellation-propagation.md` §5.2 的例外配对论证「域调度器退役时
   回退到调用方调度器不会更晚触发」，前提是祖先要么在真实时钟上退役（延迟仍有效），要么是退役后不再推进的手动时钟。用户提供
   且跨 runtime 共享的时钟可以在 runtime 关闭后继续推进，这一 MUST 要对任意实现重新证明。
4. **触发动作的线程**：生产调度器把到期动作分派到 `timeoutActionPool`（`ParRuntime.java:559-561`）；
   `ManualClock.advance()` 在推进线程上同步执行到期任务及其取消级联（`ManualClock.java:15-19,118`）——
   评审记录明说确定性正是来自这一点（`cancel-intent-clock-domain-validation-2026-10-08.md:78`）。公开的
   `advance()` 只能二选一：同步级联（确定，但测试跑的是生产从不走的线程模型）或分派（同生产，但
   `advance()` 在级联完成前返回，非确定性回来）。

已经正确、公开后不变的部分：清理预算从 token 时钟推导（发现 4 的修复，
`VirtualDeadlineTest.cleanupBudgetReadsTheTokenClockExactly` 钉死），这正是 §3 第 2 条的边界。

## 5. 先例

- `design/decision-log.md` 与 `docs/zh|en/design/idea-graveyard.md` 全文检索 clock/ticker/时钟/
  TimeSource：**无条目**。
- 形状相近：任务装饰器 SPI **暂缓**（`extension-and-wrapping.md:339`）——「语义上无新能力……无真实
  需求信号」，附重开条件；`TaskListener` 与 `DeadlockDetectionListener` 两个 SPI 已删除
  （`decision-log.md:27-33`）。趋势是收缩 SPI。
- BACKLOG O-11（`BACKLOG.md:306-311`）原文就是「先补『如何测试使用本库的代码』指南……
  `timeSource(...)` 留作开放问题」，待决策 #3（`:341-342`）同。这份指南至今不存在：
  `docs/en/user-guide.md` 无测试章节，`docs/en/testing/` 只有库自身的报告测试设计。

## 6. 六问与推荐

| 判据 | 公开 TimeSource 的答案 |
|---|---|
| 1 消除哪类「忘记」？ | **否**。生产行为不变；收益只是测试成本（省掉 timeout 参数化与真实等待） |
| 2 能否参数化现有机制？ | 能：内部缝已存在，公开是参数化而非新机制 |
| 3 取消/deadline/上下文自动继承结构化语义？ | 部分：deadline 判定沿 token 树继承；清理等待、观测时间戳、body 内等待、`awaitQuiescence` 留在真实时间，用户要同时理解两个时间域（§3） |
| 4 隐式状态或第二套管道？ | **部分是**：时间源经继承跨 runtime 传染（§4.2）；手动调度下 timeout 动作在推进线程同步执行，而生产分派到 `timeoutActionPool`（§4.4）——测试跑的是生产不走的线程模型 |
| 5 失败形状能否用现有词汇？ | 能；但观测时长是真实时间、TIMEOUT 发生在虚拟时间，读结果时易混 |
| 6 撑破抽象边界？ | 否：runtime 级配置，不碰 Batch/Group/DAG 分工 |

**推荐：关闭**。否决理由（供 decision-log）：

1. 六问第 1 问「否」、第 4 问部分「是」；`first-principles.md` §四：任一条为「否」需重新设计或拒绝。
2. **安全**：生产误配置（冻结的时钟、永不触发的调度器）拿走 deadline——提交路径唯一的活性来源
   （根 `AGENTS.md` bind-before-submit 不变量）；同步 `map`/`runAll` 不可中断地等待、调用方中断不
   取消执行，调用线程永久挂起且无出口，并沿嵌套传染。对比 Guava `CacheBuilder.ticker` 误配置的
   后果只是缓存不过期——爆炸半径不在一个量级。`first-principles.md:13`。
3. **四个设计问题无满意答案**（§4：成对、传染、退役回退、触发线程）。只公开 `Ticker` 的「收窄版」
   正好落在场景 D 的不成对状态。
4. **真实诉求有不需要 SPI 的标准解**：注入 timeout + latch 桩断言结果（§2.1，确定性 20/20）；
   读 `Checkpoints.remaining()` 做分支的预算逻辑，由用户包一层自己的接口、测试里替换。
5. **「独立测试构件」变体不成立**：它要够到包私有缝，只能在主构件里新增公开桥接类型（那就是换了
   名字的公开 SPI，回到本提案本身；根 `AGENTS.md` Architecture 节把根包的包私有边界定为 Java 8 的
   封装边界），或拆分包（同名包跨两个 jar，在模块路径上不成立）。

重开条件：①出现注入 timeout/预算确实覆盖不了的具体场景（例如要测一个无法参数化 deadline 的第三方
组件），附证据；②且新设计在结构上同时回答 §4 的四个问题。

## 7. 替代交付物：测试指南（不依赖本条拍板）

`docs/en|zh/user-guide.md` 新增一节，要点全部来自本轮实测而非推测：

- timeout 作为配置注入，测试给几十毫秒；生产值不变。
- 桩阻塞在 latch 上并响应中断（`Checkpoints.checkAwait`），不用 `sleep`，不用不可中断等待。
- 断言确定的 `TaskOutcome`，不断言耗时。
- 超时后要断言 `bodyCompletionConfirmed()` 时显式设 `closeGrace`：deadline 触发后派生预算为 0
  （实测不设 15/20、设 1s 后 20/20）。
- 需要「body 已开始」的场景用 latch 同步，不靠时序。
- 依赖剩余预算的业务分支，把 `Checkpoints.remaining()` 包进自己的接口，测试里替换。

可选内部跟进（与本条无关，只影响本仓测试）：把两个包私有钩子合成一个成对参数，或在 `build()` 校验
成对，让 §1.2 的不成对状态在测试缝里也不可表示。

## 8. 迁移路径

- **关闭**：无公开面变化，无迁移。decision-log 记 §6 的否决理由；idea-graveyard 记「公开时间源」及
  替代写法（§7）。
- **采纳**（不推荐）：纯新增（`ParRuntime.Builder.timeSource(...)` + 新公开类型），
  `scripts/check-api-surface.py` 不触发，CHANGELOG「Features」加一条；但跨内核，须先过独立对抗性
  审查，并逐条回答 §4。

## 9. 待拍板点

1. #21：**关闭**（推荐）/ 采纳。关闭则 §6 理由进 decision-log，idea-graveyard 加一条。
2. §7 测试指南作为 O-11 的交付物立即做（推荐：是；纯文档，与第 1 点无关）。
3. §7 的内部成对校验是否顺手做（可选；只影响本仓测试）。

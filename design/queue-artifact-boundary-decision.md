# queue 包产物边界决策（决策分析，已归档）

> 状态：**已拍板（2026-09-14）：维持现状，queue 包与 core 同产物发布。**
> 结论固化于 [adr/0006-queues-ship-with-core.md](../adr/0006-queues-ship-with-core.md)，
> 该 ADR 是本题的唯一权威依据。本文仅保留决策过程的背景事实：**产物边界已关闭，不要再在
> 设计评审、缺陷分诊或重构提案中重新提出拆分/删除 queue 包。**

## 1. 要决定什么问题

`queue` 包是随主产物（core）一起发布，还是拆为独立产物，或从公开面删除。
这是产品边界决策，不涉及任何运行期行为变化。

## 2. 现状（事实面）

**构成与体量。** queue 包含两个公开类，合计 2 180 行，约占 main 总量
（10 236 行）的 21%：

- `DrainingBlockingQueue`：1 642 行，全项目最大单文件。一个"逐渐关闭"的
  阻塞队列：OPEN→DRAINING→DRAINED 状态机、规则优先级瀑布、poison/mutations
  配置，六个构造器，`AutoCloseable`。它有一份独立的行为契约与 1 448 行
  专属测试。
- `VariableLinkedBlockingQueue`：522 行，容量可动态调整的 linked 阻塞队列，
  配套测试 1 289 行。

**引用关系（已逐一核实）。**

- `DrainingBlockingQueue` 在 src/main 中**零引用**（除自身与包 javadoc）。
  它是纯对外产品。
- `VariableLinkedBlockingQueue` 在 src/main 中只有一个引用点：root 包的
  `SmartBlockingQueue` 以它为委托实现。
- `SmartBlockingQueue` 位于 **root 包，不在 queue 包**。库自身从不构造它；
  它由用户自行实例化并安装到自己的 `ThreadPoolExecutor` 上。库内对它只有
  两处静态依赖：purge 启发式对它做 `instanceof` 以读取当前容量；它的
  `offer()` 是 `rejectEnqueue` 选项与 `TaskType.CPU_BOUND` 的唯一行为读者
  （命中时 `offer` 返回 false，触发池的拒绝策略，通常表现为提交者线程内联
  执行）。

**既有定位。** 项目说明文件明确将 queue 包定位为"独立的通用队列实现"
（independent general-purpose queue implementations）。即：它与 core 无代码
耦合是**有意的**，不是欠账。

## 3. 为什么现在必须定

库处于 0.x 预稳定窗口，公开面允许破坏性变更；0.3 将冻结公开面。冻结之后：

- **删除选项被真正锁死**——删公开类在冻结后不可做；
- **拆分选项技术上仍可行**（保持包名不变、core 传递依赖 queues 产物时用户
  无感），但每拖一个版本，直接声明 core 依赖的存量用户越多，迁移沟通成本
  越高。

因此"现在不决定"不等于保留选择权，而等于**默认选择维持现状**。本决策必须
显式拍板，三种结局都合法，悬置不合法。

## 4. 选项

### 选项 A：拆分为兄弟产物（`…-queues`，core 依赖它）

能搬走的只有 queue 包两个类及其测试、契约文档。`SmartBlockingQueue` 因依赖
core 类型（`TaskType`、提交作用域、任务上下文）**必须留在 core**，而它委托
`VariableLinkedBlockingQueue`，因此拆分后 core → queues 存在编译期依赖，
queues 不能反向依赖 core。

收益（校正后）：

- core 评审面缩减约 21%（10 236 → 约 8 056 行）；队列缺陷不再直接成为
  本库的缺陷，归属清晰；
- 依赖可选性：只需要队列的用户可只依赖 queues 产物，classpath 更小；
- 只需要 core 的用户经传递依赖拿到 queues，无感。

需要校正的一点：在同 reactor 构建下，两产物**发版节奏实际仍同步**，
"发布节奏解耦"不成立；拆分的真实收益是评审面隔离与依赖可选性两项。

代价：多模块构建与发布的长期维护——parent POM、模块版本策略、发布配置、
站点与文档的归属拆分。这是对一个 Java 8 基线库引入的永久性基础设施成本。

### 选项 B：从公开面删除（包私有化）

`DrainingBlockingQueue` 整体删除；`VariableLinkedBlockingQueue` 搬回 root 包
降为包私有（或内联进 `SmartBlockingQueue`）。评审面收敛最彻底。

代价：

- 砍掉一个**已有用户**的公开产品（`DrainingBlockingQueue` 零内部引用恰恰
  说明它的用户全在库外）；
- 与"独立的通用队列实现"的既定定位**直接冲突**——采纳本选项等于推翻该
  定位，需先修订定位再谈删除；
- 1 448 行契约测试与逐渐关闭契约一并作废。

除非愿意同时修订既定定位，本选项实质不在桌上。

### 选项 C：维持现状 + ADR 显式记录

零代码、零构建成本。core 永久多承担约 21% 评审面，但这条评审面有独立契约
与充分测试，且与 core 无耦合，评审负担是"多读一个包"而非"多一处纠缠"。

合法的前提是：以 ADR 显式记录"同产物发布是有意承担的评审面"，而不是继续
悬置。

## 5. 与其他待决策项的联动（核实后的精确表述）

- **`rejectEnqueue` / `TaskType` 语义的去向**：二者唯一的行为读者是 root 包
  `SmartBlockingQueue.offer`，提交路径的内联回退也在 core。**无论 queue 包
  去向如何，这段语义都留在 core**，不随包走。早前"队列策略随包走"的粗略
  表述据此校正——queue 包内没有任何读取 `TaskType` 的代码。
- **executor 可看透性（警告与分类）**：core 判断"池队列是否为
  `SmartBlockingQueue`"只需该类型的 `instanceof`，它在 root 包，不受本决策
  影响。
- **真正的跨边界点只有一处**：`SmartBlockingQueue` → `VariableLinkedBlockingQueue`
  的委托依赖。它决定拆分时 core 必须依赖 queues 产物（选项 A 的结构约束），
  或私有化时该类必须搬回 root（选项 B 的搬运成本）。

## 6. 结论（原推荐与退路）

分析阶段的推荐是选项 A（拆分），退路是选项 C（维持现状）。**2026-09-14 拍板：采纳
选项 C——不拆分，queue 包与 core 同产物发布**，理由是选项 A 引入的多模块构建与发布
基础设施成本（parent POM、模块版本策略、发布配置、站点与文档归属）对一个 Java 8 基线库
而言高于其收益（评审面隔离与依赖可选性），且"发布节奏解耦"在同 reactor 构建设想下本就不
成立。约 21% 的评审面作为有意承担的结论写入
[adr/0006-queues-ship-with-core.md](../adr/0006-queues-ship-with-core.md)。

## 7. 拍板问题清单（已作答）

1. 是否接受为多模块构建/发布付出长期维护成本？——**否，选 C（维持现状）。**
2. ~~若选 A：queues 产物的 artifactId 与版本策略~~——不适用。
3. 若选 C：ADR 中评审面措辞的确认（21% 评审面 + 独立契约 + 零耦合，作为有意承担的
   结论写入）——**已确认，见 ADR 0006 的 Consequences 与 Reconsideration。**

# 代码质量度量与 agent 自动化检测：独立分析

> 按任务要求于 2026-11-06 写入 `design/analysis/`，用途是评估静态质量指标及其与现有评审流程的结合；本文是临时分析输入，不是现行契约，不代表已批准实施。

## 结论摘要

本项目可以应用静态度量，但应把度量定位为**改变 agent 注意力的证据**，而不是“代码质量分数”。最值得做的不是再引入一套 SonarQube 或 Checkstyle，而是给现有 `refactoring-review` 增加一个 diff 作用域、可比较 base/head 的复杂度证据输入：

1. 用确定性采集器计算方法级 CC、Cognitive Complexity、长度、嵌套和参数数，输出 JSON；只把本次修改触及且显著增长的符号送给 detector。
2. 阈值集中在 `refactoring-review/config.json`；复杂度只生成候选项，不在 CI 中按绝对值判失败。
3. detector 必须解释“复杂度由什么业务/协议载荷构成、是否混合了可分离职责、建议什么重构、必须保持哪些可观察行为”；主 agent 按现有 T0/T1/T2、测试、PIT、race-claim 和对抗评审流程处置。
4. 第一版不引入 Maven 插件或外部服务。优先复用 JDK compiler tree API 或经过测试的源码解析器，避免用正则表达式把“近似扫描”伪装成精确指标。
5. 死代码、命名和包边界继续由现有编译器、Error Prone、自研脚本和 `PackageBoundaryTest` 承担；重复率、JaCoCo、jdeps、ArchUnit 只在出现具体诊断缺口时条件采用。

不建议本项目当前采用 Halstead、Maintainability Index、CK 主序列评分、全仓重复率硬门禁、函数长度硬门禁或 CodeScene。它们看起来量化、完整，但会把本仓库有意保留的并发协议、穷举分派和 JDK 移植对齐误判为待消除对象。

## 1. 本仓库的事实与现有能力

### 1.1 规模与风险分布

本次用只读 Python 做了物理行数统计和词法级近似扫描，没有运行 Maven、没有安装工具。近似 CC 的口径是 `1 + if/for/while/case/catch/&&/||/?` 的出现数；它用于寻找抽样对象，不是工具验收结果。

| 范围 | 文件数 | 物理行数 |
|---|---:|---:|
| 根包生产代码，不含 `queue` | 68 | 13,463 |
| `queue` 生产代码 | 3 | 2,191 |
| 根包测试，不含 `queue` | 105 | 29,578 |
| `queue` 测试 | 5 | 2,845 |
| `scripts/` 中 Python 文件 | 52 | 10,260 |

根包不是普通业务代码：近似扫描可见大量 `Atomic*`、CAS、interrupt 和 future 操作。根 AGENTS 与 `design/task-group-submission.md`、`design/task-group-lifecycle.md` 把提交顺序、取消归因、执行退出、线程恢复和可见性列为显式契约。因此，“可读性优化”本身也可能是并发协议变更，不能由指标驱动自动改写。

`queue` 虽与核心同产物，但阅读边界和变更约束不同。其 AGENTS 要求保留 JDK `LinkedBlockingQueue` 移植对齐，包括看似可现代化的代码和注释；`design/draining-queue-contract.md` 还规定锁顺序、用户回调出锁、弱一致遍历和逐渐关闭状态机。任何全仓统一阈值都必须把这一包单独分类。

### 1.2 已有检查并非一张白纸

| 现有能力 | 实际覆盖 | 对新方案的约束 |
|---|---|---|
| `mvn` 默认编译中的 Error Prone + NullAway | 编译期 bug pattern、空指针和 nullness；默认构建即失败 | PMD/Sonar 的通用 bug 规则大多不是净增量 |
| Spotless | Java 格式 | Checkstyle 的格式和排版规则重复 |
| opt-in SpotBugs High | 跨线程普通字段访问、注解/运行时契约不一致等 | `verification/spotbugs.md` 记录当前 High 项均为已审 false positive，不应无证据升级为 CI 门禁 |
| JUnit + Lincheck + PIT | 行为、窄交错、变异敏感性；PIT 还要求解释 `SURVIVED`/`NO_COVERAGE` | JaCoCo 行覆盖率不能代替这些行为证据，也不应设统一百分比目标 |
| CodeQL workflow | push、PR、周期安全/语义分析 | 再引入 Sonar 通用规则会形成第二套发现与处置入口 |
| `check-java-conventions.py` | 命名、消息、拼写、null-check 约定；base/head 双侧扫描和基线扣除 | 新确定性检查应复用“只拦新增、 touched 时迁移”的原则 |
| `check-api-surface.py` | 编译后 public/protected API 差异及迁移文档 | 比源码文本耦合指标更直接地保护公共演进成本 |
| `PackageBoundaryTest` | 只允许根包和独立 queue 包；queue 不得依赖核心 | 当前包级依赖方向已有可执行门禁 |
| `check-race-claims.py` | 用反向 mutation、测试或代码证明验证注册的顺序主张 | 复杂度触发的并发重构必须接入它，而非只看重构后测试变绿 |
| `process-metrics.py` + delivery ledger | 记录评审发现、处置、门禁失败和成本 | 新指标是否值得机械化，应由发现质量与处置结果校准 |

`refactoring-review` 尤其关键：`scripts/refactoring-review.py` 已实现 diff 分类、lens 触发、分片、预算、只读 detector、JSON schema、finding 合并和 refactor 停止规则。`refactoring-review/principles.md` 已规定：

- detector 只读，主 agent 负责核实和应用；
- finding 必须有具体重构动作与收益，而非只报名词；
- 行为包括顺序、可见性、中断标志、取消是否到达运行体和资源生命周期；
- 并发协议、公共契约和 out-of-diff 代码是 T2，只能建议；
- 重构不得修改测试断言、输入或时序；
- 每次变更只跑一轮，不为“清零 finding”反复重写。

所以闭环应接入这里，不应另造“质量分数 → 自动改代码 → 再跑分数”的第二管道。

现有 ledger 只有一份记录，其中 S03 有 2 次、S02/M05 各有 1 次发现。这说明结构 lens 确实能产出候选，但样本远不足以证明某个复杂度阈值或重复率阈值有稳定预测力；不能从这一个小样本直接推导硬门禁。

## 2. 并发内核里，复杂度是否真的等于难读

**不是稳定的负相关。** CC 统计分支数量，不统计读者需要同时保持的不变量、状态所有者、线程角色和 happens-before 边；Cognitive Complexity 加入嵌套权重，更接近局部阅读负担，但仍不知道一个分支是否是领域本身必需的穷举。二者都适合回答“哪里值得读”，都不能单独回答“哪里应该简化”。

本次近似扫描和源码抽样给出了直接反例与正例：

| 符号 | 近似信号 | 为什么高分可能是载荷 |
|---|---|---|
| `ImmediateResult.fold` | CC 约 16，但仅约 23 行 | 大部分分支来自对 `TaskOutcome` 的穷举 `switch`；连续 case 分组和即时 return 很容易线性阅读。拆成查表或多态反而可能增加概念，违背现有“穷举结果匹配”设计 |
| `ExecutorWaitGraph.cycleThrough` / `cyclicComponents` | CC 约 10 | BFS、父链重建和相互可达判断是算法步骤；高分支数提示需要测试和解释，不证明存在可删除职责 |
| `TaskGroup.prepare` | CC 约 21、约 157 行 | 同时承载统一 admission、deadline 时钟域、成员准备、terminal 准备、失败回滚和 wait-graph 注册。这里有真实的阅读成本，但顺序本身是协议；只能按有名字的相位提取，不能为降数移动 bind/submit 或失败清理 |
| `TaskGroup.memberCompleted` | CC 约 17 | counted CAS 的恰好一次语义、取消归因、级联、terminal 提交和 finally 中最后递增屏障彼此有顺序约束。局部简化可能直接破坏完成活性 |
| `CancellationToken.bind` | CC 约 11、约 79 行 | 已过期 deadline 同步取消、终态 token 跳过布防、first-wins 后仍无条件取消绑定工作、scheduler 退役回退都是契约载荷 |
| `ExecutionPhaseHintFuture.run` | CC 约 14 | phase claim、借用线程中断隔离、inline violation、迟到失败日志、执行退出和 delivery 握手集中在一个生命周期边界；拆分必须保留清理顺序 |

因此应区分两类高分：

- **协议/领域载荷**：分支分别对应合法 outcome、生命周期状态、竞态窗口或算法步骤。处置可以是保留并补 guarding test、注册 race claim、改善命名或注释，而不是降分。
- **偶然的阅读成本**：无关职责混在一起、同一条件反复嵌套、协议步骤与字符串格式化混杂、拷贝出的条件逐渐分歧。这时才有 Extract Function、Split Phase、复用 helper 等候选。

`refactoring-review/lenses/structure.md` 的 S02 已采用第二种语义：“一次阅读无法容纳步骤”或“混合抽象层次”，并明确抑制携带语句顺序证明的机械提取。数字指标最多帮助找到 S02 候选，不能替代 S02。

## 3. 清单逐项判定

### 3.1 确定性静态指标

| 指标 | 判定 | 本仓库理由与使用边界 |
|---|---|---|
| Cyclomatic Complexity | **条件采纳** | 作为 changed-method 的注意力信号和趋势，不作质量分或 CI 绝对阈值。`ImmediateResult.fold` 已证明高 CC 可以非常清晰；`TaskGroup.prepare` 则说明它可与真实阅读成本同时出现，需要 agent 再判别 |
| Cognitive Complexity | **条件采纳** | 若由可靠 parser 计算，可优先于 CC 表示嵌套阅读负担；仍只生成候选。不要把 Sonar 的阈值直接变成并发内核的通过条件 |
| 函数长度 | **条件采纳** | 仅当本次变更使方法增长，且与 CC/嵌套或 S02 同时出现时触发。`TaskGroup.prepare` 值得审查，但长构造器、穷举声明和顺序协议不能仅因长度被拆 |
| 嵌套深度 | **条件采纳** | 对新增深层业务分支有提示价值；queue 的锁内两阶段操作、内核的 cleanup/finally 不得为降层数而改变资源顺序。作为复合信号，不独立触发 |
| 参数个数 | **条件采纳，弱信号** | 只在多个参数反复共同穿过本次修改的签名时交给 M02；公共 API、JDK override、构造期不可变快照和显式协议参数不强改。参数对象若增加新概念，按现有原则是 T2，而非默认优化 |
| Halstead | **拒绝** | 操作符/操作数数量无法区分协议载荷与偶然复杂度，也不能为 agent 指出具体重构；与 CC、长度相比没有可预期的增量 |
| Maintainability Index | **拒绝** | 它把 Halstead、CC、行数等压成一个总分，会掩盖究竟是哪一段、哪种责任造成问题；本仓库需要的是可处置 finding，不是不可解释的综合评级 |
| CK：WMC/DIT/NOC/CBO/RFC/LCOM | **拒绝作为常规门禁** | 根包有意把 API 与包私有内核放在一起，CBO/LCOM 会把这一既定边界误报为问题；`TaskGroup` 的 WMC 很大程度是协议总量，DIT/NOC 对大量包私有实现和值对象诊断力弱。若未来做跨版本模块趋势研究，可仅作报告重评 |
| 重复率 / Type-1/2/3 clone | **条件采纳** | 只考虑本次修改的核心生产代码，并与 S03/M05 的语义确认结合；不采用全仓百分比硬门禁。queue 与测试需单独处理，见下文 |
| 死代码 | **采纳现有能力，不另建平台** | 新引入的 unused import/private member/constant branch 应保持为零；优先使用 javac/Error Prone，语义上“不可达”的分支交给 S06/D lens 和测试。公共 API、反射、future callback、executor 调用不能按零静态调用点直接删除 |
| 依赖环 / SCC | **条件采纳窄规则** | 当前只有根包与 queue 两个包，且 `PackageBoundaryTest` 已固定包集合及 queue→core 禁止方向；再做包级 SCC 没有增量。未来新增包时扩展现有边界测试即可。根包内部类级 SCC 不与模块环等价，不应一刀切 |
| 耦合、不稳定性 I、抽象度 A、主序列 D | **拒绝作为评分** | 根包同时包含 public API 与内核是明确设计，不是需要偏离主序列纠正的结构；这些聚合值无法指出取消、executor 或结果契约的真实扩散成本。扩散成本继续由 M03/M04/M07 与 API surface diff 判断 |

### 3.2 重复检测在 `queue` 上会发生什么

`queue` 的两个实现保留了 JDK 队列的节点、offer/poll、drain、迭代器和锁内操作形状。jscpd/Sonar 的 token clone 不知道“与 JDK 对齐”是维护目标：

- Type-1/2 会把有意相同的链表操作和 JDK 移植片段报成重复；
- Type-3 更容易把锁内检查、链表遍历和弱一致迭代视为相似，却无法判断两处的生命周期规则不同；
- 合并这些代码会引入新的共享 helper 和参数，增加与上游 JDK 对照的难度，违反 queue AGENTS 的 parity 要求；
- 测试也不能套用统一重复率：T04 明确允许两个测试保留各自可读的 setup，只有三处以上才考虑 helper。

建议默认从核心变更的重复候选中排除 queue，而不是给 queue 配一份逐渐膨胀的 suppression 清单。只有任务明确针对 queue 时才单独出报告，并将所有重构建议置为需要人工判断的 T2；不得把“重复率下降”当作优化成功标准。

## 4. 工具与现有能力的重叠

| 工具 | 判定 | 重叠与净增量 |
|---|---|---|
| Error Prone + NullAway | **保留并优先使用** | 已是默认编译门禁，直接阻止错误进入构建；新增工具的规则应先与这里去重 |
| Spotless | **保留** | 已覆盖格式一致性；不需要 Checkstyle 再管排版 |
| SpotBugs | **保留 opt-in，条件升级** | 对跨线程字段和注解契约有增量；现有文档要求只有发现真实 High 缺陷且 Error Prone 漏报时才进入 CI |
| PIT | **保留，优先级高于覆盖率分数** | 能证明测试对具体行为变异是否敏感，且已有范围、时效和 survivor 处置规则 |
| CodeQL | **保留** | 已覆盖通用语义/安全分析，避免再引入一套重复 bug 规则及其 triage 队列 |
| PMD | **条件采纳，仅限经过验证的净增量规则** | 通用 bug/style 与 Error Prone、SpotBugs、CodeQL、Spotless 高度重叠。若能以低成本提供稳定的方法 AST 指标和机器可读结果，可只做采集器；引入 Maven 插件/规则包需审批，不建议一次启用整套规则 |
| Checkstyle | **拒绝** | 格式由 Spotless，命名/消息/拼写由自研 gate 和 lens V，语义检查由 Error Prone 等承担；再引入主要增加 suppressions 与重复失败 |
| SonarQube | **拒绝当前引入** | 需要服务端或外部平台、质量门和另一套 new-code/处置模型；其规则、重复、覆盖和评级与现有能力大面积重叠。本项目缺的是把少量信号送入既有 agent 流程，不是另一个 dashboard |
| ArchUnit | **拒绝当前引入；出现新边界时重评** | 当前两个包的形状和 queue 方向已由 `PackageBoundaryTest` 无新依赖地固定。ArchUnit 的通用包环/分层规则反而会诱导拆分根包。只有出现该测试难以表达的依赖规则时才值得新增 test dependency |
| JaCoCo | **条件采纳为临时诊断报告** | 可帮助定位从未执行的语句/分支，但 PIT 已提供 `NO_COVERAGE` 和变异证据。只在 PIT scope 诊断不足时添加，不设总覆盖率目标、不作为 agent 优化分数 |
| jscpd / Sonar duplication | **条件采纳为本地候选报告** | 可对 changed core files 提供 token 级线索；S03/M05 已有语义处置。queue、测试和公共 API 相似样板需排除或人工确认，先校准 precision 再谈门禁 |
| jdeps | **条件采纳为发布/依赖审计** | 可净增“是否意外使用 JDK internal API、实际依赖哪些模块”的证据；不负责代码复杂度。Java 8 编译和真实 Java 8 runtime 已覆盖基线兼容，不必为日常 CI 再加一遍 |
| CodeScene | **拒绝当前引入** | hotspot/change coupling 需要持续历史与缺陷数据来解释；当前 process ledger 只有一份记录，尚无证据表明历史耦合比 M  lens 和实际缺陷复盘更能指导变更。外部服务还增加成本与代码托管边界 |

## 5. 闭环路线比较

### 路线 A：新增独立确定性脚本

形状：`scripts/check-complexity.py` 分别扫描 base/head，按方法身份做差，输出 JSON，再让 agent 读取。

优点：

- 可精确控制 diff 范围、queue 排除、基线扣除和输出 schema；
- 不需要 CI 新 job，可先本地运行；
- 与 `check-java-conventions.py` 的“新增才报、旧代码 touched 时处理”一致；
- 可把阈值与“失败”解耦：脚本成功产生证据，候选是否存在是另一个字段。

缺点：

- 若用正则统计，泛型、lambda、匿名类、嵌套方法和 `switch` 容易误算；`verification/java-conventions.md` 已明确承认文本级 heuristic 的边界，复杂度比那四条约定更依赖语法树；
- 若只生成独立报告而无人接入 plan/brief，它会退化为没人读的 dashboard；
- 自研 parser、base/head 方法匹配和测试有维护成本。

结论：**可作为采集器，不应成为独立硬门禁，也不应成为孤立报告。**

### 路线 B：PMD / Sonar / ArchUnit 等现成工具或 Maven 插件

优点：

- AST 与指标口径成熟，减少自研解析错误；
- PMD 一类工具能较快产出 XML/JSON 供后续转换；
- ArchUnit 对真正复杂的依赖规则表达力强。

缺点：

- 新依赖/插件需审批；Sonar 还引入服务与质量门，ArchUnit 当前没有规则缺口；
- 现成阈值面向通用 Java，不理解本仓库的协议载荷、根包边界和 queue parity；
- Maven 插件天然容易变成构建失败，而本项目需要的是“候选 + 解释 + 处置”；
- 与现有 Error Prone、SpotBugs、CodeQL、Spotless、PIT 和 PackageBoundaryTest 重叠，可能增加 triage 而非减少缺陷。

结论：**可以做一次只读原型来校验采集器数值，但不建议作为首选落地形态。** 如果自研 AST 采集成本高于接入 PMD 的单一指标规则，再重新比较，而不是同时启用 PMD 全家桶。

### 路线 C（推荐）：确定性度量 sidecar + 现有 refactoring-review 闭环

这是路线 A 的可靠采集器与现有流程的组合，不新增第二套质量平台：

```text
base/head 源码
  -> 确定性 AST 度量与差分
  -> plan 的 metrics.json（只有证据，没有 verdict）
  -> 按变更方法路由给 S / M / C detector
  -> finding：原因 + 具体 move + behaviorRisk + guardingTests
  -> 主 agent 按 T0/T1/T2 核实、拒绝、 defer 或应用
  -> 原有 tests / race claims / PIT / adversarial review
  -> ledger 记录信号是否产生有效 finding
```

推荐它的原因不是“复用旧东西”本身，而是现有实现已经解决了本问题最难的部分：只读检测与应用分离、finding schema、预算、并发协议分级、测试 oracle 保护、停止规则和事后度量。新增 sidecar 只补“哪里值得看”，不替换任何判断。

## 6. 推荐闭环的具体契约

### 6.1 度量放在哪一层

- **生成层**：`scripts/refactoring-review.py plan` 在生成 detector brief 时同时生成机器可读 `metrics.json`。独立命令可保留用于调试，但不是另一个必须运行的门禁。
- **检测层**：只把与 detector 文件/符号重合的候选摘要注入 brief。S 看局部结构，M 看跨签名参数和重复，C 在并发路径上审查行为风险。
- **处置层**：主 agent 写入现有 report 的 Dispositions；允许结论是“保留高复杂度，因为它是协议载荷”，并记录 guarding tests 或 race claim，而不是必须改代码。
- **CI 层**：第一版不新增硬失败。CI 只需在已有脚本测试/job 中覆盖采集器正确性；是否要在 PR 展示摘要可后续决定，任何 workflow 修改另行审批。
- **历史层**：将“触发数、有效 finding 数、被拒绝数、保留原因、验证成本”接到 delivery ledger。没有这些结果，就无法判断阈值是否有 ROI。

### 6.2 阈值放在哪里、如何差分

所有数字集中在 `refactoring-review/config.json`，符合其“numbers and patterns live only in config”规则；脚本只读取配置，不在 Python/Java 中藏第二份常量。

建议用 `metrics` 或同等单一配置区描述：

- 路径分类：首版只含核心生产 Java；queue 和测试默认排除；
- 指标开关与候选阈值：CC、Cognitive、长度、嵌套、参数；
- 最小变化量：要求本次变更确实增加复杂度，避免每次修改都重报旧热点；
- 最大候选数：并入现有 finding budget，避免度量挤占真正缺陷审查。

差分身份使用 `path + 类型 + 方法签名`，不使用行号。base/head 两侧必须都计算：

- 新增方法：超过关注阈值可成为候选；
- 已存在且本次触及的方法：只有超过阈值且相对 base 增长达到配置幅度才成为 in-diff 候选；
- 未触及的高分方法：不进入本次修复范围，至多按 R2 作为 out-of-diff 信息；
- 删除或复杂度下降：不生成优化任务；
- 方法签名变化：明确标为无法配对或新增，不假装知道其历史趋势。

通用阈值如 CC 10、Cognitive 15、长度 80 只能作为**首轮校准起点**。发布前应用若干历史 diff 回放，观察是否稳定指向 S02/M02 的有效 finding；不能直接把 `TaskGroup.prepare` 的当前高分转换成失败。

### 6.3 JSON 应包含什么

确定性输出只陈述事实，建议至少包括：

```json
{
  "path": "src/main/java/.../TaskGroup.java",
  "symbol": "TaskGroup#prepare",
  "pairing": "modified",
  "touchedByDiff": true,
  "metrics": {
    "cyclomatic": {"base": 18, "head": 21},
    "cognitive": {"base": 20, "head": 24},
    "physicalLines": {"base": 140, "head": 157},
    "maxNesting": {"base": 3, "head": 4},
    "parameters": {"base": 3, "head": 3}
  },
  "triggers": ["cyclomatic-growth", "length-growth"]
}
```

不要输出 `qualityScore`、`passed` 或 `mustRefactor`。这些字段会预先做出只有 agent 和维护者才能做的判断。示例数字仅说明 schema，不是对当前代码的精确工具测量。

### 6.4 如何要求“原因解释 + 处置”

在 S02/M02 相关 brief 中增加必填说明，但继续复用现有 finding 字段，避免另建报告格式：

- `rationale`：指出复杂度来自哪些具体分支/相位，为什么它们可分离或不可分离，收益是让哪类变化、测试或推理更容易；
- `refactoring`：给出具体 move；若应保留，则不生成 finding，不能把“降低指标”写成 move；
- `behaviorRisk`：逐项列出顺序、取消、deadline、interrupt、visibility、资源释放或公共签名风险；
- `guardingTests`：列出实际覆盖该契约的测试；
- `tier`：并发协议或公共契约至少 T2；仅私有、局部且行为保持的提取才可能是 T0/T1；
- disposition：主 agent 可 `applied` / `rejected` / `deferred` / `review` / `reverted`，并记录原因。

尤其要禁止以下无效输出：“CC 为 21，建议拆方法”。它没有说明哪些职责可分离，也没有说明拆分如何保持协议，按 R4 应被丢弃。

### 6.5 如何避免自动简化破坏并发协议

1. **不自动应用**：detector 永远只读；指标不得直接调用编辑工具或触发自动修复。
2. **先定性质再定级别**：复杂度候选若触及 cancellation、interruption、deadline、visibility、context、executor binding 或 queue，即进入对应 Task Route；不是普通局部重构。
3. **协议变化是 T2**：`TaskGroup.prepare` 的准备/注册顺序、`memberCompleted` 的最后屏障递增、`CancellationToken.bind` 的取消顺序等不能为了数值下降而调整。
4. **测试 oracle 不动**：行为保持重构不得改断言、输入或时序；需要改测试 oracle 的，停止并重新分类。
5. **验证与风险匹配**：targeted + full `mvn test`；已注册顺序主张运行 `check-race-claims.py`；按 `verification/pit.md` 选择变更类与协作者并处置 survivor；必要时 Lincheck 只覆盖窄交错。
6. **评审覆盖重构**：按根 AGENTS 将并发敏感变更送入独立对抗评审，并把每个 T1 项列为攻击面。
7. **不追求收敛到零**：遵循 R7，一轮处置后停止；剩余高分可以是已解释的保留项。

## 7. 权限与成本边界

### 无需额外询问的本地工作

在真正实施且维护者已选择方案后，以下属于现有授权范围：

- 新增/修改仓库内 Python 或 JDK 自带能力实现的采集脚本、`refactoring-review/config.json`、lens/brief 和相应脚本测试；
- 运行本地测试、格式化和仓库检查；
- 生成 git-ignored 的 JSON/报告，运行只读历史 diff 校准；
- 对确认可行为保持的候选走现有实现、评审、PIT 和提交流程。

这里的“无需询问”不等于本分析已授权实施；根 AGENTS 明确“分析请求只授权分析”。若采集机制本身形成新的重要流程契约，仍应按仓库提案规则先确定方向。

### 必须先问维护者的工作

- 向 POM 添加 PMD、Checkstyle、JaCoCo、ArchUnit 等依赖或 Maven 插件；
- 为 jscpd 等引入新的包管理依赖；
- 新增/修改 `.github/workflows/` 中的 CI job、质量门或外部上传步骤；
- 引入 SonarQube/CodeScene 服务、凭据或外部代码分析边界；
- 修改 build JDK、Java 8 baseline，或做依赖主版本升级；
- 由复杂度建议进一步改变低层并发协议时，按根 AGENTS 另行取得人类确认。

JDK 自带的 `jdeps` 和 compiler tree API 不需要新增依赖；但把前者加入 CI 仍因 workflow 修改需要审批。

## 8. ROI 与落地顺序

| 顺序 | 工作 | 新依赖 / 新 CI job | 校准成本 | 预期收益 |
|---:|---|---|---|---|
| 1 | 先强化 S02 的“载荷 vs 偶然复杂度”解释模板，并让 ledger 记录拒绝原因 | 无 / 无 | 用少量真实变更校准措辞 | 最低成本消除“只报数字”和自动降分倾向，即使后续换工具也保留价值 |
| 2 | 实现路线 C 的 AST 采集、base/head 配对、JSON 与 plan/brief 接入；首版仅 CC + 长度 + 嵌套 | 可无（JDK compiler API）/ 无 | 中等；需 parser 单测和若干历史 diff 回放 | 直接形成用户要求的“检测→agent 解释→处置”闭环，且不改变 CI 通过条件 |
| 3 | 根据有效 finding/误报率加入 Cognitive 或调阈值；仅在明确缺证据时启用参数数 | 无 / 无 | 至少数轮按处置结果调整 | 把预算集中到可改变阅读成本的信号，避免一次铺满指标 |
| 4 | 若 S03/M05 的持续记录证明语义 detector 漏掉 token clone，再试 jscpd changed-core 报告 | 可能有 / 无 | 需测 queue/test 排除后的 precision | 针对真实缺口补重复检测，而非先买工具找问题 |
| 5 | PIT 诊断无法定位未覆盖路径时再临时引入 JaCoCo；依赖/发布需要模块证据时使用 jdeps | 视方案 / 原则上不新增 | 低到中 | 解决具体诊断缺口，不增加日常质量分数 |
| 6 | 新包依赖规则超出现有测试表达能力时再评估 ArchUnit | 有 / 可复用测试 job | 低 | 只在真实架构规则出现时获得增量 |

“需要校准几轮”不应理解为固定跑 N 次直到通过，而是每次都要用真实 finding 的 applied/rejected/deferred/reverted 结果检查信号价值；如果没有增量，停止扩展指标。

## 9. 明确不该做的事项与重开条件

| 看似正确的做法 | 当前不应做的原因 | 何时可重评 |
|---|---|---|
| CC > 10 或 Cognitive > 15 直接让 CI 失败 | `fold` 的穷举 switch、状态机和图算法会产生合法高分；会激励隐藏分支而非改善结构 | 有跨多次变更的误报/收益数据，且仍建议只限制新增异常增长，不评绝对质量 |
| 用 MI/Halstead/CK 合成可排序的质量分 | 聚合分无法给出具体 move，且与本项目根包结构和协议载荷冲突 | 仅作为独立研究时可测量；没有明确消费者就不进入流程 |
| 全仓 jscpd 重复率低于固定百分比 | queue 的 JDK parity 和测试可读性会被误伤；百分比可通过无意义改写下降 | 核心 changed-code 原型证明能稳定补充 S03/M05 |
| 引入 SonarQube 作为统一质量门 | 重复现有工具，新增服务与第二处置流程；不能解决 agent 解释和并发协议安全 | 项目需要跨仓库统一趋势/合规平台且有明确维护资源 |
| 引入 ArchUnit 固定分层/包环 | 当前包集合和 queue 方向已有 `PackageBoundaryTest`；根包 co-location 是决策而非 smell | 出现新的、现有测试难表达的包依赖规则 |
| 用 JaCoCo 总覆盖率替代 PIT | 行被执行不等于错误会被发现；与现有 mutation 证据相比是降级 | 只作为定位覆盖空洞的补充报告 |
| 让 agent 循环重构直到指标通过 | 违反 R7，会制造无终止优化和协议风险 | 不应以降分为目标重开；应改开“具体缺陷/具体变更成本”任务 |
| 把 SpotBugs 所有级别设为默认门禁 | 当前已审报告显示中低级别多项为有意设计或 false positive | 发现真实增量缺陷后，按 detector 而不是按总量升级 |

## 10. 本分析的验证边界

- 已直接阅读根 AGENTS、POM、CI/CodeQL/dependency workflows、上述自研脚本、refactoring-review 规则与模板、相关 verification/design 文档、queue 契约和生产热点源码。
- 规模数字来自当前工作树的只读统计；方法 CC/长度是词法近似，只用于选择源码样本，不能作为未来采集器的 oracle。
- 没有运行 Maven、PIT、SpotBugs 或新工具，没有修改实现，也没有安装依赖；因此本文不对新工具在当前代码上的精确报告数量作断言。
- 对 PMD/Sonar/jscpd 等工具的判断是基于其能力类别与本仓库现有覆盖的架构性比较；真正选择采集器时，应以同一组历史 diff 的解析正确率、候选 precision 和维护成本做一次小型原型验证。

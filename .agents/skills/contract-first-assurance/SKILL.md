---
name: contract-first-assurance
description: 契约优先的独立验证工作流。用户要求独立验证 PR、从契约生成测试、检查实现与需求的行为缺口、复核取消/并发/生命周期语义或生成可审计验证报告时使用；也可通过 /contract-first-assurance 手动调用。先冻结契约，再在隔离上下文中生成候选测试，并在真实实现上分类验证结果。
compatibility: Requires git, an isolated worktree, and a fresh agent context; repository build and mutation commands come from the host adapter.
version: "1.1.0"
---

# Contract-First Independent Assurance

## 包和宿主解析

入口和四个阶段 skill 属于同一个版本化包。先读取本文件所在目录的
`package.yaml`，把该目录记为 `SKILL_ROOT`，再按以下顺序解析宿主配置：

1. 调用方传入的 run-specific adapter；
2. 仓库内的 assurance 配置；
3. 适用的 `AGENTS.md`、design/ADR 和 verification 文档；
4. `SKILL_ROOT/references/host-adapter.md` 中的通用规则。

宿主 adapter 负责工作区、变更范围、基线、构建命令、格式检查、mutation
策略和输出目录；本 skill 不假定 Java、Maven、Gradle、npm 或 PIT。缺少必需
的 adapter 输入时，保留 `BLOCKED` 或 `INCONCLUSIVE` 证据，不用猜测命令。

本轮的 `RUN_DIR` 是 adapter 解析出的 run 目录，默认是仓库根目录下的
`.contract-first-assurance/<run-id>`。所有阶段必须使用同一个 `RUN_DIR`，并在
`run-manifest.json` 和 `verification-summary.md` 中记录解析后的 adapter 值，使
独立 worktree 可以复现。运行清单和哈希规则见
[references/reproducible-runs.md](references/reproducible-runs.md)。

开始第一阶段前必须：

1. 解析并冻结 package、adapter、base/head commit 和 dirty 状态；
2. 将 contract 来源、脱敏 API、adapter 配置和 dirty worktree patch（如果有）复制到 `${RUN_DIR}/inputs/`；
3. 创建 `${RUN_DIR}/logs/`，所有命令保存精确 argv、退出码、stdout 和 stderr 路径；
4. 写入 `run-manifest.json`，为输入和最终工件计算 SHA-256。

## 目标

把一次 PR 的实现改动转换成可审计的契约验证流程：

```text
PR/工作区改动
  ↓
契约提取与缺口标记
  ↓
独立测试 agent 根据契约生成测试
  ↓
真实实现编译和执行
  ↓
行为级去重与失败分类
  ↓
反向验证
  ↓
契约修订建议和代码改进建议
```

调用本 skill 后，自动推进整个流程。除非遇到无法消除的外部阻塞或 AGENTS.md 明确要求人工决策，不重复向用户确认每一步。

## 调用模式

本入口允许两种调用方式：

- **自主调用**：当用户要求检查 PR 质量、独立验证实现、从需求或契约生成测试、发现实现与需求的行为缺口时，模型可以根据 `description` 自动调用。普通的“写测试”“修 bug”或“做 code review”只有在明确出现独立验证、契约缺口或隔离测试意图时才触发。
- **手动调用**：用户输入 `/contract-first-assurance`，无论当前请求措辞如何，都直接运行完整流程。

本 skill 的子流程 skill 默认关闭自主调用，只能由本入口显式调度或由用户直接输入其命令调用。

## 输入范围

优先顺序：

1. 用户给出的 PR URL、PR 编号或 commit 范围；
2. 当前分支相对目标开发线的 diff；
3. 当前工作区未提交改动。

开始前确认仓库根目录、当前分支、目标基线和 PR 改动范围。不要把无关模块、历史改动或整个仓库默认纳入 contract。

如果 PR 只涉及文档，仍可提取 contract，但跳过 Java 测试和 mutation testing，并说明跳过原因。

调用模式的跨平台开关见 [references/invocation-modes.md](references/invocation-modes.md)；
宿主配置和可复现边界见 [references/host-adapter.md](references/host-adapter.md)。

## 子流程调度

按阶段显式读取并执行对应子流程；不要让子流程依靠自然语言自行竞争触发：

- 提取契约：读取 `${SKILL_ROOT}/../contract-extract/SKILL.md`；
- 隔离测试生成：读取 `${SKILL_ROOT}/../isolated-test-generation/SKILL.md`；
- 结果归并和契约差异：读取 `${SKILL_ROOT}/../contract-diff/SKILL.md`；
- 最终报告：读取 `${SKILL_ROOT}/../assurance-report/SKILL.md`。

子流程必须继承本入口的 run-id、工件路径和隔离约束，但不继承主 agent 的对话历史。

## 阶段一：提取 contract

先读取适用的 `AGENTS.md`、目标模块契约、相关 design/ADR 和 PR diff。输出一份冻结的 contract 文档到：

```text
${RUN_DIR}/contract.md
```

每条 contract 至少包含：

- `C-<number>` 稳定 ID；
- 触发条件和前置状态；
- 正常结果；
- 异常、错误码或 outcome；
- 生命周期和资源所有权；
- 取消、deadline、上下文、executor 或队列语义；
- 可观察断言；
- 当前证据来源（PR、Javadoc、design、现有测试）；
- 明确的不确定点。

用 MUST、MUST NOT、SHOULD 和 MAY 表达强度。不要把实现细节自动写成用户契约。

同时生成：

```text
${RUN_DIR}/contract-gaps.md
```

把信息缺失分为：

- 缺少异常类型或校验顺序；
- 缺少生命周期边界；
- 缺少并发交错或可见性保证；
- 缺少取消/失败归因；
- 缺少输入边界和 nullability；
- 缺少最终结果与实时观察的区别；
- 缺少失败处理和资源清理要求。

## 阶段二：建立独立测试环境

必须使用真正隔离的 worktree 或等价沙箱。

### 上下文隔离是硬约束

测试 subagent 必须使用 fresh context 启动。调用时不得继承主 agent 的完整会话历史、实现 agent 的对话、实现 agent 的分析草稿、已有测试生成过程或之前的失败解释。使用协作工具时应使用等价于 `fork_turns: none` 的新上下文；不要使用会复制父会话历史的默认启动方式。

主 agent 只向测试 subagent 传递一个最小、冻结、可审计的输入包：

```text
contract.md
允许读取的脱敏 API/类型视图
必要的构建和测试命令
测试输出格式与候选 ID 规则
```

不得把以下内容放入输入包，也不得让 subagent 通过路径或工具自行发现：

- 主 agent 的分析、判断和对话历史；
- 实现 agent 的提示词、推理、代码草稿或修复记录；
- 真实生产源码的方法体和私有字段；
- 现有测试、测试报告、PIT 报告和之前的失败分类；
- 其他 agent 的候选测试、假设或 `candidate-status.csv`；
- Git 历史、父 worktree、主 checkout 或隐藏目录中的未冻结材料。

如果平台无法保证 fresh context，必须把本轮验证标记为 `BLOCKED` 或 `INCONCLUSIVE`，不得把同一会话中的测试生成称为独立验证。

测试 agent：

- 只接收冻结后的 `contract.md`、允许的 API/类型视图和必要构建信息；
- 不读取实现 agent 的推理、测试草稿、已有测试或父目录源码；
- 不读取 Git 历史来恢复隐藏实现；
- 不修改生产代码；
- 每个候选测试分配稳定 ID，如 `CONTRACT-TDD-001`；
- 写出前置状态、操作序列、观察点、断言和不确定性说明。

当需要隐藏实现细节时，使用 Java AST 或目标语言的语法树生成脱敏源码。保留类型、签名、泛型、注解、Javadoc 和公开常量，隐藏方法体、私有字段和内部调度细节。不要使用正则替换作为主要脱敏方法。

使用 subagent 时优先选择与实现 agent 不同的上下文。模型不同是可选项，上下文和工件隔离是硬要求。

启动 subagent 前先检查其工作目录中不存在真实生产源码、已有测试和其他 agent 的输出。测试 agent 完成后，再将候选测试作为单向工件复制到独立验证 worktree；不要把验证过程中的生产实现、失败日志或修正后的断言回传给测试 agent 继续“修正独立测试”。

## 阶段三：生成测试

根据 contract 生成与改动相关的最小测试集合，优先覆盖：

- 正常路径；
- 输入边界和组合非法输入；
- 生命周期转换；
- 失败和异常归因；
- cancellation、deadline、context、executor 绑定；
- 队列容量、FIFO、阻塞和唤醒；
- 并发交错和状态机；
- 可观察副作用、资源释放和 execution exit。

测试 agent 不要复制实现内部结构。断言应面向公共行为和契约，不依赖私有字段、调用次数或当前算法，除非这些本身属于契约。

候选测试、计划和假设保存到：

```text
${RUN_DIR}/candidates/
```

## 阶段四：真实实现验证

将候选测试复制到独立验证 worktree，在真实 PR 实现上运行：

1. 先运行候选测试的编译检查；
2. 记录每个编译失败；
3. 修正测试适配问题时不得修改生产代码；
4. 运行目标测试；
5. 对失败逐项判断是生产缺陷、测试错误假设、时序不稳定、环境问题还是 contract 不完整；
6. 运行相关现有测试，避免把新增测试与已有证据混为一谈；
7. 最后运行项目要求的完整测试和格式检查。

真实实现验证必须发生在测试 agent 已经结束并封存候选之后。不得让测试 agent 在看到真实实现的失败输出后继续修改候选测试；如需适配，主 agent 或单独的适配阶段负责，并保留初始候选和适配差异。

每个候选测试必须写入：

```text
${RUN_DIR}/candidate-status.csv
```

状态只能使用以下值：

- `新增覆盖`：现有测试没有提供相同的行为证据；
- `重复覆盖`：行为、前置条件和观察窗口与现有测试等价；
- `错误假设`：测试推导合理，但不符合当前明确契约；
- `编译失败`：API、泛型、异常或测试环境假设不成立；
- `运行失败`：已编译，但前置条件、时序或断言不成立；
- `不稳定`：依赖未定义调度或时间窗口；
- `oracle不足`：契约无法推出可判定的预期。

失败分类必须保留初始状态和修正后的最终状态，不得只报告最终绿色结果。

## 阶段五：行为级去重

禁止只按测试名称、文件名或覆盖率去重。比较以下内容：

- 输入和前置状态；
- 操作序列；
- 观察点；
- 断言和异常分类；
- 并发交错；
- 生命周期阶段；
- 是否检测副作用、清理或资源释放。

如果候选测试只是把现有测试换成另一种写法，标记为 `重复覆盖`。如果它暴露了文档中未定义的行为分歧，标记为 `错误假设` 或 `oracle不足`，并回写到 `contract-gaps.md`。

## 阶段六：反向验证

对每条 `新增覆盖` 测试至少选择一个对应生产行为做临时反向验证：

- 临时注入一个与 contract 相反的行为；或
- 使用 PIT、故障注入、状态扰动或等价的可控突变；
- 运行目标测试，确认它按预期失败；
- 精确恢复生产文件；
- 保存命令、退出状态和日志。

反向验证结果保存到：

```text
${RUN_DIR}/reverse-verification/
```

不要把临时突变留在工作树。PIT 必须遵守项目的 `verification/pit.md`，保留原始 mutation 状态；超时、内存错误、无 XML 或部分运行不得报告为通过。

## 阶段七：输出改进建议

最终生成：

```text
${RUN_DIR}/report.md
```

报告必须回答：

1. PR 实际承诺了哪些 contract；
2. 哪些 contract 缺少异常、边界、生命周期或并发细节；
3. 哪些候选测试提供了新增证据；
4. 哪些测试重复、错误或不稳定；
5. 哪些失败是实现缺陷，哪些是测试或文档问题；
6. 代码需要修复什么；
7. 文档/contract 需要补充什么；
8. 哪些验证通过、失败、阻塞或不适用；
9. 仍然存在什么不确定性。

建议按以下顺序给出改进：

```text
先修复明确的生产缺陷
再补充缺失的 contract
再加强新增行为的测试和反向验证
最后删除重复测试或合并重复契约
```

不要因为测试数量增加或覆盖率提高就自动建议保留所有候选测试。

## 自动推进规则

调用 skill 后：

- 自动创建输出目录和隔离 worktree；
- 自动启动独立测试 subagent；
- 自动执行编译、目标测试、全量测试、适用的 PIT 和反向验证；
- 自动保存中间工件和失败日志；
- 长任务每 1–2 分钟检查进程、日志和输出；
- 发现无 CPU、无输出或明显停滞时报告并按一次重试规则处理；
- 最终一次性汇报结果和未决项。

不得因为常规实现选择、测试修正、格式化、日志归档或失败分类反复向用户确认。只有以下事项可以暂停并请求决定：

- 公共 API 形状或 contract 语义有多个合理方向；
- 需要新增依赖、修改构建基线、发布、合并 main 或发送外部消息；
- 外部环境在规定重试后仍阻塞，且无法继续产生有意义证据。

## 最终工件

一个成功运行至少应包含：

```text
${RUN_DIR}/
├── run-manifest.json
├── inputs/
├── logs/
├── contract.md
├── contract-gaps.md
├── candidates/
├── candidate-status.csv
├── reverse-verification/
├── verification-summary.md
└── report.md
```

如果某一步未执行，必须保留对应的 `SKIPPED`、`BLOCKED` 或 `INCONCLUSIVE` 状态和原因。
最终报告前运行：

```bash
python3 ${SKILL_ROOT}/scripts/verify_run.py --run-dir ${RUN_DIR}
```

校验失败时，不能把本轮报告标为可复现或通过。

## 示例调用

```text
/contract-first-assurance
```

执行后，skill 根据当前 PR diff 提取 contract，启动隔离测试 agent，验证真实实现，完成去重和反向验证，并输出契约缺口与代码改进建议。

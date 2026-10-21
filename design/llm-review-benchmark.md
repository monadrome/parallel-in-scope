# LLM 评审基准契约

本文是 `tools/llm-review-bench` 与项目内 `/multi-review` 的活契约，约束谁可以产生评审证据、证据如何
保存、判定如何生效，以及哪些数字允许被报告。命令参数、JSON 字段与解析细节以工具的
[README](../tools/llm-review-bench/README.md)、[protocol.md](../tools/llm-review-bench/docs/protocol.md)
和 [clusters.md](../tools/llm-review-bench/docs/clusters.md) 为准；本文只固定决策与不变量，不复制
易漂移的文件清单、行号或测试计数。

## 1. 背景

多 agent 评审已经是本仓库的日常流程，但“哪个 reviewer、哪个模型、哪种 effort 真正找得到缺陷”长期
没有可复现证据。旧流程把四路 reviewer 的输出合并成一次会话内的 findings 和分诊，修复完成后证据随
会话结束而消失；不同 run 之间无法比较，无法回算某个 reviewer 的命中率，也无法回答“这次修复对应
上次哪条 finding”。把 reviewer 数量、severity 或一次演示当成质量结论，都会把“看起来审查过”误当成
“缺陷被找到”。

`tools/llm-review-bench` 把评审拆成三层：不可变的原始证据、显式的人工/agent 判定、可复算的指标。
它不自动判断语义，也不把一次 case 包装成模型排名。

## 2. 用户故事

并发库的维护者要判断新评审 agent 是否比当前组合更可靠。维护者需要：

1. 让多个 reviewer 看同一个不可变 git 快照，而不是各自看一个会漂移的工作区。
2. 保留每个 reviewer 的原始响应、日志、耗时、超时与失败状态，事后能审计。
3. 用统一的 finding 协议和显式聚簇规则，把“同一根因”与“同一行不同缺陷”分开。
4. 用四态 verdict 区分真缺陷、误报、有效但不可行动、证据不足。
5. 在后续独立 fix 出现时，把已存的 open-world 评审转成带 gold 的 case，而不重跑模型。
6. 任何时候都能只凭磁盘上的原始与派生记录复算指标，不依赖某次会话的内存。

## 3. 改前与改后

### 3.1 改前最佳用户操作

旧流程是仓库外的用户级多 agent 脚本，能力完整但只服务于一次会话：

```bash
# 用户级 multi-review skill 随附的 Python 脚本，不在本仓库
S=/path/to/multi-review-skill/scripts/multi_review.py
python3 $S prepare --repo . --working
python3 $S review <run> --detach
python3 $S wait <run> --max 1500
python3 $S merge <run>          # findings.md，按文件+行号聚簇
# 主 agent 分诊；争议项再走 kiro/codex/claude 三方投票
```

它的优点是启动快、上下文准备和修复闭环成熟；缺点是证据不可复算、没有 gold/recall、没有跨 run 的
统一协议，聚簇只是文件+行号的近似，投票结果也无法作为可审计的测量真值。

### 3.2 改后同一场景

从仓库根执行；`dist/` 缺失时先构建：

```bash
test -f tools/llm-review-bench/dist/cli.js || npm --prefix tools/llm-review-bench run build

# 日常：审未提交改动（open-world，无 gold）
node tools/llm-review-bench/dist/cli.js review --working --json

# 读取 runId 与 <runDir>/reviews/<agent>/attempt-N.json，亲自核实后写 clusters/verdicts
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json \
  --judge-kind agent --judge-name <name> --judge-model <model> --json

# 报告由固定版本的 evaluation 渲染
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

显式 fix benchmark：

```bash
node tools/llm-review-bench/dist/cli.js run --fix <fix-sha> --json
# fix 只生成 candidate gold；确认后导入 confirmed gold
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json --gold gold.json \
  --confirmed-by <name> \
  --judge-kind agent --judge-name <name> --judge-model <model> --json
```

后续出现独立 fix 时，不重跑 reviewer：

```bash
node tools/llm-review-bench/dist/cli.js link --run <run-id> --fix <fix-sha> --json
node tools/llm-review-bench/dist/cli.js rescore <run-id> --json
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

项目内入口是薄 skill [`.claude/skills/multi-review/SKILL.md`](../.claude/skills/multi-review/SKILL.md)：
它只负责构建、选 case、读取原始 findings、按规则生成判定 JSON 和调用 CLI，不实现评测算法，也不再
调用旧的 Python 流程。

### 3.3 消除的失败模式

- **证据随会话消失**：run 目录保存原始响应、日志和输入哈希，跨会话可审计、可复算。
- **timeout/failed 被当成空评审**：失败记录 `findings: null`，排除出分母并单独报告，绝不折算成 0。
- **用文件+行号或文本相似度冒充语义去重**：工具只提供 identity 基线，真实聚簇必须是显式分区。
- **没有 gold 却有“分数”**：open-world/candidate 的 recall 为 `null`，报告显示 `n/a`。
- **把决策投票当成测量真值**：投票可以决定修不修，但不产生可复算的 gold；基准只认显式判定。
- **自我修复回填 gold**：同一 run 的修复是 remediation，不能反过来证明该 run 找到了缺陷。
- **一次 case 被说成模型排名**：provenance 与 case 数被记录，单 case 结论被明确限制。
- **不可审计的“优化”**：派生产物按 `vN` 追加，旧评测仍可重放。

## 4. 架构

### 4.1 单一 TypeScript 编排器

`tools/llm-review-bench` 是一个独立 CLI，运行在 Node.js 22+，ESM，运行时只用 Node 标准库，构建只
依赖 `typescript` 与 `@types/node`；源码编译到 `dist/`，入口是 `dist/cli.js`。它不属于 Maven 构件，
也不改变库的运行时行为。

编排器负责 case 准备、reviewer 进程监管、协议校验、存储、评测和报告。项目内 MUST 只通过这个 CLI
产生基准证据；MUST NOT 在 skill、hook 或临时脚本里复制它的编排逻辑。

### 4.2 适配器

每个 reviewer 是一个 adapter：只负责构造 argv 计划、在共享进程监管下运行、把最终文本解析成统一
finding。生产 adapter 覆盖 Codex、Claude、Kimi、OCR、Kiro；fixture adapter 只用于测试和本地端到端，
不会被自动选中。

共享监管层提供独立进程组、逐 attempt 的 stdout/stderr 日志、硬超时（`SIGTERM` → grace →
`SIGKILL`）、以及前后 `git status` 脏检查。adapter 的只读 flag 与用户 `extraArgs` 的 bypass 拒绝规则
见 [adapters.md](../tools/llm-review-bench/docs/adapters.md)。

### 4.3 统一协议与存储

finding 在进入评测前被确定性规范化：仓库相对 POSIX 路径、有序行范围、`P0`–`P3`、`0..1` 置信度、
稳定 ID；原始响应另行保留，规范化不销毁原文。所有 JSON 读取拒绝重复键、尾逗号和注释；run id、
agent 名、cluster id、gold id 必须是单一安全路径段，文件路径必须是仓库相对 POSIX 路径并做逃逸检查。

存储是文件系统：run 目录保存不可变输入与原始记录，benchmark 根的 `gold/` 树保存 gold，派生记录按
版本追加。没有数据库、服务或后台进程。

### 4.4 评测与报告

评测层只读原始 review、派生 dedup/adjudication 和 gold，计算 per-reviewer、union、micro、macro
rates 以及 known-fix/regression recall，并写出带版本号的 HTML、CSV、JSON。报告是静态文件，无脚本；
所有用户或模型可控字符串都做 HTML 转义，CSV 对公式前缀做防注入处理。

## 5. case 类型与关系

| 操作 | 输入 | 调用 reviewer | gold | 关系 |
|---|---|---|---|---|
| `review` | 一个 commit（默认 `HEAD`），`base` 默认 `head^` | 是 | open-world | live，无 gold，无 recall |
| `review --working` | 暂存、未暂存、未跟踪改动 | 是 | open-world | 快照 commit + 命名空间 ref；不改真实 index/worktree |
| `run --fix` | fix commit | 是 | candidate | `buggy = fix^`，审 `buggy^..buggy`；fix diff 只生成候选 label |
| `resume` | 已有 run | 只跑未完成 reviewer | 沿用/重新派生 | 同一 run，不替换已完成记录 |
| `rerun` | 已有 run | 全部重跑 | 新 candidate/open-world | 新 run id，复用不可变输入；不继承旧 review/verdict/gold |
| `link` | 已有 run + 后续 fix | 否 | 新 gold 版本 | fix 必须从 run head 下降；不调用模型 |
| `rescore` | 已有 run | 否 | 沿用 | 只从原始与派生记录复算 |
| `report` | 已有 run | 否 | 不适用 | 按最新 evaluation 引用的精确版本渲染 |

`rerun` 不是 `resume`：前者是新的测量 case，后者只补完同一次测量。`link` 只增加 gold/evaluation
版本，不产生新的 reviewer 结果；`rescore` 和 `report` 都不调用模型。

## 6. 原始不可变与派生版本化

- `run.json`、`inputs/`（context、diff、prompt、case manifest、结构化 context）、
  `reviews/<agent>/attempt-N.json`、逐 attempt 日志、`derived/*/vN.json`、`gold/<run-id>/vN.json`
  和报告文件都是 write-once；第二次写同一路径失败。
- `state.json` 是唯一允许替换的 run 文件，用同目录临时文件 + fsync + rename 原子替换；
  `events.jsonl` 只追加，序号单调递增。
- 后续命令（`resume`、`rerun`、`link`、`rescore`、`report`、`apply-judgment`）在读取前校验输入
  sha256；输入被改动就中止，不“修复”旧证据。
- 新的聚簇、判定或 gold 一律写新的 `vN`；MUST NOT 覆盖、手改或用旧版本补丁。
- evaluation 记录它引用的 dedup/adjudication/gold 版本与 review 哈希；report 加载这些精确版本，
  而不是“最新版”，因此旧报告可重放。

## 7. 判定、gold 与 recall

### 7.1 四态 verdict

每个 cluster 只允许四种 verdict：

| verdict | 含义 |
|---|---|
| `confirmed` | 被审变更引入或实质暴露的真实缺陷，或真实的测试/文档契约缺口 |
| `rejected` | 不是缺陷：误报、纯风格、已修复、误读变更 |
| `neutral` | 有效观察，但在本 case 中不可行动，或只是可选精度改进 |
| `uncertain` | 证据不足或无法判断；保留可见，但不计入 confirmed/rejected |

没有 verdict 的 cluster 是 `pending`，不是 `rejected`；pending 从 rate 分母中排除并单独报告。
verdict 在导入时绑定当时的 `dedupVersion`；若之后出现新 dedup 版本，旧 verdict 视为 pending 并
记录 note，必须针对新 cluster 集合重新导入。

聚簇是显式输入：import 必须把每个 completed reviewer 的每个 finding 恰好分区一次；漏项、重复、
未知 ID 都是校验错误。工具不根据标题、行号或文本相似度推断语义。

### 7.2 gold 隔离与三种状态

gold 存放在 benchmark 根的 `gold/` 树，位于 run 目录之外，且 MUST NOT 出现在 review worktree、
prompt 或 agent output 目录。historical context 不包含 fix commit SHA；fix 只存在于审计元数据和
gold 记录中。

| 状态 | 来源 | recall |
|---|---|---|
| `open-world` | live/working review，无 fix | `null` |
| `candidate` | `run --fix` 或 `link`（默认）从 fix diff 生成 | `null`，直到显式确认 |
| `confirmed` | 显式 JSON，逐 label 声明 `introducedByBuggyCommit` 与 `confirmedBy` | 满足条件时计算 |

`link --gold` 可直接导入 confirmed gold，不经过 candidate。candidate label 的
`introducedByBuggyCommit` 是 `null`，不是真值；确认时必须给出布尔值。标为
`false` 的 label 表示不是该 buggy commit 引入，排除出分母而不是算作 miss，并用 `excludedFixScope`
记录原因。confirmed gold 没有任何 label 会被拒绝。

### 7.3 recall 边界

- `known_fix_recall` 与 `regression_recall` 只统计 `confirmed` gold 中
  `introducedByBuggyCommit === true` 且 `kind` 匹配的 label。
- 一个 confirmed cluster 只有在其 adjudication 显式列出 `goldLabelIds` 时才命中；工具不做标题、
  行号或文本匹配。
- 分母为该 kind 的 eligible confirmed label 数；分子为被至少一个 confirmed cluster 命中的 label 数。
- open-world/candidate gold、尚无任何已判定 cluster、或 reviewer 未完成时，recall 为 `null`；
  报告显示 `n/a` 及原因，绝不显示 `0`。
- 任何 rate 的分母为零时同样为 `null`/`n/a`，不是 `0`。
- 测试缺口可以是独立 confirmed cluster 并进入 rates；只有 gold label 本身是 `regression` 类型的
  测试缺口时才进入 regression recall。

## 8. 51 分钟分层预算与失败语义

默认总预算 51 分钟（3060 秒），分层为：

- reviewer 阶段 45 分钟（2700 秒）；
- adjudication 预留 5 分钟（300 秒）；
- cleanup 1 分钟（60 秒）；
- 硬超时后 `SIGTERM` 到 `SIGKILL` 的宽限默认 10 秒。

reviewer 阶段可用预算为 `min(reviewSeconds, totalSeconds - adjudicationSeconds - cleanupSeconds)`。
评审时钟从 reviewer 阶段开始计时；`resume` 为未完成 reviewer 开启新的 reviewer 阶段。adjudication
是显式 import，不是自动模型调用，5 分钟是预算护栏而不是固定等待。

超时必须按进程组终止并记录 `timed_out`，失败记录 `findings: null`。两者都不是空评审，都不进入
rate 分母；`resume` 可以用新 attempt 重试，旧记录和日志保持原样。MUST NOT 静默延长预算或把超时
折算成“没有发现问题”。

## 9. 日常 multi-review 与被动评测

项目内 `/multi-review` 是日常入口：它默认用 `review --working` 或 `review --commit` 产生
open-world case，读取原始 findings，主 agent 亲自核实后写入显式 clusters/verdicts，再调用
`apply-judgment`、`rescore`、`report`。它保留正常 code review 的修复职责：确认的 P0/明显缺陷照常
最小修复、补测试、跑项目检查并提交。

每次日常评审都**被动**积累一个 open-world 评价：有 per-reviewer rates 和证据，但没有 gold，也没有
recall。这是有意的低门槛数据收集，不是“质量分数”。

独立 gold 不能来自同一 run 的自我修复。理由不是工具限制，而是测量语义：该 finding 由同一 run 的
reviewer 产出、又在同一 run 里被判定，再用这次 run 促成的 fix 作为 gold，测到的是“自我一致性”，不是
“缺陷发现能力”；它还允许 agent 在看到答案后编写 gold。同一 run 的修复只在评审报告中作为
remediation 说明。后续若出现**独立于该 run** 的 fix（例如别人修复的回归、后续独立发现并修复的缺陷），才用
`link --run <id> --fix <sha>` 把已存评审转成 gold-assisted case，再显式导入 clusters/verdicts/gold。
`link` 只能验证 fix 从 run head 下降，无法验证独立性；独立性依据必须由判定者写入 gold 的
`provenance.note`（输入 JSON 的 `note`）。

## 10. 安全边界

- **gold 隔离**：gold 在 run 目录之外，不进入 worktree、prompt、agent-output；historical context
  隐藏 fix SHA。reviewer 仍可能主动遍历 benchmark 根或共享 git 对象库，因此 adapter 是可信的基准
  参与者，不是恶意代码。
- **reviewer 隔离是信任边界，不是 OS sandbox**：快照只读由策略和 adapter flag 保证，并由前后
  `git status` 检测；一个自带逃逸手段的 CLI 仍可能写入，检测到就失败该 attempt。
- **bypass flags 拒绝**：用户 `extraArgs` 中的 `--dangerously*`、`--yolo`、`--allow-all-tools`、
  `--trust-all-tools`、`--auto`、`--full-auto` 被拒绝；Kiro 必需的 `--trust-all-tools` 只存在于
  adapter 内部并记录为风险。
- **本地数据**：benchmark 根默认在 `.review-benchmark/`，已在根 `.gitignore` 中；原始日志可能包含
  模型输出或敏感片段，不得提交。
- **测试不调用真实模型**：测试使用临时 git 仓库与 fixture adapter；真实 CLI 只由显式运行触发。
- **并发**：每个 run 只允许一个 runner 进程；状态更新在进程内串行，没有跨进程锁，并发命令不支持。
- **working 快照 ref**：`--working` 在仓库 git 目录创建命名空间 ref，cleanup 不删除它；这是
  rerun 可复现的设计代价，目前没有自动清理命令。

## 11. MUST / MUST NOT

1. 项目内 `/multi-review` MUST 通过 `tools/llm-review-bench/dist/cli.js` 产生评审与基准证据；
   MUST NOT 调用旧 Python `multi_review.py`、三方投票脚本或另建编排。
2. 主 agent MUST 亲自核实 finding 后再写 verdict；reviewer severity MUST NOT 直接作为结论。
3. 同一 run 的自我修复 MUST NOT 作为该 run 或其 rerun 的独立 gold；`link` 的 gold MUST 记录
   独立性依据。
4. run 创建后，raw 证据（run manifest、inputs、review records、logs）MUST write-once；后续命令
   MUST 校验输入哈希。
5. derived dedup/adjudication/evaluation、gold 与 reports MUST 以 `vN` 追加；MUST NOT 覆盖或手改
   已有版本。
6. `state.json` MUST 原子替换；`events.jsonl` MUST append-only。
7. gold MUST 存放在 run 目录之外；MUST NOT 出现在 worktree、prompt 或 agent-output；historical
   context MUST 隐藏 fix commit。
8. clusters MUST 对每个 completed reviewer 的每个 finding 恰好分区一次；import MUST 拒绝漏项、
   重复、未知 ID。
9. verdict MUST 是 `confirmed`/`rejected`/`neutral`/`uncertain` 之一；缺省 MUST 视为 `pending`，
   MUST NOT 当作 `rejected` 或计入分母。
10. verdict MUST 绑定导入时的 dedup 版本；dedup 变化后旧 verdict MUST 视为 pending 并记录 note。
11. gold MUST 显式；candidate/open-world MUST NOT 进入 recall；confirmed label MUST 显式给出
    `introducedByBuggyCommit` 布尔值。
12. recall MUST 只由 confirmed gold、`introducedByBuggyCommit === true`、匹配 kind 和 confirmed
    cluster 显式 `goldLabelIds` 计算；MUST NOT 用文本、标题或行号推断。
13. open-world/candidate 的 recall MUST 为 `null`（报告 `n/a`）；MUST NOT 伪造 `0` 或百分比。
14. 零分母 rate MUST 为 `null`/`n/a`；MUST NOT 记为 `0`。
15. timeout/failed reviewer MUST 记录 `findings: null` 并排除出 rate 分母；MUST NOT 当作空评审。
16. 一个 case MUST NOT 被表述为模型排名；结果 MUST 记录 agent/version/provider/model/revision/
    effort provenance。
17. 默认预算 MUST 保持 51 分钟分层（45m review + 5m adjudication + 1m cleanup）；超时 MUST 按
    进程组终止并记录 `timed_out`，MUST NOT 静默延长。
18. dirty snapshot、changed input、stale adjudication MUST 显式失败或 pending；MUST NOT 自动修复。
19. adapter MUST 使用可用的只读 flag 与 `git status` 脏检查；用户 `extraArgs` MUST 拒绝 bypass
    flags。
20. 评审隔离 MUST 表述为信任边界而非 OS sandbox；MUST NOT 声称恶意 CLI 已被隔离。
21. 首期 MUST NOT 引入服务、数据库、dashboard、趋势分析或自动建 issue。
22. MUST NOT 引入自动语义 judge、文本相似度去重或单一总分。
23. 测试 MUST NOT 调用真实模型；MUST 使用临时 git 仓库与 fixture adapter 验证协议和进程行为。

## 12. 明确非目标

- 不做托管服务、数据库、Web 控制台、趋势分析或自动创建 issue。
- 不自动做语义去重、语义判定或缺陷修复；这些是显式输入或主 agent 的职责。
- 不把一次 case 当模型排名；rerun 本身是非确定性的重复测量。
- 不提供 Python 核心或第二套并行编排。
- 不改变库的公开 API、线程模型或构建产物；本工具是仓库内工具，不是 Maven 构件。
- 不做跨 run 的自动 gold 推断；`link` 只建立后续 fix 与已存评审的关系。

## 13. 被排除的替代方案

**纯 Skill / Hook 编排。** 只靠 skill 或 hook 无法稳定拥有 51 分钟生命周期的进程组、硬超时、
逐 attempt 日志、输入哈希校验和版本化存储；每次会话都要重新实现解析与状态机，结果不可复算。
hook 是事件级的，不能替代一个可审计的 run store。重新考虑的条件是平台提供持久、可审计的 run
存储，且仍采用本文的协议与失败语义。

**Python 核心（继续扩展 `multi_review.py`）。** 两套编排会让 finding 协议、聚簇规则、gold 语义和
指标口径分叉；旧的投票流程是决策辅助，不是测量协议，把它保留为核心会让“修不修”与“是否命中”
混在一起。单一 TypeScript CLI 让协议、校验和测试只有一个事实源，且运行时无第三方依赖。重新考虑
的条件是项目整体迁移到 Python，并在同一变更中退役本 CLI。

**文本相似度语义去重。** 同一行可能是不同缺陷，同一根因可能用完全不同的措辞；相似度合并会改变
分母，可能在没有证据的情况下“提高”分数。工具因此只提供 identity 基线，真实聚簇必须是显式分区
并由判定者写 note。没有重新考虑条件；若未来引入语义辅助，也只能作为建议，不能替代显式分区。

**单一总分。** 总分掩盖漏检、误报、pending 与不同 case 的难度差异，也容易被针对性优化。四态
rates、per-reviewer/union 计数、recall 分子分母和 notes 保留了可解释性。重新考虑的条件是提出
经过校准并公开权重的评分模型，且不与原始指标互相替代。

**首期服务或数据库。** 文件系统存储可 diff、可审计、无需运维，足以支撑单机、单 runner 的日常
评审；服务/数据库会引入部署、权限、并发与迁移成本，而当前没有跨机器聚合的硬需求。重新考虑的条件
是出现多用户并发写入或跨机器汇总的真实需求，并且迁移不破坏 raw 不可变与版本化语义。

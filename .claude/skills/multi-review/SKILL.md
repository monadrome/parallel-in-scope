---
name: multi-review
description: 项目内并行多 agent 代码审查与可复算评测。用于 multi-review、par-review、并行 review、交叉审查、审查当前工作区或某个 commit。默认通过 tools/llm-review-bench 的 TypeScript CLI 运行 reviewer、保存原始 findings、显式判定并生成可复算报告；不要使用旧的 Python multi_review.py 或三方投票脚本。
---

# /multi-review

本文件是项目级入口，只负责编排与判定，不定义评测算法。存储协议、finding 规范化与指标口径以
[tools/llm-review-bench/docs/protocol.md](../../../tools/llm-review-bench/docs/protocol.md) 为准，
聚簇与 verdict 规则以
[tools/llm-review-bench/docs/clusters.md](../../../tools/llm-review-bench/docs/clusters.md) 为准；
活契约见 [design/llm-review-benchmark.md](../../../design/llm-review-benchmark.md)。用户级同名
skill 仍存在于本机，但项目内日常 `/multi-review` 以本文件为准。

## 硬约束

- 唯一执行路径是 `tools/llm-review-bench/dist/cli.js`。MUST NOT 调用
  `~/.agents/skills/multi-review/scripts/multi_review.py`、`vote`/`tally` 或任何自建 Python 编排。
- 不改 `tools/llm-review-bench` 内实现；只通过 CLI 使用它。需要新能力时向用户报告，不要就地改工具。
- reviewer 的 severity 只是参考；主 agent 必须亲自读代码核实后再写 verdict。
- timeout / failed 的 reviewer 不是 0 findings，不得当作空结果计入分母；按 status 记录并说明。
- setup 失败、空输出、解析失败都不是“没有发现问题”；按实际错误报告并停止或重试。
- open-world run MUST NOT 伪造 recall；没有 gold 时报告 `n/a`，不得为了数字补写 gold。
- 同一 run 的自我修复是 remediation，MUST NOT 作为该 run 的独立 gold 回填。

## 1. 构建

从仓库根执行；`dist/` 不存在时先构建：

```bash
test -f tools/llm-review-bench/dist/cli.js || npm --prefix tools/llm-review-bench run build
```

需要固定 reviewer provenance 时用 `--systems <file>`；只跑部分 reviewer 用 `--agents`。
默认会自动选择 `PATH` 上可用的真实 CLI，不会使用 fixture。

## 2. 选择 case 并运行

按用户目标选一个，不要混用：

```bash
# 未提交改动（默认首选；工作区干净时改用 --commit）
node tools/llm-review-bench/dist/cli.js review --working --json

# 单个 commit；根 commit 需要显式 --base
node tools/llm-review-bench/dist/cli.js review --commit <sha> --json
node tools/llm-review-bench/dist/cli.js review --base <base> --commit <sha> --json

# 显式 fix benchmark：审 buggy = fix^，fix 只生成 candidate gold
node tools/llm-review-bench/dist/cli.js run --fix <fix-sha> --json

# 只重试失败/超时的 reviewer（不是新 case）
node tools/llm-review-bench/dist/cli.js resume <run-id> --json
```

`run --fix` 不接受 `--working`/`--commit`/`--base`；`review --working` 不能与 `--commit` 组合。
默认输出根是 `<repo>/.review-benchmark`，可用 `--output` 指定。

## 3. 读取 run 与原始 findings

从 JSON stdout 读取 `runId`、`runDir`、`systems`、`gold`、`reports`；没有 `--json` 时也会打印 run id。

- 原始 finding 在 `<runDir>/reviews/<agent>/attempt-N.json`。
- `status` 不是 `completed` 时 `findings` 为 `null`；这不是空评审。先看
  `<runDir>/logs/<agent>-attempt-N.*.log`，必要时 `resume`。
- 不要读 `derived/` 猜结论；判定输入是原始 findings 加代码，不是上一版 verdict。
- 报告 `notes` 里的 pending、stale adjudication、unclustered、incomplete reviewer 必须保留并说明。

## 4. 亲自核实并生成版本化判定

先读并遵守 [docs/clusters.md](../../../tools/llm-review-bench/docs/clusters.md)。逐条 finding 回到
`<runDir>/snapshot/`、调用方和测试核实；能写失败测试就写，不能证实就标 `uncertain`，不要为了凑
`confirmed` 降低标准。

生成三个显式 JSON（形状见 [docs/protocol.md](../../../tools/llm-review-bench/docs/protocol.md) 第 7–8 节）：

- `clusters.json`：对每个 completed reviewer 的每个 finding 恰好分区一次；漏项、重复、编造 ID 都会被拒绝。
- `verdicts.json`：每个簇 `confirmed` / `rejected` / `neutral` / `uncertain` 四选一；不写的簇是
  `pending`，不计入分母。
- `gold.json`：只用于 historical case；顶层字段包括 `status`/`confirmedBy`/`note`/`labels`/
  `excludedFixScope`（`provenance` 是工具写出的记录字段，不是输入字段）；每个 label 必须显式给
  `introducedByBuggyCommit`，confirmed gold 还要 `confirmedBy`。open-world/live run 没有可确认的
  gold label（工具只记录 open-world 状态）。

```bash
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json \
  --judge-kind agent --judge-name <name> --judge-model <model> --json

# historical case 才加 --gold；confirmed gold 需要 confirmedBy（JSON 内或 flag）
node tools/llm-review-bench/dist/cli.js apply-judgment <run-id> \
  --clusters clusters.json --verdicts verdicts.json --gold gold.json \
  --confirmed-by <name> \
  --judge-kind agent --judge-name <name> --judge-model <model> --json
```

`apply-judgment` 默认重新评测并渲染报告；只导入不评测用 `--no-rescore`。需要重新计算时：

```bash
node tools/llm-review-bench/dist/cli.js rescore <run-id> --json
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

新判定必须形成新的 `vN`；不要手改 `derived/` 或 `gold/` 下的已有版本。

## 5. 修复与报告

- 保留正常 code review 的修复职责：confirmed 的 P0/明显缺陷，按项目规则最小修复、补测试、
  跑该项目的格式与测试命令，并提交。修复不了的项在报告中列出。
- 同一 run 的自我修复只作为 remediation，不写回该 run 的 confirmed gold，也不把它当作独立
  gold；`link` 只用于独立于该 run 的后续 fix（见第 6 节）。
- 报告给用户：run id、每个 reviewer 的 status/findings、confirmed/rejected/neutral/uncertain/
  pending 计数、recall（有 gold 且可计算时）或 `n/a` 及原因、报告路径、未修复项。
- 收尾：`node tools/llm-review-bench/dist/cli.js cleanup <run-id>` 删除 worktree 与临时 agent 输出，
  保留证据。

## 6. 后续 fix 与复算

后续出现独立 fix 时，不重跑 reviewer：

```bash
node tools/llm-review-bench/dist/cli.js link --run <run-id> --fix <fix-sha> --json
# 读取 linked gold（默认 candidate）；确认后用 apply-judgment --gold 导入 confirmed gold，
# 并在 gold.json 的 note 写明独立性依据（会记录到 provenance.note）
node tools/llm-review-bench/dist/cli.js rescore <run-id> --json
node tools/llm-review-bench/dist/cli.js report <run-id> --json
```

`link` 要求 fix 从 run head 下降；它只写新的 gold/evaluation 版本，不调用模型。`rerun <run-id>`
是新 run（新 run id、同一不可变输入、重新跑 reviewer、不继承旧判定），不是 `resume`。

## 7. 结果判读

- 分母为零的 rate 是 `n/a`/`null`，不是 0。
- candidate/open-world gold 的 recall 是 `null`；不要在报告里写成 0 或百分比。
- 一个 case 只说明一个 measured system，不是模型排名；比较用 `leaderboard`，并保留
  agent/model/revision/effort provenance。
- failed/timeout 与 completed 的 reviewer 不能混为一个分母；报告分别列出。

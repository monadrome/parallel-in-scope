---
name: refactoring-review
description: 代码改动的重构审查：行为提交之后、code review（/multi-review）之前的必经步骤。脚本按 diff 决定是否需要、派几个只读 detector、各看哪个视角；主 agent 核实后以独立 refactor 提交落地行为不变的重构。用于 refactoring-review、重构审查、review 前重构、提交后整理结构。只有代码改动才运行；纯文档改动由脚本判定跳过。
---

# /refactoring-review

本文件只负责编排。规则路由、原则、视角与阈值以 [refactoring-review/AGENTS.md](../../../refactoring-review/AGENTS.md)
为准；确定性部分（门禁、规划、合并、校验）只能通过 `scripts/refactoring-review.py` 完成，不要手工替代。

## 硬约束

- detector 只读：不改文件、不跑构建、不提交、不调用 skill、不派生 agent。brief 已写明，派发时不得删改这些条款。
- 每个改动只跑一轮（原则 R7）；refactor 提交不再做 refactoring-review。
- 只落地 T0/T1；T2 一律只记录去向。分诊只能把 tier 调严，不能调松。
- refactor 提交与行为提交分开，提交信息用 `refactor:`，不夹带行为变化。
- detector 超时、失败、输出无法解析都不是“没有发现”；重跑一次，仍失败记为 blocked。
- 不修改 `tools/llm-review-bench`；移交 code review 时按 `/multi-review` 原流程使用它。

## 0. 前置

行为改动已经通过本地验证并提交，代码路径没有未提交修改（脚本会检查）。审查基准默认是 `config.json` 的
`defaultBase` 与 HEAD 的 merge-base；先 `git fetch`。

## 1. 门禁与规划

```bash
python3 scripts/refactoring-review.py plan [--base <ref>] [--reviewers <a,b,c>]
```

- `required: false`：在交付报告里写一行 reason，直接进入第 8 步。
- `required: true`：读 `<runDir>/plan.json`。`mode`、每个 detector 的 lens、文件、budget、routes、
  reviewer 都由脚本决定；`uncovered` 非空时在报告里如实列出并建议拆分改动。
- `--reviewers` 给出可用的模型或 harness 名单，脚本按 base/head 确定性轮换；不给时由主 agent 选择，
  但仍须记录每个 detector 实际用的 reviewer。

## 2. 发现（并行）

对 `plan.json` 中每个 agent，启动一个全新上下文的只读 agent，工作目录为仓库根（处于 behavior head），
提示词就是 `<runDir>/agents/<id>/brief.md` 的全文。所有 detector 并行启动。

- 本 harness 的子 agent、或其他模型的 CLI 都可以；CLI 用只读模式，参数参考
  `tools/llm-review-bench/docs/adapters.md`（例如 codex 的 `--sandbox read-only`）。
- 把 detector 最终返回的 JSON 原样保存为 `<runDir>/agents/<id>/findings.json`，不要编辑内容。

## 3. 合并

```bash
python3 scripts/refactoring-review.py merge <runDir>
```

退出码 1 表示有 detector missing/invalid：按硬约束重跑一次后再合并；仍失败则该 detector 记为 blocked
继续。`merged.json` 给出去重后的发现（tier 取最严）、`flags`（`unknown-rule`、`outside-agent-files`）
与 `conflicts`（同一符号不同手法）。

## 4. 分诊（主 agent 亲自核实）

对每条合并发现，按顺序：

1. 回到工作树读该符号，确认引用的代码存在且描述准确；带 flag 的发现先核实 flag。
2. 对照 R1（行为不变）与 R10 复核 tier，只能调严。
3. 在 `design/decision-log.md`、`docs/zh/design/idea-graveyard.md`、`adr/` 查同一方向是否已被否决；
   否决过即 rejected 并引用。
4. `out-of-diff` 的发现查 `BACKLOG.md` 是否已登记；只记录，不修。
5. 只对拟接受的 T0/T1 读其 `guardingTests`，确认覆盖了要保持的行为；没有覆盖则降为 T2 或先补测试。
6. `conflicts` 不投票：按 R9 优先删除与内联，并记下两边理由。
7. 接受数超过 `limits.maxAcceptedFindings` 时停止接受，其余 deferred。

## 5. 落地

逐项实现已接受的 T0/T1，放进一个或少量 `refactor:` 提交；提交信息列出合并 id（`R3`、`R5`）并写明
behavior-preserving 及守护测试。然后：

- `mvn spotless:apply`，针对性测试，再全量 `mvn test`；
- T1 且涉及并发路由的项：`python3 scripts/check-race-claims.py`；
- 改了生产代码：按 `verification/pit.md` 判断 PIT 适用性与范围。

## 6. 校验

```bash
python3 scripts/refactoring-review.py verify <runDir>
```

- `growthExceeded`：撤回超出部分，转为独立任务。
- `oracleEdits`：逐条确认只是跟随改名的机械编辑；否则撤销对应重构并记为 reverted、T2（R8）。
  全部确认为改名时，在报告里写明“reviewed: rename-only”。
- `nonRefactorCommits`：把行为改动移出 refactor 区间。

## 7. 报告

按 [refactoring-review/report-template.md](../../../refactoring-review/report-template.md) 写
`<runDir>/report.md`：每个 detector 的状态、每条发现的处置与理由（包括驳回）、每项检查的状态、
可机械化规则对应的拟议检查、T2 的去向（`BACKLOG.md`、`design/proposal/` 或未决项）。
运行目录保留为审计记录。

## 8. 移交 code review

按 `/multi-review` 审查 base..HEAD（包含 refactor 提交），例如
`node tools/llm-review-bench/dist/cli.js review --base <base> --commit HEAD --json`。判定阶段：

- 报告里已定论的发现作为基线，reviewer 重复提出时沿用原处置；
- 额外核查攻击面：每个 refactor 提交是否真的保持行为，以及每个已落地的 T1 项。

---
name: complexity-review-eval
description: 项目内的复杂度灰度评测与重构注意力路由。凡是代码改动涉及方法变长、分支/嵌套增长、结构性重构、开发者只能做浅层 review、担心仅靠 agent review 不足，或提到 P0036、复杂度评测、灰度评测、重构候选时，都主动使用本 skill。它生成可审计的 complexity metrics，并把候选接入 refactoring-review 的 S lens；它不把复杂度当质量分，也不替代测试、契约检查或独立 code review。
---

# Complexity review evaluation

把复杂度机制当作评测体系中的灰度信号：它负责把有限的 reviewer 注意力导向本次 change
中值得阅读的方法，确定性构建和测试负责质量底线，语义 review 负责决定是否真的需要重构。

## 何时主动调用

主动调用本 skill 的典型情况：

- 代码 change 新增或显著修改了长方法、深嵌套、分支密集的方法；
- 开发者明确表示只做了简单 review，想依靠 agent review 补足检查；
- 要运行 `/refactoring-review`，且 change 包含核心生产 Java；
- 讨论 P0036、复杂度候选、重构热点、评测灰度或 review 注意力分配；
- change 触及并发、取消、deadline、executor、队列或公共契约，需要确认是否应升级 review 强度。

纯文档、纯配置、没有核心生产 Java 变化的 change 可以调用本 skill 做记录，但应预期候选为零。

## 执行步骤

1. 先确认行为 change 已提交。正式评测比较提交的 base/head；未提交的生产代码不会进入 metrics。
2. 在仓库根目录运行：

   ```bash
   python3 scripts/refactoring-review.py plan \
     --base origin/dev/v0.5.0 \
     --head HEAD \
     --output-dir .agents/reviews/<topic>-complexity \
     --force
   ```

   `--base` 可省略，因为项目配置默认使用 `origin/dev/v0.5.0`。如果只是调试未提交状态，
   可以使用 `--allow-dirty`，但必须说明结果不包含未提交代码，不能把它当正式评测证据。

3. 读取以下产物：

   - `.agents/reviews/<topic>-complexity/metrics.json`
   - `.agents/reviews/<topic>-complexity/plan.json`
   - 结构 lens 的 `agents/S/brief.md`（或对应 S shard 的 brief）

4. 确认 `metrics.json` 包含：

   ```text
   stage = gray-evaluation
   purpose = attention-routing evidence; not a quality score or CI gate
   ```

   如果 stage 缺失、采集失败或输出无法解析，报告为 `blocked/inconclusive`，不能当作“没有复杂度候选”。

5. 如果需要运行 agent detector，继续执行现有 refactoring-review 流程：

   ```bash
   python3 scripts/refactoring-review.py detect \
     .agents/reviews/<topic>-complexity \
     --reviewer codex

   python3 scripts/refactoring-review.py merge \
     .agents/reviews/<topic>-complexity
   ```

   detector 输出无法解析、超时或没有读到 changed files 时，状态是 blocked，不是 clean。

## 如何解释候选

复杂度候选只是评审提示。对每个候选先回答：

- 复杂度由哪些职责、分支或协议步骤组成；
- 是否存在可独立变化的职责边界；
- 具体的行为保持型重构 move 是什么；
- 哪些测试、顺序、取消、deadline、interrupt、executor 绑定和资源生命周期必须保持。

以下情况可以拒绝候选：算法本身需要复杂控制流、穷举分派是完整性要求、代码属于并发协议，
或者拆分只会增加参数传递和隐藏状态。不要为了降低 CC、代码行数或嵌套深度而改动行为。

## 质量底线

本 skill 不能替代这些检查；它们仍按任务路由执行：

- 编译、格式和完整测试；
- 公共 API surface 检查；
- race claim、并发专项测试和适用的 PIT；
- 独立 `/multi-review`；
- 文档和迁移说明。

复杂度候选为零、detector 返回空 findings 或指标较低，都不等于 change 通过。只有适用的
确定性门禁和独立评审证据都有效时，才可以报告交付。

## 灰度阶段边界

- 当前 stage 固定为 `gray-evaluation`；
- 当前范围是核心生产 Java，排除 `queue`；
- 当前实现是无依赖的保守扫描器，属于评测输入，不是质量分数；
- 不新增 CI 失败条件，不自动修复，不要求复杂度下降；
- 后续是否升级为 AST 采集器或增加风险信号，要根据真实 change 的有效发现率和误报成本决定。

## 结果报告

向用户报告时，至少说明：

1. 评测是否运行以及 stage；
2. 候选数和是否注入 S lens；
3. 确定性门禁与独立 review 的状态；
4. 复杂度机制仍处于灰度验证阶段，不是 CI 质量门禁；
5. 任何 blocked/inconclusive 的原因。

# P0036：复杂度评审信号（灰度验证）

## 状态

**灰度验证中。** 本提案属于评测体系，不改变构建通过标准，也不把复杂度变成质量分数。

## 目标

在开发者只能做浅层 review 时，为现有 `refactoring-review` 提供可复核的注意力路由证据，
帮助 detector 优先阅读本次 change 中复杂度明显增长的方法。语义评审仍决定是否存在重构机会。

## 当前实现

`python3 scripts/refactoring-review.py plan` 会在核心生产 Java（排除 `queue`）上生成
`evaluation.complexity` 和 run 目录中的评测证据，并把候选注入结构（S）lens。证据包含
圈复杂度及分项、代码行数、最大嵌套和 base/head 差值。

每次 run 明确标记：

```text
stage = gray-evaluation
purpose = attention-routing evidence; not a quality score or CI gate
```

采集器当前是无依赖、保守的源码扫描器；它的结果只用于评测和人工判断。解析口径或误报
达到升级条件前，不扩大范围、不增加 CI 门禁，也不自动修改代码。

## 必须保持的边界

- 候选只来自单个 change 的 base/head，不扫描历史热点作为当前 finding。
- `queue` 保持排除。
- 复杂度候选不是 finding，也不要求重构。
- detector 可以拒绝候选；算法、穷举分派和并发协议的内在复杂度可以保留。
- 采集失败不能伪装成零候选。
- 现有构建、测试、race claim、PIT 和独立评审门禁保持不变。

## 灰度验收

先观察若干真实 change 中候选的接受、拒绝、延期和误报比例。只有当候选稳定带来有效的
重构发现，才讨论 AST 采集器、额外风险信号或 delivery ledger 聚合；复杂度下降本身不是
成功标准。

## 蒸馏摘要

复杂度指标只负责定位评审注意力，语义评审负责决定是否重构；评测结果必须标记为
`gray-evaluation`，不得直接成为质量分、CI 门禁或自动修改依据。候选只来自单个 change，
并排除 `queue`；采集失败必须显式报告，不能当作零候选。后续是否扩大到 AST、额外风险信号
或 delivery ledger，取决于灰度期间的有效发现率和误报成本。

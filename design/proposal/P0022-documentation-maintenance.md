# 文档语义与历史记录维护

## 蒸馏摘要

- 核心出发点：用户示例、现行契约与历史记录各自承担不同责任，修复错误教学与漂移的事实源。
- 不变量与拟议 MUST：示例 MUST 编译并遵守上下文恢复及 P1–P4；MUST 区分中断投递与 IO 停止、
  Future 终态与 body 退出。ADR 的决定 MUST NOT 被改写，变更使用后继 ADR 与 Superseded 链接；
  过时的非决策内容按 adr/AGENTS.md 蒸馏。
- 被否决方向及理由：不恢复已删除 listener/运行句柄来迎合旧文档，不恢复 throwing Function，
  不按符号零命中宣布整篇 ADR 失效，不将本地路径门禁通过称为示例或语义验证通过。

## 输入与复核要求

承接 documentation-audit-2026-10-08 的 D1–D10、doc-value-audit-2026-10-09、
decision-brief-2026-10-09 的文档与流程项，以及四代核心审计的文档维护建议。
这些是历史审计输入；实施前逐项对照当前树，不照抄行号、计数或“全部仍成立”。
`6588e97` 已修 graveyard 死链接与两参 map 示例，不能再作为待修项。
分析稿退出与提案可见性由 [生命周期契约](../proposal-lifecycle.md) 承接。

## 用户示例的具体修复目标

1. demo、静态 HTML 的其余旧公开 submit 示例按当前 API 复核。
   重定向存根与 mkdocs 导航先核对再改，不能因看似重复而删承重 URL。

P0011 的 MDC/TTL、checked Function、缺少成功返回、中断处理和 IO 超时说明已由
[修复记录](../confirmed-proposal-defects.md) 承接。graveyard 的预算/异构边界、报告冻结语义与
静态演示的 fail-fast 标签也已纠正。余项仍需当前示例验证；不改变库签名。

## 活契约、历史与索引维护

- task-group-cancellation 的已删 listener/直接 CANCELLED 观察与共享归因描述；
  lifecycle 的 resolve 签名枚举：逐项按机制统一，取消与观测 MUST 保留单一事实源。
- task-group-api-and-options 中 failure 只属于业务/提交失败的过强表述、lifecycle 中把直接
  body 确认与观测发布混为一谈的文字需复核；nested Function 读取 batch 的 checked 异常
  与 group 的 unchecked 包装用具体消费代码解释，不为统一表面添加 throwing callback 家族。
- PR #56 重落分支 `c4ddfc7` 的 O-13/O-14 还需明确教学：批次 report().firstException()
  只取 USER_FAILURE/SUBMISSION_FAILURE，纯超时可为 null，不代表所有任务成功；取消细节
  从逐元素 ImmediateResult 读取。FAIL_FAST 可同时覆盖已运行后取消与未提交的元素，单靠
  outcome 不能推断执行历史，应结合可用最终观测。维持现有归因，不为区分这两者增加开关。
  若要让 firstException 聚合取消，须独立评估 valuesOrThrow 的失败优先级与迁移，不能借
  文档修订改变契约；历史 O-15 的跨版本 API diff 已实现，不再立项。
- CHANGELOG 的 0.3 开发项错放 0.2 节须逐项核对引入提交并去重归位；已发布措辞的拼写保留。
  提出 changelog 与行为变更同步约定时补清晰触发条件，不据审计旧列表机械追加条目。
- BACKLOG 重验开放条目、压缩已关闭条目并统一落点；不要用旧 submitter/purge/签名、旧数字
  排当前优先级。根 AGENTS 的 migration-v0.3 路由另补，保留旧版本迁移资料。
- zh philosophy 的 int-code 叙述、frontmatter/重复 graveyard 与 docs 双语长度差异按内容价值
  处理；user-guide 中已删 executor tags 与包私有 SubmissionException 的措辞应复核。
- draining-queue 对不存在 closable 契约、group-one-shot 对 09-27 API 稿的字面指针应改为
  实际 ADR 或已决结论；runner-interrupt-explainer.html 是否删除或保留可达教学用途需明确选择。

## 验证与工作流维护

优先现有 demo/consumer 的可编译片段共源，逐步补对文档有意义的样例验证，而非另列全库 API。
门禁只证明各自覆盖的结构；反引号路径、HTML 路由或公网链接检查先证明误报边界和净收益，
不因一次遗漏扩成通用语义解析器。受阻检查分别报告，不补造通过结果。

核心审计中的 agent 建议按原任务边界承接：高风险不变量保持常驻；环境修复/长验证细节可按需
路由；change review、source-only 与 contract review 输入分开；写 agent 用独立工作树，
reviewer 只读，Maven 在单 checkout 串行。外部 consumer 与 NullAway 消费端验证分别报告。
本机 skill 的旧索引、Node pre-commit 默认或 tdd 前提不在本库实现范围，修 skill 时独立复核。
不因整理文档修改当前低层协议确认规则或建立额外审批机制。

测试类名与文件名已对齐并验证定向选择；不把本次局部修复当作剩余示例和语义维护通过。

## 其他会话的分工

2026-10-09 的 OSS 互盲审计中，公开入口/原生 JDK 选型/贡献职责由
[P0017](P0017-oss-adoption-path.md) 承接，构件身份与发布消费由
[P0023](P0023-artifact-verification.md) 承接；本提案保留文档内部语义、历史记录与索引维护。
OTel 的完整 Context 示例已按当前包装机制校正，剩余真实 SDK/agent 验证与接入边界归
[P0013](P0013-opentelemetry-verification.md)。不重新复制各自清单，不把已有 API diff、dev
贡献基线、自动 snapshot 或已补 TaskGroupResult Javadoc 列为待新建内容。
结果匹配链与单任务获取的历史剩余方向由 [P0019](P0019-result-exhaustive-matching.md)
承接，不并入文档修复，也不把修复 failure 中断敏感当作新增 API 的理由。

本提案承接 P0011 尚未实施的方向；其具体缺陷修复已并入活契约。

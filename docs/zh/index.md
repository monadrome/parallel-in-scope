# parallel-in-scope 文档

> 此目录跟随 `0.4.0-SNAPSHOT` 开发线。稳定版 `0.3.0` 请使用
> [线上稳定文档](https://monadrome.github.io/parallel-in-scope/zh/)。

这里汇总使用指南、API 参考、设计文档和示例。

## 使用指南

| 文档 | 内容 |
|---|---|
| [5 分钟快速上手](quickstart.md) | 在新 Maven 项目中完成首次批量调用 |
| [完整使用指南](user-guide.md) | 配置、公共 API、执行语义和进阶能力 |
| [v0.4 迁移指南](migration-v0.4.md) | 从 `0.3.x` 迁移：类型化 `TaskKey`、聚合结果读取与运行时诊断 |
| [v0.3 迁移指南](migration-v0.3.md) | 从 `0.2.x` 迁移：任务组一次性链式 API 与运行期契约变化 |
| [v0.2 迁移指南](migration-v0.2.md) | 从 `0.1.x` 迁移到 `0.2` API |
| [5 分钟快速上手](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/demo/docs/zh-CN/articles/QUICK-START-5-minutes.md) | 用最小示例完成首次调用 |
| [批量调用最佳实践](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/demo/docs/zh-CN/articles/BATCH-best-practices.md) | HTTP、数据库和混合 IO 场景 |
| [Demo 文档索引](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/demo/docs/zh-CN/README.md) | 示例：问题复现、解决方案和可执行测试 |

## API 与契约参考

| 文档 | 内容 |
|---|---|
| [协作式取消](reference/cooperative-cancellation.md) | checkpoint、异常与取消传播 |
| [Nullability Annotations](reference/nullability-annotations.md) | 空值注解规则和依赖约定 |

## 实现原理与设计决策

| 文档 | 内容 |
|---|---|
| [并发库的减法哲学](design/philosophy.md) | 核心取舍与边界 |
| [Idea Graveyard](design/idea-graveyard.md) | 明确不提供的能力及替代方案 |
| [架构与数据链路](visuals/parallel-in-scope-architecture.html) | 执行、取消、调度和检测链路 |
| [使用入口](visuals/parallel-in-scope-user-guide.html) | 主要使用路径与快速入口 |

## 测试与案例

| 文档 | 内容 |
|---|---|
| [测试使用本库的代码](testing/testing-your-code.md) | 确定性超时、清理、预算与结果测试 |
| [TaskBatchResult 测试设计](testing/task-batch-result-report-test-design.md) | 稳定契约与并发断言 |
| [AI 并发审查误报复盘](case-studies/ai-concurrency-review-false-positive.md) | 一次误报审查与可执行的验证方法 |

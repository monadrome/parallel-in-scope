# 开源采纳、选型与贡献路径

## 蒸馏摘要

- 核心出发点：用户应能从公开入口选对 API 世代、判断是否适合自己的 JDK/执行器，并运行
  可退出的例子；人类贡献者应知道目标分支与自己承担的验证。
- 不变量与拟议 MUST：首用代码 MUST 对照真正可下载的对应构件；原生 JDK 对比 MUST 区分
  虚拟线程、结构化范围与本库有界清理。MUST 保留 executor 借用所有权与现有外部贡献规则。
- 被否决方向及理由：不把本地 install/陈旧 main ref 当对外证据，不因星数推断没有用户，
  不为营销扩大核心能力或重开 benchmark/queue/注释门禁；不强制 1.0 或新社区平台。

## 当前基线与未决目标

README 直接推荐 0.3 快照、同时标出 0.2 稳定版；本地 Quick Start 已关闭 runtime 和借用池。
CONTRIBUTING 已要求 JDK 25 构建、PR 投当前 dev 线；API diff 已由 `81d8724` 实施。
这些不能照旧审计再列为缺失。源码、snapshot、稳定版与在线站点是否一致仍要用当前公开
入口复核，不能复用 2026-10-09 的 jar、404、PR 数量或 CI 结论。

剩余可操作方向：

- README 的短选型段与可运行 before/after：Java 8+、已有 Guava/TTL、固定池和有限 fan-out
  的适用理由；对原生 StructuredTaskScope 与虚拟线程分别说明选择条件、目标 JDK/preview
  状态和语义差异。材料可复用 demo 的 Java 8 成本文章，事实按实施时官方版本复核。
- stable/next 的阅读与下载入口对齐：不要让使用者跟 stable 坐标复制 next API。每个示例
  标明适用版本，用真正构件编译、运行、退出；发布链的证据职责见 [P0018](P0018-artifact-verification.md)。
- 人类贡献路径：保留已有 issue-first 及 dev base，明确作者负责复现/测试/迁移，维护者
  负责独立审查/PIT；外部作者不需要安装维护者的 cmux/AI 工具或自动 push。
- 发布站点与 demo 的实际入口：先核对当前部署和 URL，再修导航/徽章；英文 demo 的
  中文内容说明与检查已在 `7337775` 处理，不把它当未解决问题重复清扫。
- About/homepage/topics、私密漏洞报告与支持版本、行为准则：按单维护者真实渠道和承诺
  完善，不臆造响应 SLA，也不把配置文件缺失写成已发现安全漏洞。外部元数据变更另行授权。
- 公开 issue/PR/历史分支的方向维护：按当前状态链接到落实或取代结论，不按旧积压数量
  批量关闭/删除；分析资料原文不是需要永久留在主干的档案。

## 用户代码与改进后的同一入口

当前最稳妥的用户写法是选择与文档同代的构件，并遵守显式资源所有权：

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), pool).build();
try {
    TaskBatchResult<Value> result = runtime.par(ParId.of("io"))
            .map(ids, this::load, BatchOptions.timeout("load", timeout).parallelism(4));
    consume(result);
} finally {
    runtime.close();
    try {
        boolean drained = runtime.awaitQuiescence(grace);
        handleDrainResult(drained);
    } finally {
        pool.shutdown();
    }
}
```

例子的应用入口允许抛 InterruptedException；线程所有者选择政策。`drained=false` 不表示
共享资源已可释放，应用必须处理该事实。改后同一代码按选中的坐标可编译运行，入口说明
让用户无需猜测 API 世代或归档设计。没有库 API 迁移或更强退出保证。

## 取舍与验收

先证明采纳入口可用，再按真实反馈决定案例传播节奏；这不是用审计共识替维护者拍营销
优先级。benchmark 暂缓保持，具体延迟/内存门槛出现后依原有重开条件评估；不为可信度
先创造未被要求的吞吐承诺。建议过 `first-principles` 的点是减少选错版本、忘记退出与贡献
职责误解，不新增运行时概念或管道。

验收以陌生使用者的公开路线为准：stable/next 编译与真实 Java 8 运行，进程退出，现代
JDK 用户能判断适用性，人类贡献者能按 CONTRIBUTING 完成小修复。README、站点及实际
下载分别核对；本地链接/语言检查不证明线上 URL、公开构件或文案的语义。

## 来源

Claude `67244035` 与 Codex `01a120ae` 的 2026-10-09 同题 OSS 审计和交叉比较。
保留“公开构件优先于本地绿”的纠正，撤回陈旧本地 main 与同名本地 snapshot 的证据。
文档内部语义/CHANGELOG/BACKLOG 清理仍归 [P0011](P0011-documentation-maintenance.md)，
不再维护第二份细节清单。

# 发布构件、消费者与来源验证

## 蒸馏摘要

- 核心出发点：本地构建、CI 消费、公开下载可能解析到不同的同名 snapshot，验证对象的
  身份必须可追溯；发布成功不能仅由发布意图推断。
- 不变量与拟议 MUST：消费者 MUST 使用它声称验证的构件，MUST 区分本地构建与 Central
  下载；发布摘要 MUST 依据实际结果。版本、源码、示例、hash 与验证范围 MUST 对齐。
- 被否决方向及理由：不重复建设已经存在的 snapshot 发布或 API diff，不在确认根因前把
  缓存覆盖猜测写成定论，不用安全徽章/文件存在代替验证，不自动执行 deploy 或推 main。

## 当前流程与缺口

快照已由 `.github/workflows/snapshot.yml` 在 dev push/manual 触发并按 UTC 日限流，
此前“没有自动快照”的建议已经落实。`release.yml` 也已修发布凭据的 server-id 作用域；
API diff 和当前外部消费者存在，不能再作为待新建机制。

当前源码仍有这些可验证的缺口：

- CI 的 Java 8 作业先用 JDK 25 install，再切 JDK 8 时第二次恢复 Maven cache，没有 jar
  身份校验。历史“缓存覆盖”的机制解释未独立坐实；风险点可从当前配置确认，是否仍发生
  编译失败要重新读取实际 run，不能沿用旧失败日志。
- 发布路径构建 library/demo，但没有明确复用全部文档/格式/真实 Java 8 消费结果，也未
  使用发布后隔离解析证明 Central 服务的是本次构件。
- `scripts/verify-maven-central.sh` 已用空临时仓库排除本地副本，但消费者源码跟随当前
  0.3 API。传旧稳定坐标只选择一个测试方法仍会编译整类，不能据此验证旧版本。
- POM 的 SCM tag 与构件源码标识、归档时间和 Java 9+ automatic module 名需按真实下游
  需求评估；不把静态配置不足说成已经证明不安全或不可复现。

## 最佳现有验证与改进后

今天可以在本地 install 后用显式版本跑消费者；这只证明本地构件。公开构件检查应使用
已经存在的隔离解析脚本，并匹配对应 API 世代。用户仍写同样的 Maven 坐标和库代码：

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.3.0-SNAPSHOT</version>
</dependency>
```

改后下载路径、README/指南、消费者与该坐标的真实构件对应，能区分 local build、缓存、
HTTP artifact 与源码 revision；不让同名 install 隐藏公开 API 不匹配。
签名不变。版本化消费者/文档与支持轨道需要维护者选择；不默认把稳定与 next 代码混编。

## 建议实施顺序与验收

1. 先锁 install/consumer 的 jar 身份与缓存恢复顺序：输出并核对 SHA-256；若选择临时本地
   仓库，其余依赖与构件来源也分清。实验分别构造同版本旧缓存与当前 jar，必须能检出错用。
2. 定义 release/snapshot 复用的验证与发布后 smoke：真实 Java 8、格式、文档门禁各自
   有独立结果。Central 延迟/网络阻塞按阻塞报告，不把启动验证当通过，也不借此触发发布。
3. stable/next 消费源码与对应坐标分别可编译；公开示例与 consumer 共源的收益先做窄样例
   验证，避免新增一份全库 API 文档缓存。支持策略与用户入口归 [P0017](P0017-oss-adoption-path.md)。
4. 发布后下载核验应记录实际状态、版本、源码 revision；成功 deploy 的摘要不等于已下载
   同一源码构件的证据。日期门禁不是“已经服务同一源码树”的证明。
5. 对来源及供应链，优先固定 Actions revision、真实 SCM tag/源码 commit/hash 和明确升级
   策略；复现构建需实际做两次洁净重建比较。Automatic-Module-Name 是独立的兼容性选择，
   先有 JPMS 下游用例再决定名字。SBOM/attestation 按实际需求，不默认加整套平台。

按第一性原理，这些动作减少选错构件和遗漏验证，参数化现有 consumer/发布流程，不影响
取消、context 或 outcome，不新增库概念。验收不得将一个绿 consumer 泛化成所有公开版本
兼容，也不得将文件存在、签名文件下载或 CodeQL job 存在写成安全认证。

## 来源

2026-10-09 Claude `67244035` 与 Codex `01a120ae` 的 OSS 比较，随后用户选择不推 main、
在 dev 线上有未发布改动时自动发快照。已执行的发布改动以 `5935059`、`7fa42f5`、
`8b5fc71`、`8d7ff69` 及当前 workflow 为准；保留未落实的构件身份/验证建议，不回滚已选
trigger/日限流策略。本提案不修改 workflow、pom、公开坐标或外部应用。

本提案承接 P0018 未实施的构件身份与消费验证方向。误报 Published 的摘要缺陷已由
[修复记录](../confirmed-proposal-defects.md) 承接，保持现行 trigger 与日限流策略。

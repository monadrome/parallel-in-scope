# OpenTelemetry 接入边界与实证

## 蒸馏摘要

- 核心出发点：把任务体自助包装的 OTel 用法变成可验证的接入路径，区分上下文传播、任务 span
  创建和指标消费，避免错误父级、上下文残留与误判 agent 覆盖范围。
- 不变量与拟议 MUST：核心 MUST 继续复用现有 TTL/准备/取消管道；Scope MUST 在打开它的线程、
  body 动态范围内关闭，任务 span MUST 在该 body 退出时结束。手工传播 MUST 保留完整 Context，
  嵌套执行 MUST 捕获本次调用的当前父级。兼容声明 MUST 有固定版本的真实 SDK/agent 验证。
- 被否决方向及理由：本轮不增加核心运行时依赖、自动建 span 或装饰器 SPI；已有 body 包装能
  表达当前用法。Executor advice 的 Context 传播不等于建 span，也不自动等于 TTL 集合重复捕获；
  不宣称任意 executor 挂 agent 即可用，不因 queue 的 ADR 0006 推断 OTel 可选模块永久禁止。

## 当前实现与剩余目标

入口是同步 `Par.map`、`mapAndCombine` 和组链 `runAll`；没有公开运行中的 Task/TaskFuture
句柄或提交前钩子。`TaskSubmissions.prepare` 在准入线程捕获登记的 TTL，`ScopedCallable`
包住实际 body；普通 OTel ContextStorage 不因此自动加入传播集合。当前手工配方见
[用户指南](../../docs/en/user-guide.md#opentelemetry) 与
[包装契约](../extension-and-wrapping.md#53-追踪)。

剩余目标是验证并精确声明接入边界，不能把历史源码推演标成已经通过的兼容性测试：

| 路径 | 实际 handoff 的发起者 | 验证重点 |
|---|---|---|
| 批次初始窗口、组成员、包内单任务 | 准入调用线程 | 捕获的是这次逻辑调用的 Context |
| 事件补窗 | 完成监听器释放槽位后取得 drain 的线程；不一定是 worker | agent 在该线程与任务对象上捕获、回放什么 |
| 批次或组终端 | 成功门控/成员收敛触发提交的线程；inline 或已完成 future 的监听器也可能在调用线程执行 | 汇总父级不能意外变为最后一个输入的 span |

Guava 的 direct listener 在触发完成/取消的线程上执行；非重入 drain 会合并 inline 完成事件。
因此“补窗/终端必定由最后一个 worker 提交”不成立。当前固定 fail-fast 会停止剩余提交，
历史“独立取消必定补出错误父级”推论也必须重验，不能当已经确认的公开缺陷。
公开组链不能声明空组的 combine，不为这条不存在的用户路径增加测试或教程。

## 用户代码与验证后的同一写法

今天可以在调用线程显式捕获 Context，并在用户 body 内恢复完整值：

```java
Context parent = Context.current();
TaskBatchResult<String> result = par.map(ids, id -> {
    Span span = tracer.spanBuilder("load").setParent(parent).startSpan();
    try (Scope ignored = parent.with(span).makeCurrent()) {
        return load(id);
    } finally {
        span.end();
    }
}, BatchOptions.timeout("load", timeout));
```

落地后该段代码不变，由真实 SDK 测试与发布的用法说明支持。`parent.with(span)` 同时携带
baggage/自定义 Context key；只用 `setParent` 设置 span 父级不会把这些值设为当前上下文。
每次调用重新捕获，内层调用在外层 body 的 Scope 内捕获，MUST NOT 永久复用请求根的 TTL
快照盖掉新的父级。消除的是示例被误当普遍传播保证、父级扁平化和 Scope/end 责任混淆；
没有签名迁移。修正手工示例不代表 SDK 或 agent 接入实证已经完成。

## 方案选择与边界

按 [第一性原理](../first-principles.md) 六问，文档与验证消除接入遗漏，复用已有 body 包装，
保留结构化取消/预算及既有 outcome，不建立隐式传播管道或新执行形状。
任务 span 的命名、采样和导出归应用；被取消或跳过而未进入 body 的输入不产生该 body 的 span。
Future 已取消或同步出口已返回，都不证明忽略中断的 body 已退出；不能据此提前结束其 span。

| 候选 | 取舍 |
|---|---|
| 只修示例 | 纠正已有教学错误，但不足以宣布 agent 兼容 |
| 文档 + 真实 SDK 测试 + agent 消费端 | 建议；测试依赖与版本经项目权限流程确认后再加，核心运行时不增加依赖 |
| 核心直接依赖 OTel 或自动生成任务 span | 暂不采用；没有证明所有用户都需要该遥测成本及 API 责任 |
| 可复制 body 包装工具、调用方平台适配 | 可用现有机制表达；需证明捕获时点、嵌套刷新、异常和资源恢复 |
| 可选 OTel artifact/装饰器 SPI | 条件候选；按包装契约的真实需求与平台层不足条件重新评估，不能把评审共识当需求证据 |
| ContextStorage 的 TTL wrapper | 待实验的替代方案；涉及全局安装顺序、还原语义和 agent 类加载器边界，不作为默认配方 |

Agent advice 与 `Context.taskWrapping(executor)` 是不同机制。后者返回包装对象、按 execute
现场捕获；库只解包已识别的 TTL wrapper，未知 wrapper 会降低内省与策略守卫的覆盖。
MUST NOT 将 taskWrapping 注册方式作为默认教程；也不把“agent 不替换 executor”扩张为
“任务 Runnable 在任何版本、配置下都不被包装”。两种身份都需实证。
OTel 的独立 ThreadLocal 不自动违反 TTL 集合的 I3；同时桥接同一份 Context 时再分析重叠。

## 验收与不承诺的事实

- 无 agent 的真实 SDK：验证完整 Context、父 span 与 baggage，初始/补窗、组成员和两类终端，
  worker 复用、两个请求、嵌套、direct/CallerRuns、异常及取消的精确恢复。
- 固定 agent 版本和配置的独立 JVM：同样检查父级，而非仅断言 Context 非空；覆盖支持的
  标准池、Guava 适配与自定义 executor，并检查禁用相关埋点的对照。先启动 worker 与后启动
  worker 都测，防止 Thread 创建处传播掩盖任务传播缺口。
- 实际注册对象、排队 Runnable、取消/窗口上界与 L8：agent 下丢弃策略仍拒绝；手工 wrapper
  的覆盖限制另列。库的 scheduler/timeout action 路径不凭 agent 外围推演宣布兼容。
- Exporter 只统计本配方创建的任务 span，区分 agent 自动 HTTP/JDBC span；每个实际进入的
  body 恰好一个任务 span，包含成功、失败、未退出与未进入的对照。
- 指标优先消费冻结 TaskCompletion/运行时 snapshot：缺失最终观测另记，Duration 单位转换
  明确。MUST NOT 默认用 unitId/requestId/taskIndex 等逐运行值做 metric label。
- 文档区分传播与建 span、Scope.close 与 Span.end；不复制泛 OTel 教程或锁死未经复核的
  3.0/语义约定版本预测。所有 SDK/agent 检查的结果分别报告，未跑就是未验证。

## 来源与复核

2026-10-09/10 的 Claude 会话 `a32c8992`，Kimi 独立推导 `f8fdc80d` 与对抗评审 `1e75d300`。
会话曾读取上游 executor/Runnable advice、VirtualField 与 ContextStorage，但没有实际带
agent 运行本库。官方源码入口是
[executors instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/executors)
与 [Context API](https://github.com/open-telemetry/opentelemetry-java/tree/main/context)；实施时固定
revision/版本重验，不将历史 main 链接当当前兼容证明。当前调用点已按 `fb6af08` 重新核对。

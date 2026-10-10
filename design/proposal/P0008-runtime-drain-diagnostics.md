# 未退出运行的身份诊断

## 蒸馏摘要

- 核心出发点：停机后知道有运行未退出，却无法定位具体哪次运行。
- 不变量与拟议 MUST：摘要 MUST 有界、按需且只持纯数据；MUST 标明总数与截断，
  MUST NOT 持业务值、异常、Callable 或完整跟踪树。诊断 MUST NOT 替代 awaitQuiescence。
- 被否决方向及理由：不添加采样线程、listener SPI 或长期钉住完成任务的索引；
  不把 unexitedBodySignals 的运行单位误说成 body 个数。

## 目标与论证

当前最佳停机代码是 `runtime.close()` 后在 awaitQuiescence 超时时记录 `runtime.snapshot()`。
已有 `unexitedBodySignals` 的单位是运行，不是 body；一个百项批次也只贡献一个信号。

可评估按需取有界的未退出运行摘要，草图为 `runtime.unexitedRuns(maxEntries)`：包含运行
身份、名称、年龄和未确认计数，说明总数与截断。不持业务值/异常/Callable，不引入采样线程
或 listener SPI，仍是非线性化诊断。消除“知道有残留却无法定位”的问题，无旧调用迁移。
索引成本与引用生命周期需要测量，不能为诊断长期钉住完成任务。

新增查询为可选能力，无旧调用迁移。索引开销与引用生命周期需要测量，接口草图尚未验证。

当前最好代码：

```java
log.warning("not drained; " + runtime.snapshot());
```

改后同一停机分支的草图：

```java
log.warning("not drained; " + runtime.unexitedRuns(maxEntries));
```

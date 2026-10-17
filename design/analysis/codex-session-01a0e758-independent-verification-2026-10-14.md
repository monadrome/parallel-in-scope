# Codex session 01a0e758 当前待修复问题报告

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未处理。§1 的节点分配仍在 producer monitor 内（`put`、限时 `offer`）；§2 的 BACKLOG P-P5 仍引用已删除的 `HeuristicPurger`。其余三处队列项未逐项核对。

> 核验日期：2026-10-14
> 当前基线：`84cc49a54a2c1fa2eb88a7f01279d70cbc95cb72`（`dev/v0.3.0`）
> 性质：临时分析报告，不是活契约或已批准提案

## 结论

当前未发现该 session 所述但尚未修复的正确性缺陷。仍需处理的内容集中在队列性能候选和 Backlog 事实漂移；已修复项、明确保留的契约、API 偏好及未批准的新能力提案不列入本报告。

## 1. 队列实现仍有五处静态性能问题

级别：P3。证据强度：读码确认；尚无 benchmark、allocation profile 或真实下游热点数据。

| 位置 | 当前问题 | 修复要求 |
|---|---|---|
| `DrainingBlockingQueue.put`、timed `offer`、`add` | 在 producer monitor 内分配 `Node`，增加临界区停留时间和锁内 GC 压力；普通非阻塞 `offer` 已在 monitor 外分配 | 先测量争用和分配影响；若进入热点，将节点分配移到 monitor 外，并在锁内重新检查 lifecycle/capacity，不得改变关闭、超时和拒绝语义 |
| `DrainingBlockingQueue.peek`、无参 `remove`、`element` | 空队列也进入 consumer monitor；`poll` 已有无锁空路径 | 评估增加只读 count 快路径；必须保留与并发 enqueue、`DRAINED` 和 poison 结果之间的 happens-before 关系 |
| `DrainingBlockingQueue.toArray(T[])` | 总是先创建 `Object[]` 快照，再逐项复制到目标类型数组；目标数组容量足够时仍发生中间数组分配和双拷贝 | 优化前必须保留“锁内只复制内部引用、用户数组操作在锁外”的隔离契约；不能为了单次拷贝把用户数组写入放进 monitor |
| `DrainingBlockingQueue.clear()` | 除 drained-noop 分支外，即使队列原本为空也调用 `signalPutReady()` | 记录 clear 前是否真实释放容量，只在需要唤醒生产者时发信号；不得漏掉从满队列或 draining 状态清空后的唤醒与终态发布 |
| `VariableLinkedBlockingQueue.Itr.remove()` | 每次从 head 重新扫描 iterator 保存的节点；批量 iterator removal / `ThreadPoolExecutor.purge()` 形态可能退化为 O(k·n) | 先用真实 purge/iterator workload 量化；优化必须保持 JDK `LinkedBlockingQueue` 的弱一致 iterator 和节点自链接约定 |

这些问题目前只证明“存在额外工作”，尚未证明其成本足以承担并发协议修改风险。进入实现前应先建立可重复基准；没有可测收益的条目应关闭，而不是仅凭静态形状修改锁协议。

## 2. `BACKLOG.md` 的队列性能条目已与当前实现不一致

级别：P3 文档维护缺陷。

### 2.1 P-P4 仍描述已经修复的固定数组分配

`BACKLOG.md` 仍声称 `DrainingBlockingQueue.remove(Object)` 在锁前无条件分配两份 64 槽数组，空队列和短队列都产生约 1 KB 垃圾。当前实现已由 `96fa94d` 修复：

- 空队列直接返回，不分配搜索数组；
- 非空短队列按 live count 定长；
- 超过 64 个元素时才使用 64 槽批次。

需要更新 P-P4，删除已修复的分配问题和对应旧行号。`remove(Object)` 的两阶段“锁内快照—锁外 equals—锁内按身份确认”是 `draining-queue-contract.md` 的现行要求，不能继续把两次阶段简单描述成可合并的重复遍历。若要保留 predecessor hint 优化，必须单独写成未量化候选并证明不破坏并发重验。

### 2.2 P-P5 仍引用已不存在的 `HeuristicPurger`

P-P5 表格仍列出 `HeuristicPurger` 的 CAS/`Stopwatch` 开销，但当前主源码已不存在该类，自动 purge 机制也已退出主线。该行需要删除或改为历史说明，不能继续作为当前待修复项。

### 2.3 条目中的源码行号已漂移

P-P4/P-P5 仍使用旧基线行号。Backlog 顶部虽然声明基线固定并要求修复前复核，但这些行号已不足以定位当前实现，且 P-P4 已出现事实反转。需要改用机制名和成员名，或在条目完成复核后更新基线，避免后续执行者按旧位置重复修复。

## 验证

使用项目要求的 OpenJDK 25 运行与这些结论相关的回归测试：

- `DrainingBlockingQueueTest`
- `InterruptedResultFreezingTest`
- `BatchSettleAttributionTest`
- `TaskGraphRootEdgeTest`
- `TaskGraphPolarityTest`
- `TaskGraphReportFutureTest`
- `InertEnqueueRefusalWarningTest`
- `GroupClassOptionsOverloadTest`
- `ImmediateResultToStringTest`

结果：123 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。

文档检查 `scripts/check-english-docs.py` 通过，其 6 个单元测试全部通过。未运行队列 benchmark、profiler、PIT 或完整测试套件，因此五个性能项的收益尚未得到动态验证。

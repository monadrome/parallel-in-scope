# 取消传播：评审与验证记录（快照 2026-11-04）

> 非契约。从 [取消传播契约](../cancellation-propagation.md) 移出的验证与评审流水，正文与移出时一致，不随现行契约更新。

## 显式请求取消输入（§5.4）

验证与评审：来源注册、终态切断、准入失败、监听注册失败、双目标等待记录与跨线程声明均有反向验证；
PIT 中本次新增语句的变异全部杀死，残留项逐一归为既有行为语句的覆盖限制，未冒充等价变异。Claude 独立复审因
harness 无法提供文件工具而未执行，按维护者指示放弃，不计为评审通过。

## 中断意图移除（§5.1）

反向验证覆盖请求来源与父传播改回 `cancel(false)`、listener 窗口内的 fail-fast 输家与
已派发 timer 输家改为条件级联，均使对应测试失败。限定 PIT（`CancellationToken`、`BatchOptions`、`GroupDraft`、
`TaskGroupDefinition`，含 `VOID_METHOD_CALLS`，无 history）181 个变异，178 KILLED、3 SURVIVED、无 NO_COVERAGE：
`transitionTo` 输家返回 true 判为等价——输家只多一次 `futureToken.cancel(true)`，`futureToken` 的值从不被读取，
其监听（父传播、timer 撤销）都重读 state，SUCCESS 已提交时父传播是 no-op；`timeoutScheduler()` 返回 null 与
`GroupDraft.takePayloads` 删除 `Set.clear` 属于本次未触及的既有语句，作为已知覆盖限制保留。三轮多席位评审
（kiro、claude、ocr，均读到改动文件）：首轮修复陈旧 javadoc 链接、残留两参签名与被删的输家级联断言；第二轮修复
已派发 timer 测试的冗余取消路径；第三轮无新阻断项。驳回：计数输家的第二次取消（同上等价论证）；adopt 成员的
`cancel(false)`（公开面没有成员句柄，被收养成员不交给执行器）。

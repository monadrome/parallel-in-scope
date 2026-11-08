# P0038: queue 公开类型命名收口

## 状态

已由维护者决定在 `0.5.0` 开发线落地。

## 问题

`SmartBlockingQueue` 的 `Smart` 不能说明它的实际行为：它读取提交作用域中的
`rejectEnqueue`，决定是否拒绝本次入队。`VariableLinkedBlockingQueue` 暴露了
`Linked` 这一实现细节，而用户真正依赖的是运行时调整容量。

## 决定

- `SmartBlockingQueue` 更名为 `SubmissionAwareBlockingQueue`；
- `VariableLinkedBlockingQueue` 更名为 `ResizableBlockingQueue`；
- `DrainingBlockingQueue` 保持不变；
- `rejectEnqueue`, `capacity`, `setCapacity` 的行为和签名保持不变；
- 不保留旧类型兼容别名，消费方在 `0.5.0` 重新编译并按迁移指南改名。

## 用户代码

改前：

```java
new ThreadPoolExecutor(4, 8, 0L, TimeUnit.MILLISECONDS,
        new SmartBlockingQueue<>(64));
```

改后：

```java
new ThreadPoolExecutor(4, 8, 0L, TimeUnit.MILLISECONDS,
        new SubmissionAwareBlockingQueue<>(64));
```

容量队列改前后的 import 和类型名同样替换为 `ResizableBlockingQueue`。

## 消除的失败模式

用户不再需要从 `Smart` 猜测队列行为，也不会把 `VariableLinked` 误认为稳定的
存储实现承诺。名称直接表达提交准入和可调整容量两个公开语义。

## 保持的契约

`SubmissionAwareBlockingQueue.offer` 仍只在当前提交单元设置 `rejectEnqueue` 时返回
`false`；`TaskType` 仍不改变入队行为。`ResizableBlockingQueue` 仍是有界 FIFO 队列，
并支持并发安全的 `setCapacity`。本提案不改变取消、deadline、线程池拒绝处理或队列
并发协议。

# 子树 body 退出确认

## 蒸馏摘要

- 核心出发点：父 body 退出不能证明所有后代已停止使用共享资源，减少递归收集子结果的遗漏。
- 不变量与拟议 MUST：后代 MUST 在获执行资格前登记，确认 MUST 有可证明的封口和
  happens-before；MUST 保留直接 body 确认与 Future settled 的区别，共用一次有界清理预算。
- 被否决方向及理由：不偷换 bodyCompletionConfirmed 的直接范围，不用当前子列表快照或
  单一计数冒充封口，不做无限 join 或恢复返回后可轮询的运行句柄。

## 目标与论证

依据：[同步出口契约](../synchronous-scope-exit.md)、[BodyCompletionTracker](../../src/main/java/io/github/monadrome/parallelinscope/BodyCompletionTracker.java)、
[SynchronousExecutionTest](../../src/test/java/io/github/monadrome/parallelinscope/SynchronousExecutionTest.java)
的 `bodyConfirmationDoesNotIncludeUnfinishedNestedChildren`。

现行行为是明确且有测试保护的：父结果可以 SUCCESS 且 `bodyCompletionConfirmed()==true`，
子执行却仍有忽略中断的 body。它符合 direct-only 契约，但共享资源的使用者容易漏掉子结果。

当前应用必须收集相关子结果，在父和子都确认后才释放共享资源。两层场景的代码：

```java
ConcurrentLinkedQueue<TaskBatchResult<?>> children = new ConcurrentLinkedQueue<>();
TaskBatchResult<Value> parent = parentPar.map(items, item -> {
    TaskBatchResult<Value> child = childPar.map(item.parts(), this::fetch, childOptions);
    children.add(child);
    return fallback(child);
}, parentOptions);
boolean confirmed = parent.bodyCompletionConfirmed()
        && children.stream().allMatch(TaskBatchResult::bodyCompletionConfirmed);
```

更深嵌套需要递归收集全部相关执行，而非只检查这个列表。`fallback` 代表应用明确选择的
降级逻辑；业务成功不能作为所有子 body 已退出的证据。

值得原型验证的目标是让执行内核记录后代退出信号，根返回时另外冻结子树确认：

```java
TaskBatchResult<Value> parent = parentPar.map(items, this::loadWithChildren, parentOptions);
boolean confirmed = parent.subtreeCompletionConfirmed();
```

这是新增只读事实的草图；不改变旧 `bodyCompletionConfirmed()` 的直接范围，不提供返回后
轮询的执行句柄。可能配套有界的未退出后代摘要。机制必须覆盖动态注册与封口：后代在
获得执行资格前登记，body 不再能注册后代后才允许确认；退出与 Future settled 仍是不同信号。

先用现有 `ListenableFuture` body-exit 信号做包内原型，避免手写 wait/notify 或新的中断
投递协议。必须证明注册与父退出竞速、取消前后嵌套、跨 runtime 后代、inline、空执行，
以及子树确认的 happens-before。清理仍共用一次有界预算，不能每一层重新获得 closeGrace。
传播中不得长期保留已退出后代的业务值、异常、闭包或完整跟踪树。

如果只改一个计数或对当前子列表取快照，无法证明封口，就不应公开
`subtreeCompletionConfirmed`。显式嵌套保证只覆盖库内同步动态范围；逃逸线程和异步 IO
仍不能算已确认。新增事实可以兼容，改变默认等待范围则需要契约修订和耗时迁移说明。

优先复用 ListenableFuture body-exit 信号做包内原型；逃逸线程与异步 IO 不纳入确认。
此提案没有新协议实现或验证，不以历史全套测试通过推定可行。

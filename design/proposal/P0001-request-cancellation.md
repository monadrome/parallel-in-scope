# 显式请求取消输入

共享 root 预算独立见 [P0004](P0004-shared-root-budget.md)，避免一个入口同时承担可变取消
所有者与只读预算两个角色。下列新增 API 均为草图。

## 蒸馏摘要

- 核心出发点：请求断开时将取消送达已经阻塞的库内 body，消除人工轮询的空窗。
- 不变量与拟议 MUST：外部来源 MUST 作为已有执行 token 的取消输入，MUST NOT 替换结构化
  祖先；first-wins、bind-before-submit、`cancel(false)` 意图、同步出口与有界清理 MUST 保留。
  来源由用户所有，库 MUST NOT 反向取消或完成来源；执行结束 MUST 切断来源到执行的引用。
- 被否决方向及理由：不先构造第二套 parent 树或 ambient RequestScope，它们增加归因与封口协议；
  不要求 immutable options 自动清空来源，带请求来源的 options 本来就具有请求寿命；
  不把中断送达宣传为任意 HTTP/JDBC IO 已停止。

## 1. 需求与现行边界的差距

用户需求：客户端断开/上游取消请求到达时取消整段执行。

现行同步出口契约（[synchronous-scope-exit.md](../synchronous-scope-exit.md)）只保留
deadline/fail-fast/祖先取消三个来源；caller interrupt 不取消 root，运行句柄已撤除。任务体
可以轮询请求标志，但不能唤醒已阻塞的 body。

**改前用户能写出的最好代码**：

```java
AtomicBoolean requestCancelled = ...;   // 应用自己的请求生命周期
par.map(ids, id -> {
    if (requestCancelled.get()) throw new CancellationException();
    return fetch(id);
}, options);
```

失败模式：检查点之间（尤其阻塞 IO 中）取消不可达；外层 `Future.cancel(true)` 不证明 inner
同步调用退出。共享预算的独立问题见 P0004。

## 2. 提议形状（草图）

```java
CancellationToken requestCancellation = new CancellationToken();
BatchOptions options = BatchOptions.timeout("fetch", timeout)
        .cancelWith(requestCancellation);          // 新增，仅 BatchOptions/TaskOptions 或 group 级
TaskBatchResult<Value> result = par.map(ids, this::fetch, options);
// 客户端断开事件中：requestCancellation.cancel(); 或 cancel(false)
```

组级对称形状：`runtime.group("load", timeout).cancellationSource(requestCancellation)...`
（在 `GroupStart` 上加一次，而不是每个成员 `TaskOptions` 重复）。

```java
// 改后同一段代码
BatchOptions options = BatchOptions.timeout("fetch", timeout).cancelWith(requestCancellation);
TaskBatchResult<Value> result = par.map(ids, this::fetch, options);
// fetch 体内的 Checkpoints.sleep/checkAwait 等阻塞适配器在请求取消时被中断唤醒，
// 元素归因 GROUP_CANCELLED（或新增归因词，见 §4），原 throwable 链保留。
```

## 3. 必须先决定的问题

1. **归因**：祖先取消与显式来源同时存在时由已有执行 token 的 first-wins 决定；优先将
   来源事件送入该决定，保留原 parent 链，不先增加第二 parent 树。需要来源身份时再决定
   内部记录。优先沿用 GROUP_CANCELLED，不因输入接入而新增公共 reason enum。
2. **预取消**：bind 前显式来源已取消 → 准入即拒绝还是照常提交后立刻收敛？建议与父已取消
   的现有行为一致（构造期 listener 同步传播，任务不进入用户代码），不新增异常路径。
3. **中断策略**：显式 `cancel(false)` 沿 §5.1 的意图传播规则生效；deadline/fail-fast 仍然
   中断。请求来源没有自己的 deadline。
4. **token 所有权**：用户 token 只是被借用的来源；库不得调用它的 cancel，也不得在它的
   futureToken 上留永久 listener——执行 token 终态时必须切断（复用 ParentLink 的
   sever 机制，双向各一条 link）。
5. **引用切断**：执行 token 终态即切断来源到执行的可达链接；不要求可复用 immutable
   options 清空其来源字段。带请求来源的 options 不承诺跨请求复用。
6. **验证**：覆盖预取消、正常完成解绑、祖先与来源竞速、`cancel(false)` 与 deadline 竞速、
   inline 及来源跨次复用；必须以确定性交错确认归因和引用寿命。

## 4. 迁移与兼容

可选参数：不设置即保持现行行为，无全体迁移。依赖"请求取消不传播"现状的代码不受影响。
若落地，需要在同步出口契约中新增第四个取消来源并更新 `TaskOutcome` 归因表；是否新增
`REQUEST_CANCELLED`  outcome 词或复用 `GROUP_CANCELLED` 留待定稿（复用更少惊扰，
新词更准）。

## 5. 反对意见自检

- 是否只是"用户自己轮询标志"的语法糖？不是：它把取消送达阻塞中的 body（中断投递），
  轮询做不到。
- 是否打开任意外部控制面？受控：来源只有一个、只读取消语义、无运行句柄复活。
- 是否违背同步出口？不改变"调用线程同步等待至完成或失败"，只多一个取消来源。

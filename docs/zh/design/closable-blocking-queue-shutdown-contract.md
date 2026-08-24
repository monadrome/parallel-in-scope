# ClosableBlockingQueue 关闭契约设计

> 可关闭有界 FIFO 阻塞队列的关闭契约规范。目标:关闭后队列对外呈现的行为完全自洽、可预测、可测试。
> 依据 `BlockingQueue` 四形态契约与 JDK 同类先例,关闭行为按"求值优先级瀑布"组织,配置模型只保留取出毒丸和变异策略。

## 1. 核心心智模型

关闭是单向不可逆终态。关闭后,队列对外呈现:

```
关闭后 = 生产端永久无容量(拒一切放入)
      + 消费端行为由求值优先级瀑布决定:
          poison 身份校验 → 禁止写入 poison,抛 IllegalArgumentException
          POISON 策略 → 值返回型取出返回 poison
          Special-value 方法 → offer 返回 false,poll/peek 返回 null
          closed 状态 → Throws/Blocks 方法抛 ISE/NSE
          变异方法 → NOOP(默认)或 THROW(可配)
          开放状态 → 执行 BlockingQueue 原始语义
```

恢复通道(`remainingList()` / `drainTo`)独立于消费策略,**始终可用**——真实剩余元素只从这两处取,不受策略影响。

## 2. 方法分组

以下分组互斥；后文只引用分组，不再按单个方法临时判断语义。

| 分组 | 方法 | 正常状态语义 | 关闭后的默认结果 |
|---|---|---|---|
| `SPECIAL_VALUE` 特殊值方法 | `offer(e)`、`poll()`、`peek()` | 立即返回 `boolean`/元素/`null` | `offer` 返回 `false`；`poll`/`peek` 返回 `null`，POISON 模式返回 poison |
| `THROWS` 抛异常方法 | `add(e)`、`remove()`、`element()`、`addFirst`、`addLast`、`removeFirst`、`removeLast` | 满/空时抛 JDK 异常 | 写入抛 `IllegalStateException`；取出抛 `NoSuchElementException`，POISON 模式返回 poison |
| `BLOCKING` 阻塞方法 | `put(e)`、`take()` | 等待直到可满足或被中断 | `put` 抛 `IllegalStateException`；`take` 抛 `NoSuchElementException`，POISON 模式返回 poison |
| `TIMED_BLOCKING` 超时阻塞方法 | `offer(e, timeout, unit)`、`poll(timeout, unit)` | 等待至成功、超时或中断 | `offer` 返回 `false`；`poll` 返回 `null`，POISON 模式返回 poison；关闭后不继续等待 |
| `MUTATION` mutation 方法 | `clear`、`remove(Object)`、`removeIf`、`removeAll`、`retainAll` | 修改队列内容 | `NOOP` 返回空结果；`THROW` 抛异常 |
| `QUERY` 查询方法 | `size`、`isEmpty`、`remainingCapacity`、`contains`、`isShutdown` | 查询当前状态 | 只反映真实元素和生命周期状态；poison 不计数、不参与查询 |
| `RECOVERY` 恢复方法 | `remainingList`、`drainTo` | 不属于普通消费通道 | 读取/排空真实剩余元素；不处理 poison |
| `ITERATOR` 迭代方法 | `iterator`、`toArray`、`stream` 及其视图迭代器 | 弱一致地观察真实元素 | 只观察真实元素；不观察 poison |

`offer(timeout)`/`poll(timeout)` 虽然返回 `false`/`null`，仍属于 `TIMED_BLOCKING`，不属于 `SPECIAL_VALUE`。`remove()`（无参）属于 `THROWS`，`remove(Object)` 属于 `MUTATION`。

## 3. 设计原则

开放状态下遵守 `BlockingQueue`/`Deque` 原始语义。关闭后按上述分组求值：poison 身份校验是所有写入的前置校验；POISON 只覆盖值返回型取出结果；各组保留自己的返回/异常通道。

## 4. 规则优先级

**核心机制:优先级瀑布,首条匹配 rule 独占。** 每个方法在每个配置下只被一条 rule 管辖,从高到低首条命中即止,rule 间互斥,故行为层无冲突。

| 优先级 | Rule | 适用条件 | 关闭后行为 |
|---|---|---|---|
| 前置校验 | poison 身份校验 | 所有写入方法的参数 | 与 poison 同一引用时抛 `IllegalArgumentException` |
| 1(最高) | POISON 策略 | 配置 poison 的值返回型取出方法 | 返回 poison |
| 2 | `SPECIAL_VALUE` | 特殊值方法 | 返回 `false`/`null` |
| 3 | `TIMED_BLOCKING` | 带计时阻塞方法 | 超时返回 `false`/`null`；关闭后立即返回关闭结果 |
| 4 | `THROWS` + `BLOCKING` | 抛异常方法、阻塞方法 | 写入抛 `IllegalStateException`；取出抛 `NoSuchElementException` |
| 5 | `MUTATION` | mutation 方法 | `NOOP` 或 `THROW` |
| 6 | `QUERY`/`RECOVERY`/`ITERATOR` | 查询、恢复、迭代方法 | 按各自分组契约执行 |

不定义自定义 `QueueShutdownException`;关闭结果直接复用 `IllegalStateException`、`NoSuchElementException` 或 poison。

## 5. 配置模型(收敛后)

配置只保留两个维度:

- `poison`：可选且非 `null`。关闭后，值返回型取出方法返回该对象：`poll`、`poll(timeout)`、`peek`、`take`、`element`、`remove()` 及端点 remove 方法；未配置时，Special-value 方法返回 `null`，Throws/Blocks 方法抛 `NoSuchElementException`。
- `mutations`：`NOOP`（默认）或 `THROW`，控制关闭后的 `clear`、`remove(Object)`、`removeIf`、`removeAll`、`retainAll`。

插入行为按方法形态处理：`offer`/`offer(timeout)` 关闭后返回 `false`，`put`/`add` 关闭后抛 `IllegalStateException`。timed 方法无独立策略。`remove()` 无参版本属于取出方向；`remove(Object)` 属于变异方向。

## 6. 命名预设 + 单维度覆盖

```java
QueueShutdownPolicy.empty()
QueueShutdownPolicy.poison(p)
QueueShutdownPolicy.throwing()

QueueShutdownPolicy.builder()
    .poison(p)
    .mutations(MutationsStrategy.THROW)
    .build();
```

预设保证组合自洽;覆盖只改单维度,语义仍可推导。非法组合在构造期拒绝。开放状态下 timed 方法遵守 JDK timeout 和可中断语义;关闭后按 Special-value 或 Throws/Blocks 形态返回，不继续等待剩余 timeout。

## 7. 关闭后完整契约表

按默认预设 `empty()` 展开(插入固定 + 取出 POISON 关 + 变异 NOOP):

| 方法 | 关闭后行为 |
|---|---|
| `offer(e)` / `offer(e,t,u)` | `false` |
| `put(e)` | `IllegalStateException`(降级 add,无返回值通道,不能阻塞) |
| `add(e)` / `addFirst` / `addLast` / `addAll` | `IllegalStateException`(与"满"一致) |
| `poll()` | `null`(永久空) |
| `poll(t,u)` | `null`(不等待 timeout) |
| `take()` | `NoSuchElementException`(降级 remove,无 null 通道,不能阻塞) |
| `peek()` | `null` |
| `element()` | `NoSuchElementException` |
| `remove()` / `removeFirst` / `removeLast` | `NoSuchElementException` |
| `remove(Object)` | `false`(变异 NOOP) |
| `clear()` | no-op |
| `removeIf` / `removeAll` / `retainAll` | `false` |
| `size()` / `isEmpty()` | `0` / `true`(只计真实元素,毒丸不计) |
| `iterator()` / `toArray` / `stream` | 空迭代 / 空数组 / 空流(弱一致,与关闭无关) |
| `contains` | `false` |
| `remainingCapacity()` | `0`(永久无容量) |
| `remainingList()` | 返回关闭时保留的真实元素快照(`CopyOnWriteArrayList`) |
| `drainTo` | 排空真实剩余元素(含恢复列表),不返毒丸 |

`poison(p)` 预设将所有值返回型取出结果改为 `p`，其优先级高于 Special-value 的 `null`。毒丸是身份哨兵而不是元素：必须非 `null`，调用方以 `==` 判断；所有写入路径拒绝同一引用并抛 `IllegalArgumentException`，包括 `addAll` 的预校验。毒丸不计数、不迭代、不进入恢复通道。因此 poison 模式的 `size()`/`isEmpty()` 与取出结果是两个有意分离的视图，消费循环应使用 `take() == poison` 退出。

## 8. 并发与关闭

| 不变量 | 说明 |
|---|---|
| 幂等 | 多次 close 等价首次,无副作用 |
| 并发安全 | 多线程并发 close 安全,内部同步 |
| happens-before | close 前已入队元素对后续 `remainingList()`/`drainTo` 可见(close 充当内存屏障) |
| 唤醒保证 | close 立即唤醒所有阻塞的 put/take/poll(t)/offer(t),重新获得队列锁后按关闭规则返异常/值/毒丸 |
| 互斥与线性化 | close、remove、drainTo 不重叠;三者在同一临界区确定先后。close 先线性化时,后续 remove 按关闭策略处理;drainTo 仍可排空真实剩余元素 |
| 锁顺序 | 若使用两把锁,统一按 lifecycleLock → queueLock 获取;禁止反向获取。阻塞等待不得持有 lifecycleLock |

## 9. 调用约定

- POISON 关闭：消费循环使用 `take() == poison` 判断结束；`poll()`/`peek()` 同样返回 poison。
- 非 POISON 关闭：`poll()`/`peek()` 返回 `null`，需要区分关闭与暂时空队列时调用 `isShutdown()`；`take()` 等 Throws/Blocks 取出方法抛 `NoSuchElementException`。
- `size`/`isEmpty`/迭代器只反映真实元素，不用于判断 poison 消费信号。
- `remainingList()` 是快照，`drainTo` 会排空真实剩余元素；二者都不处理 poison。

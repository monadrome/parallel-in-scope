# SimpleResizableBlockingQueue Design Invariants

下面可以直接作为后续实现和 code review 的检查清单。

## 目标模型

```text
ResizableBlockingQueue
├── AtomicReference<QueueState<E>>
│   ├── LinkedBlockingQueue<E> delegate
│   ├── capacity
│   └── generation
├── lifecycleLock
│   ├── readLock：普通队列操作
│   └── writeLock：resize
└── WaitCoordinator（稳定、不替换）
    ├── waitLock
    ├── notEmpty
    └── notFull
```

## 功能不变量

1. `capacity > 0`，非法容量必须在修改状态前失败。
2. resize 完成后始终满足 `0 <= size <= capacity`。
3. 队列不接受 `null`。
4. 扩容必须迁移全部任务，overflow 为空。
5. 缩容且 `size <= newCapacity` 时迁移全部任务，overflow 为空。
6. 缩容溢出时，保留最早的 `newCapacity` 个任务，返回队尾任务。
7. 新队列和 overflow 都维持原始 FIFO 顺序。
8. `新队列元素 ∪ overflow = resize 前全部元素`，不能丢失或重复。
9. overflow 中的任务已经从队列移除，后续由调用方负责。
10. `size()`、`remainingCapacity()`、`getCapacity()` 必须来自同一个 QueueState。
11. resize 与普通操作线性化：每个操作只能完整发生在 resize 之前或之后。
12. 多个并发 resize 必须串行，并基于前一次完成后的最新状态。

## 等待不变量

1. 可替换的 delegate 永远不能执行阻塞式 `put/take`。
2. delegate 只使用 `offer/poll/peek/remove/drainTo` 等有限操作。
3. 所有长期等待都发生在稳定的 `WaitCoordinator` 上。
4. `notEmpty/notFull` 及其关联锁在整个队列生命周期内不能替换。
5. 等待线程被唤醒后必须重新读取 `AtomicReference`，不能缓存旧 delegate。
6. Condition 必须使用 `while` 重试，处理竞争、resize 和伪唤醒。
7. Condition 等待期间不能持有 lifecycle read/write lock。
8. 等待前必须在 `waitLock` 下重新检查当前 QueueState，防止丢失唤醒。
9. 成功入队后唤醒 `notEmpty`；成功出队、remove、clear、drain 后唤醒 `notFull`。
10. resize 完成后根据新状态唤醒等待者；正确性优先时可以 `signalAll()`。
11. 若使用 waiter count 优化信号，等待者必须先登记，再重新检查状态。
12. 中断和超时不能被 resize 吞掉；超时在多次唤醒后不能重新计时。

## 锁顺序约束

1. 普通短操作获取 lifecycle read lock。
2. resize 获取 lifecycle write lock。
3. lifecycle read lock是“普通操作共享门”，不是只读语义；offer/poll 都可持有它。
4. resize 持有写锁期间，不允许任何 delegate 操作正在执行。
5. 发 Condition 信号前必须先释放 lifecycle lock。
6. 禁止 `lifecycle lock -> waitLock` 的嵌套顺序。
7. 等待前重新检查可以使用 `waitLock -> lifecycle read lock`。
8. resize 不能持有 write lock 再获取 waitLock，否则可能与等待线程形成锁环。
9. lifecycle lock 应考虑公平模式，防止持续 offer/poll 使 resize 饥饿。

## Resize 事务约束

1. 新 QueueState、replacement 和 overflow 应尽可能在破坏旧状态前构造完成。
2. 迁移期间旧 delegate 必须保持稳定，不能并发写入或消费。
3. 推荐将 delegate、capacity、generation 放入同一个不可变 QueueState 原子发布。
4. `stateRef.set(newState)` 是 resize 的线性化点。
5. 发布后旧 delegate 不得再被任何新操作访问。
6. 旧 delegate 上不能存在阻塞线程，这是能够安全替换的前提。
7. resize 失败时应保持旧 QueueState 可继续使用，避免半迁移状态。
8. resize 到相同容量的行为需要明确：无操作，或仍创建新 generation。
9. resize 是 O(n) 且需要额外 O(n) 临时内存，这是明确接受的停顿成本。
10. 大队列 resize 期间所有新操作会等待，不能把它当作高频控制手段。

## 必须覆盖的并发 Case

| Case | 预期结果 |
|---|---|
| 空队列上 `take`，随后 resize，再 offer | consumer 在稳定 Condition 上醒来并读取新队列 |
| 满队列上 `put`，随后扩容 | producer 被唤醒并写入新队列 |
| 满队列上 `put`，随后缩容且新队列仍满 | producer 继续等待 |
| `poll` 失败后、登记等待前发生 offer | 重新检查发现元素，不得永久等待 |
| `offer` 失败后、登记等待前发生扩容 | 重新检查发现容量，不得永久等待 |
| resize 与 offer 同时发生 | 元素只能进入旧队列后被迁移，或直接进入新队列 |
| resize 与 poll 同时发生 | 元素只能被消费一次 |
| 两个 resize 并发 | 后一个基于前一个完成后的 QueueState |
| timed offer/poll 遭遇多次 resize | 总等待时间不重置 |
| 等待线程被 interrupt | 按 BlockingQueue 契约抛出 `InterruptedException` |
| 缩容到刚好等于 size | overflow 为空，新队列满 |
| 缩容到小于 size | overflow 完整、有序、无重复 |
| resize 空队列 | 新容量正确，无虚假元素 |
| resize 到相同容量 | 行为符合预先定义 |
| 多生产者、多消费者、反复 resize | 所有任务最终只出现一次 |
| iterator 在 resize 前创建 | 必须定义为快照或弱一致语义 |
| iterator.remove 与 resize 并发 | 不能删除错误任务或破坏容量信号 |
| `clear/remove/drainTo` 后存在阻塞 producer | producer 必须被唤醒 |
| `ThreadPoolExecutor.purge()` | iterator.remove 必须兼容取消任务清理 |
| `shutdownNow()` 与 resize 并发 | drain 与 overflow 不能重复归还任务 |

## 额外约束

- `add()` 继承自 `offer()`：队列满时应抛出 `IllegalStateException`。
- overflow 任务曾被执行器接受，调用方必须明确选择重提、转移、拒绝或取消。
- 相等但不同实例、同一实例重复入队时，iterator removal 语义需要特别测试。
- `drainTo(this)` 必须拒绝。
- 不要暴露底层 delegate，否则调用方可以绕过生命周期锁和等待协调器。
- 为减少信号开销可以记录等待者数量，但不能以牺牲无丢失唤醒为代价。
- 当前单锁实现满足等待和迁移正确性，但串行化了 offer/poll；读写锁重构只能优化普通操作，不能把阻塞重新下放给 delegate。

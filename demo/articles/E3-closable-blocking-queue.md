# E3. 可关闭的 BlockingQueue：思路、约束、契约与技术设计

> 从零实现一个带"永久终止"语义的 BlockingQueue：终止前正在等待的调用抛 InterruptedException，终止后发起的调用抛 UnsupportedOperationException。基于 Guava Monitor 双管程，终止状态折叠进等待谓词，无需线程注册表即可唤醒全部等待者。

## 问题背景：JDK 队列没有关闭语义

生产-消费是并发基础模式。但 JDK BlockingQueue 缺一个能力：关闭。线程池有 `shutdown()` / `shutdownNow()`，队列没有。任务队列排空后，`take()` 永久阻塞；放满后，`put()` 永久阻塞。程序要优雅退出，只能靠毒丸（poison pill）——塞一个特殊标记，消费端识别后退出。

毒丸的痛点：

- 只解决消费端。生产端 `put()` 满队列阻塞、无人消费时永远醒不来。
- 需要业务代码识别毒丸，污染逻辑；元素类型还得兼容"毒丸"这个值。
- 多消费者场景每个消费者都要分到毒丸，漏一个就卡死。

思路转向：把"关闭"做成队列的一等公民。队列自己持有终止状态，终止瞬间唤醒所有等待者，之后拒绝一切操作。调用方不用改业务逻辑，队列自己处理。

## 约束：先立规矩再写代码

实现前，先明确边界：

1. **单向不可逆**。终止是永久状态，没有重启。
2. **契约以"调用发起时点"分界**。终止瞬间正在等待的调用，与终止之后发起的调用，行为必须不同。
3. **等待者必须被唤醒，且不保留线程引用**。不用线程注册表，终止时全部释放。
4. **等待中的阻塞调用抛 InterruptedException**。四种阻塞方法：`put` / `take` / `offer(限时)` / `poll(限时)`，以及排队等锁期间被终止的调用。
5. **终止后发起的调用抛 UnsupportedOperationException**。非阻塞操作、集合操作、迭代器、序列化，全部拒绝。
6. **外部中断 ≠ 终止**。业务线程被 `interrupt()` 只影响该线程，队列继续可用；只有显式 `terminate()` 能终止队列。
7. **`terminate()` 不得死锁**。双管程模型下，它要与任何并发路径都能安全交错。
8. **空转快速失败**。终止后，`offer()` / `poll()` / `peek()` / `size()` 这些非阻塞操作应无锁快速拒绝，而不是抢锁后发现已终止。

## 契约：三种结果，一条分界线

| 场景 | 行为 |
|---|---|
| 终止前正常操作 | 标准 BlockingQueue 语义：FIFO、有界、双管程并发 |
| 终止时正在等待（put / take / offer(t) / poll(t)） | 抛 `InterruptedException("queue service has been terminated")` |
| 终止前已开始、排队等 monitor 时被终止 | 抛 `InterruptedException`（阻塞方法拿到锁后重查终止态） |
| 终止后发起操作（28 个：size / offer / poll / iterator / toArray / serialize ...） | 抛 `UnsupportedOperationException("queue service has been terminated")` |
| `terminate()` | 幂等：首次返回 true，之后 false |
| `isTerminated()` | 布尔查询 |

为什么等待中的调用用 InterruptedException，而不是自定义异常或返回 null？

- 调用已经在"阻塞"语境里，Java 表达"等待被打断"的既定信号就是 InterruptedException。调用方现有的 `catch (InterruptedException)` 路径直接生效，无需新增异常类型。
- 返回 null 有歧义：`poll()` 超时本来就返回 null，无法区分"超时"与"队列被关闭"。
- 自定义异常要侵入调用方的 catch 结构，破坏对现有阻塞 API 的兼容。

为什么终止后的调用用 UnsupportedOperationException？操作压根没开始，队列处于不可用终态。这是运行时异常，调用方不必声明。消息统一 "queue service has been terminated"，可读、可测。

分界线的关键是"调用已开始"这个时点。阻塞调用从进入方法起就处于阻塞语境，之后无论卡在哪一步（等 monitor、等元素、等容量），终止都按"等待被打断"处理——InterruptedException。非阻塞 / 集合调用没有等待语义，被终止拦截就是拒绝——UnsupportedOperationException。

一个容易踩的细节：**终止路径抛出的 InterruptedException 不会设置当前线程的中断标志**。队列的终止不是对线程的 interrupt，只是"谓词满足了终止分支"。调用方如果习惯性地在 catch 里 `Thread.currentThread().interrupt()` 恢复中断状态，反而会错误地中断自己。这是刻意的语义：队列关闭不等于线程被打断。

## 技术设计

### 选型：为什么是 Guava Monitor

| 方案 | 问题 |
|---|---|
| `synchronized` + wait/notify | 谓词判断要手写循环，跨线程通知要自己记标志位，易错 |
| ReentrantLock + Condition | `await` 后要手动 `while` 循环重查谓词（spurious wakeup 由调用方兜底），通知也要手动 |
| Guava Monitor | `newGuard(predicate)` + `waitFor(guard)`，谓词在每次唤醒时自动重估，循环由库托管 |

Monitor 的 guard 天然贴合本文场景：谓词本身是"终止 或 条件满足"的复合表达式。终止状态折叠进谓词，唤醒语义自动成立——不用为"终止"单独设计通知机制。

### 双管程布局：复刻 LinkedBlockingQueue 的双锁

JDK LinkedBlockingQueue 用 takeLock / putLock 双锁，生产和消费并行不互斥。这里同样拆两个 Monitor：

- `putMonitor` 保护 tail 与容量等待
- `takeMonitor` 保护 head 与元素等待
- `AtomicInteger count` 串联两端

```java
private final Monitor putMonitor = new Monitor();
private final Monitor takeMonitor = new Monitor();
private final AtomicInteger count = new AtomicInteger();

private final Monitor.Guard notFullOrTerminated = putMonitor.newGuard(
        () -> terminated || putPermitted);
private final Monitor.Guard notEmptyOrTerminated = takeMonitor.newGuard(
        () -> terminated || takePermitted);
```

`put()` 的结构（`take()` 对称）：

```java
public void put(E element) throws InterruptedException {
    rejectIfTerminated();                      // ① 无锁快拒
    putMonitor.enterInterruptibly();           // ② 可中断进管程
    try {
        throwIfTerminatedDuringBlockingCall(); // ③ 持锁重查：阻塞调用被打断 = 中断
        if (count.get() == capacity) {
            putMonitor.waitFor(notFullOrTerminated);   // ④ 等待容量
            throwIfTerminatedDuringBlockingCall();     // ⑤ 醒来重查
        }
        enqueue(new Node<>(element));
        putPermitted = count.get() < capacity;         // ⑥ 维护谓词不变式
    } finally {
        putMonitor.leave();
    }
    if (previousCount == 0) {
        signalNotEmpty();   // ⑦ 跨管程唤醒消费端
    }
}
```

### 终止即唤醒：状态折叠进谓词

终止为什么能唤醒所有人？因为终止标志是两侧谓词的一部分：

```java
public boolean terminate() {
    fullyLock();          // 先 putMonitor 后 takeMonitor，固定锁序
    try {
        if (terminated) return false;
        terminated = true;
    } finally {
        fullyUnlock();    // leave 触发 Monitor 重估谓词，全部等待者醒来
    }
    return true;
}
```

`fullyLock` 同时占住两个管程，置位后 `fullyUnlock` 释放。Monitor 在 leave 时唤醒 guard 已满足的等待线程重估谓词——此时 `terminated=true` 使两个谓词都为真，所有等待者醒来。醒来后每个阻塞方法立刻 `throwIfTerminatedDuringBlockingCall()`，抛 InterruptedException。

这里有个优雅的点：**终止不需要保留任何等待线程的引用**。终止状态就在谓词里，等待者是自己"看见"终止并退出的。没有线程注册表，没有 shutdownNow 式的线程枚举。

### 跨管程通知：布尔缓存谓词

双管程的代价：消费者在 takeMonitor 释放容量，等待的生产者却在 putMonitor 上，Monitor 不会跨实例通知。需要显式跨管程信号：

```java
// 消费端：容量空出来了，通知生产端
private void signalNotFull() {
    putMonitor.enter();
    try {
        putPermitted = count.get() < capacity;   // 持锁重查，写入真实状态
    } finally {
        putMonitor.leave();
    }
}
```

为什么谓词读缓存布尔 `putPermitted`，而不是直接读 `count.get()`？

关键在不变式：`putPermitted == (count < capacity)`，且只在持锁时由对侧写入。谓词把"是否还有容量 / 元素"这个判定权收拢到持有对应管程的一侧，等待线程的唤醒不再依赖对共享计数的实时读取，而是依赖对侧"确认过"的状态翻转。这让 guard 从"尽力而为的提示"变成**可靠的先决条件**——所以 `put()` 在 `waitFor` 返回后直接 enqueue，不再二次检查容量；`take()` 同理。谓词满足即证明安全，代码可以信任它。

### 三类检查：堵死三个竞态窗口

终止与并发操作交错，有三个窗口必须各自封死：

| 窗口 | 检查 | 异常 |
|---|---|---|
| 进 monitor 前（快路径） | `rejectIfTerminated()` | UnsupportedOperationException |
| 持锁后（非阻塞 / 集合操作） | `ensureOperational()` | UnsupportedOperationException |
| 持锁后 / 醒来后（阻塞方法） | `throwIfTerminatedDuringBlockingCall()` | InterruptedException |

非阻塞的 `offer()` 是典型例子，体现"快拒 + 持锁重查"的叠加：

```java
public boolean offer(E element) {
    rejectIfTerminated();              // 快拒：终止后无锁直接抛
    if (count.get() == capacity) {     // 无锁判满，满了直接返回 false
        return false;
    }
    putMonitor.enter();
    try {
        ensureOperational();           // 持锁重查：快拒与进锁之间可能被 terminate 抢先
        if (count.get() < capacity) {  // 再次判满：进锁前可能被其他生产者填满
            enqueue(new Node<>(element));
            ...
        }
    } finally {
        putMonitor.leave();
    }
    ...
}
```

快拒避免终止后所有操作都去抢锁；持锁重查保证"快拒通过之后、拿到锁之前"这个空窗期的 terminate 不会漏网。阻塞方法则多一层：拿到锁后查终止态抛的是 InterruptedException，因为调用始终处于阻塞语境。

### drainTo 与终止的交叉

`drainTo` 持 takeMonitor，且可能阻塞在 `target.add()`（目标集合满 / 慢）。此时消费者排队，terminate 在 fullyLock 上排队。drain 释放锁后谁先拿到锁都能正确推进：

- 消费者先拿 takeMonitor：发现 terminated，抛 InterruptedException；
- terminate 先拿双锁：置位，唤醒全部等待者。

防止死锁靠两点：**固定锁序**（所有 fullyLock 都是 put→take），以及**不变量维护**——drainTo 在 finally 里修正 head / count / 谓词状态，即使 `target.add` 抛异常，未转移的元素留在队列、生产者被唤醒。

## 示例

完整可运行代码见 `io.github.huatalk.parallelinscope.queue.ClosableQueueLifecycleDemo`：

```java
public static void main(String[] args) throws Exception {
    // 一个放满的队列：put / 限时 offer 都会阻塞
    MonitorLinkedBlockingQueue<Integer> fullQueue = new MonitorLinkedBlockingQueue<>(1);
    fullQueue.put(0);
    // 一个空队列：take / 限时 poll 都会阻塞
    MonitorLinkedBlockingQueue<Integer> emptyQueue = new MonitorLinkedBlockingQueue<>(1);

    AtomicInteger interrupted = new AtomicInteger();
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    List<Thread> waiters = Arrays.asList(
            blocked("blocked-put", interrupted, unexpected,
                    () -> { fullQueue.put(1); return null; }),
            blocked("blocked-timed-offer", interrupted, unexpected,
                    () -> fullQueue.offer(2, 1, TimeUnit.DAYS)),
            blocked("blocked-take", interrupted, unexpected, emptyQueue::take),
            blocked("blocked-timed-poll", interrupted, unexpected,
                    () -> emptyQueue.poll(1, TimeUnit.DAYS)));

    for (Thread waiter : waiters) {
        waiter.start();
    }
    TimeUnit.MILLISECONDS.sleep(300);   // 等 4 个线程全部进入阻塞

    System.out.println("terminate fullQueue -> " + fullQueue.terminate());
    System.out.println("terminate emptyQueue -> " + emptyQueue.terminate());
    System.out.println("terminate again (idempotent) -> " + fullQueue.terminate());

    for (Thread waiter : waiters) {
        waiter.join(5_000);
    }
    System.out.println("blocked callers interrupted: " + interrupted.get()
            + " (expect " + waiters.size() + ")");
    if (unexpected.get() != null) {
        throw new IllegalStateException("unexpected outcome", unexpected.get());
    }

    try {
        fullQueue.offer(3);
        throw new AssertionError("offer after termination should fail");
    } catch (UnsupportedOperationException terminated) {
        System.out.println("post-termination offer -> " + terminated.getMessage());
    }
}

private static Thread blocked(String name,
        AtomicInteger interrupted,
        AtomicReference<Throwable> unexpected,
        CheckedOperation operation) {
    return new Thread(() -> {
        try {
            operation.run();
            unexpected.compareAndSet(null,
                    new IllegalStateException(name + " returned instead of blocking"));
        } catch (InterruptedException expected) {
            interrupted.incrementAndGet();
        } catch (Throwable failure) {
            unexpected.compareAndSet(null, failure);
        }
    }, name);
}
```

输出：

```
terminate fullQueue -> true
terminate emptyQueue -> true
terminate again (idempotent) -> false
blocked callers interrupted: 4 (expect 4)
post-termination offer -> queue service has been terminated
isTerminated -> true
```

四个线程分别阻塞在 put / offer(限时) / take / poll(限时)，`terminate()` 后全部以 InterruptedException 退出，证明终止契约完整覆盖所有等待入口；`terminate()` 幂等返回 false；终止后非阻塞 offer 抛 UnsupportedOperationException。

## 测试验证

终止契约与竞态由 `MonitorLinkedBlockingQueueTest` 系统覆盖，关键用例：

- `terminationInterruptsAllBlockedProducers` / `terminationInterruptsAllBlockedConsumers`：满队列的 put 与空队列的 take 全被唤醒
- `terminationInterruptsTimedProducerAndConsumerWaits`：限时 offer / poll 同样被打断
- `terminationInterruptsMixedWaitersAcrossAllFourBlockingMethods`：四种阻塞方法混合等待
- `producersWaitingForMonitorAcquisitionAreInterruptedByTermination`：序列化持锁时，排队等 monitor 的调用被终止
- `globalCollectionOperationsRaceWithTerminationWithoutDeadlock`：全局操作与终止赛跑不死锁
- `mpmcPreservesEveryElementUnderGlobalReadContention`（@RepeatedTest(10)）：多生产者多消费者 + 全局快照观察者，元素零丢失
- `counterpartReleaseCompletesOperationBeforeLaterTermination`：即将完成的调用在终止前正常结束
- `externalInterruptDoesNotTerminateQueue`：外部中断不终止队列
- `operationsStartedAfterTerminationAreUnsupported`：终止后 28 个操作全部拒绝

> 📁 完整测试代码：[MonitorLinkedBlockingQueueTest.java](https://github.com/huatalk/parallel-in-scope/blob/main/src/test/java/io/github/huatalk/parallelinscope/MonitorLinkedBlockingQueueTest.java)

## 总结

- **契约两条线**：终止前已开始的阻塞调用 → InterruptedException；终止后发起的调用 → UnsupportedOperationException。`terminate()` 幂等。
- **终止即谓词**：`terminated` 折叠进两个 guard，等待者自己"看见"终止退出，无线程注册表。
- **双管程 + 跨管程布尔信号**：复刻 LinkedBlockingQueue 双锁并发度；谓词不变式让 guard 成为可靠先决条件。
- **三类检查封死全部竞态窗口**：无锁快拒、持锁重查（非阻塞）、持锁 / 醒来重查（阻塞）。
- **固定锁序 + 不变量维护**，保证 terminate 与任何并发路径交错不死锁。

---

如果这篇文章对你有帮助，欢迎关注我，持续分享高质量技术干货，助你更快提升编程能力。

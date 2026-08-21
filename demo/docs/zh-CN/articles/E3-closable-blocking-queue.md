# E3. 可关闭的 BlockingQueue：ClosableBlockingQueue 的设计目标、约束与契约

> 一个把"关闭"做成队列一等公民的有界 FIFO 阻塞队列：close() 自动释放全部等待者但从不中断线程，关闭后写操作抛 QueueShutdownException，消费端可配毒丸，存量元素经 remainingList() 恢复。基于 Guava Monitor 双管程 + AbstractService 生命周期。

## 设计目标

JDK BlockingQueue 没有关闭语义。任务队列排空后 `take()` 永久阻塞，放满后 `put()` 永久阻塞。传统解法是毒丸（poison pill），但毒丸只解决消费端、需要业务代码识别标记、多消费者还容易漏分。

`ClosableBlockingQueue` 的设计目标，是把"关闭"做成队列支持的能力：

1. **关闭自动释放全部等待者，不依赖协作**。`close()` 一次调用，生产端和消费端所有阻塞调用全部苏醒，无需毒丸或 shutdownNow 式的线程枚举。
2. **关闭 ≠ 中断**。关闭不 `interrupt()` 任何线程。等待者"看见"关闭后以明确结果退出——抛异常或返回毒丸——结果可预期、可测试。外部 `interrupt()` 的既有语义原样保留。
3. **生产消费对称**。`put` / `offer(t)` 被关闭释放 → 抛异常；`take` / `poll(t)` 被关闭释放 → 抛异常或返回毒丸。两边都不卡死，不支持"只关一半"。
4. **元素可恢复**。关闭时已入队元素不丢失，`remainingList()` 在终止后取回。
5. **关闭不被用户代码阻塞**。`stopAsync()` 不遍历链表，存量延迟获取；用户回调（drain 目标集合的 add、元素 equals、forEach 回调）都在 monitor 之外执行，再慢也拖不住关闭。
6. **完整兼容**。实现 `BlockingQueue`  + Guava `Service` + `AutoCloseable`，并暴露 Java 21 sequenced 集合端点与反向视图。
7. **关闭幂等、可等待**。并发 stop 只发布一次；`awaitTerminated()` 保证恢复快照已固定、所有已准入的阻塞调用已退出。

## 约束

实现前立下的边界：

1. **有界 FIFO，元素非 null**，行为对齐 `LinkedBlockingQueue`。
2. **无线程注册表**。用 packed 原子"准入词"（closed 位 + 活跃阻塞调用计数）代替线程枚举，`awaitTerminated()` 靠它保证所有准入调用已离开。
3. **双状态机协同**。内部 `OPEN / CLOSING / CLOSED`（队列判定用）+ Guava `NEW / RUNNING / STOPPING / TERMINATED`（生命周期发布用），两边通过终止发布协议衔接。
4. **关闭判定折叠进 guard 谓词**。`queueState != OPEN` 是 put/take 两侧谓词的组成项，等待者自己"看见"关闭，无需外部唤醒信号。
5. **阻塞调用准入制**。进入阻塞前取一个 lease；关闭后新调用直接拒绝，已持 lease 的调用完成后才发布终止。
6. **Java 8 兼容**。sequenced 端点以普通方法暴露；运行时是 Java 21 时 `reversed()` 返回的 List 才额外实现 `SequencedCollection`。
7. **用户代码不进锁**。`equals` / `compareTo` / 集合回调在 monitor 外执行，持锁期间绝不调用业务代码。

## 新契约

| 时机 | 行为 |
|---|---|
| 正常操作 | 标准 BlockingQueue 语义：FIFO、有界、put/take 双管程并发 |
| 关闭时正在等待 put / offer(t) | 释放，抛 `QueueShutdownException` |
| 关闭时正在等待 take / poll(t)，未配毒丸 | 释放，抛 `QueueShutdownException` |
| 关闭时正在等待 take / poll(t)，配了毒丸 | 释放，返回毒丸对象 |
| 关闭后写操作（offer / add / clear / remove / addAll ...） | 抛 `QueueShutdownException` |
| 关闭后消费操作，未配毒丸 | 抛 `QueueShutdownException` |
| 关闭后消费操作，配了毒丸 | 返回毒丸对象 |
| 外部 `interrupt()` 等待中 | 抛 `InterruptedException`（与关闭正交，原样传播） |
| `remainingList()` | 终止后返回已入队元素的共享 `CopyOnWriteArrayList` |
| `close()` / `stopAsync()` | 幂等、从不中断线程 |
| `awaitTerminated()` | 阻塞直到终止发布且准入调用清零 |

三个关键决策：

**为什么是 unchecked `QueueShutdownException`，而不是 `InterruptedException`？** 关闭不打断线程。`InterruptedException` 是"外部中断"的既定信号，关闭是"队列不再接受操作"的语义，两者必须分开。若复用中断异常，调用方现有的 `catch (InterruptedException)` 会被队列关闭误触发，且中断标志状态会被污染。`QueueShutdownException` 继承 `IllegalStateException`，是运行时异常，不破坏 `BlockingQueue` 的方法签名，也不被业务 catch 误接。

**为什么毒丸可选而不是强制？** 异常模式 fail-fast，适合"关闭即收尾"；毒丸模式适合"关闭后消费端还要优雅收尾"的场景。构造器传一个毒丸对象即切换模式。毒丸是**虚拟对象**：它保留身份、从不入链表、不计数、不进 `remainingList()`；若用户试图入队与毒丸同一标识的元素，`requireElement` 直接拒绝。

**关闭时元素不丢失，但也不"救回"未入队的 put。** 阻塞中的 `put` 其元素从未进入队列，仍归调用方所有。关闭后它抛异常返回，元素由调用方自己处理。测试 `blockedProducerPayloadIsNotReportedAsRemaining` 明确了这一边界。

## 现有实现支持功能

- **队列核心**：四种阻塞方法（put / take / offer(t) / poll(t)）、非阻塞 offer / poll / peek、`drainTo`（锁内摘除、锁外回调）、`clear`、`remove`、弱一致迭代器（Java 8 LinkedBlockingQueue 形状，支持基于身份移除）。
- **生命周期**：完整 Guava `Service` API（startAsync / stopAsync / awaitRunning / awaitTerminated / state / isRunning / addListener / failureCause）；首个阻塞调用隐式启动服务；显式重复启动被拒绝；并发 stop 幂等。
- **恢复与诊断**：`remainingList()` 懒物化为共享 `CopyOnWriteArrayList`（首个访问者物化，之后并发读写互不干扰）；`waitingProducers()` / `waitingConsumers()` 等待队列长度诊断；`isShutdown()`；诊断型 `toString()`。
- **关闭安全**：关闭不被阻塞的 drain 目标、阻塞的 equals、阻塞的 forEach、阻塞的倒序 addAll 拖延；`drainTo` 半途失败时已摘除批次归 target 所有，不回灌、不进 remaining。
- **Sequenced 面**：`addFirst` / `addLast` / `getFirst` / `getLast` / `removeFirst` / `removeLast`，以及 `reversed()` 反向 List 视图——支持端点、按位读写、批量添加、fail-fast 迭代器，双向写穿到队列；Java 21 下视图还实现 `SequencedCollection`。
- **双管程并行**：put 与 take 各自独立 Monitor，生产和消费并发不互斥。

## 技术设计

### 双管程布局与 guard 谓词

与 `LinkedBlockingQueue` 双锁一致，`putMonitor` 保护 tail 与容量等待，`takeMonitor` 保护 head 与元素等待，`AtomicInteger count` 串联两端。guard 谓词直接读共享状态，无需缓存布尔：

```java
private final Monitor.Guard takeReady = new Monitor.Guard(takeMonitor) {
    public boolean isSatisfied() {
        return count.get() > 0 || queueState != QueueState.OPEN;
    }
};
private final Monitor.Guard putReady = new Monitor.Guard(putMonitor) {
    public boolean isSatisfied() {
        return count.get() < capacity || queueState != QueueState.OPEN;
    }
};
```

谓词两项分别对应两类唤醒：有数据/有容量（正常流转），或 `queueState != OPEN`（关闭取代流转）。关闭状态折叠进谓词——等待者不需要被显式通知，关闭后 guard 一满足，`enterWhen` 返回，方法体自行决定结果是入队/出队还是拒绝。

### 关闭即谓词：initiateShutdown

`close()` → `stopAsync()` → `requestStop()` → `initiateShutdown()`。关闭在双锁内原子完成：

```java
private void initiateShutdown() {
    fullyLock();                     // 固定锁序 putMonitor → takeMonitor
    try {
        if (queueState == OPEN) {
            final Node<E> detached = head.next;      // 原子摘下整个链表
            remainingTask = new FutureTask<>(() -> materializeRemaining(detached));
            closeAdmission();                        // 拒绝后续阻塞调用
            head = new Node<>(null);
            last = head;
            count.set(0);
            queueState = QueueState.CLOSING;
        }
    } finally {
        fullyUnlock();               // leave 触发两侧 guard 重估，等待者醒来
    }
}
```

两个关键性质：

- **stopAsync 不遍历**。存量链表被原样摘下存进 `remainingTask`，物化推迟到首次 `remainingList()`（`FutureTask` 保证只物化一次）。所以"关闭"这个动作是 O(1) 的。
- **元素归位明确**。已入队元素进恢复列表；阻塞中 put 的元素从未入队，抛异常后归调用方。

### admission 准入词：无注册表的等待者管理

不记录等待线程，只记"有多少阻塞调用处于活跃状态"：

```java
private static final int ADMISSION_CLOSED = Integer.MIN_VALUE;
private static final int ACTIVE_CALL_MASK = Integer.MAX_VALUE;
```

- `beginBlockingCall()`：CAS 取 lease——closed 位已置 → 直接拒绝；否则活跃计数 +1。取到 lease 才允许进入阻塞。
- `endBlockingCall()`：活跃计数 -1；若 closed 位已置且活跃归零 → 发布终止。

`awaitTerminated()` 靠它保证两件事：恢复快照已固定（`remainingTask` 已可见），以及每个拿到 lease 的调用都已退出（活跃计数归零）。这取代了"枚举线程再 interrupt"的线程注册表方案——关闭不用知道谁在等，只等计数清空。

### 关闭与中断正交

两条完全独立的退出路径：

```java
// 外部 interrupt：enterWhen 可中断，InterruptedException 原样传播，中断标志正常设置
takeMonitor.enterWhen(takeReady);   // throws InterruptedException

// 关闭：guard 因 queueState != OPEN 而满足，enterWhen 正常返回，
// 方法内 allowBlockingCommit() 发现已关闭 → QueueShutdownException（不设置中断标志）
```

测试 `externalInterruptPropagatesUnchanged` 与 `shutdownReleasesAllGuardWaitersWithoutInterruptingThreads` 分别钉死这两条路径。调用方可以放心区分：收到 `InterruptedException` 说明线程真的被打断，收到 `QueueShutdownException` 说明队列已关闭。

### 锁外回调：关闭不被用户代码阻塞

凡是可能慢的用户代码都移出 monitor：

- `drainTo`：锁内只做摘除（所有权的线性化点），锁外逐个 `target.add`。若 add 抛异常，已摘除批次归 target 所有，不回灌。
- `remove(Object)`：锁内快照节点 + item，锁外跑 `equals`，再按身份重锁校验后摘除。
- 迭代器 / forEach / 倒序 addAll：同样遵守"锁内不碰业务代码"。

配合这些，测试 `blockingDrainTargetCannotDelayShutdown`、`blockingEqualsCannotDelayShutdown`、`blockingForEachCallbackCannotDelayShutdown` 证明了关闭不会被慢回调拖死。

## 示例

完整可运行代码见 `io.github.huatalk.parallelinscope.queue.ClosableQueueLifecycleDemo`：

```java
public static void main(String[] args) throws Exception {
    // 一个放满的队列：put 阻塞在 producer Guard 上
    ClosableBlockingQueue<Integer> fullQueue = new ClosableBlockingQueue<>(1);
    fullQueue.put(0);
    // 一个空队列：take 阻塞在 consumer Guard 上
    ClosableBlockingQueue<Integer> emptyQueue = new ClosableBlockingQueue<>(1);

    AtomicInteger rejected = new AtomicInteger();
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    List<Thread> waiters = Arrays.asList(
            blocked("blocked-put", rejected, unexpected, () -> {
                fullQueue.put(1);
                return null;
            }),
            blocked("blocked-take", rejected, unexpected, emptyQueue::take));

    for (Thread waiter : waiters) {
        waiter.start();
    }
    TimeUnit.MILLISECONDS.sleep(300);   // 等两个线程各自进入阻塞

    fullQueue.close();    // 关闭不中断任何线程，等待者自己醒来拒绝
    emptyQueue.close();
    System.out.println("shutdown -> fullQueue.isShutdown()=" + fullQueue.isShutdown()
            + ", emptyQueue.isShutdown()=" + emptyQueue.isShutdown());

    for (Thread waiter : waiters) {
        waiter.join(TimeUnit.SECONDS.toMillis(5));
    }
    System.out.println("blocked callers rejected: " + rejected.get()
            + " (expect " + waiters.size() + ")");

    fullQueue.awaitTerminated();
    System.out.println("state -> " + fullQueue.state());
    System.out.println("remaining after close: fullQueue=" + fullQueue.remainingList()
            + ", emptyQueue=" + emptyQueue.remainingList());

    try {
        fullQueue.offer(2);
        throw new AssertionError("offer after close should fail");
    } catch (QueueShutdownException shutdown) {
        System.out.println("post-close offer -> " + shutdown.getMessage());
    }

    // POISON 模式：关闭后的消费者返回保留对象，而不是抛异常
    Integer poison = Integer.valueOf(-1);
    ClosableBlockingQueue<Integer> poisonQueue = new ClosableBlockingQueue<>(1, poison);
    poisonQueue.put(1);
    poisonQueue.close();
    System.out.println("poison mode take -> " + poisonQueue.take());
}
```

输出：

```
shutdown -> fullQueue.isShutdown()=true, emptyQueue.isShutdown()=true
blocked callers rejected: 2 (expect 2)
state -> TERMINATED
remaining after close: fullQueue=[0], emptyQueue=[]
post-close offer -> ClosableBlockingQueue is shut down; offer is no longer accepted
poison mode take -> -1
```

两个阻塞线程一个在满队列 put、一个在空队列 take，`close()` 后都苏醒并以 `QueueShutdownException` 退出——注意没有任何 interrupt 发生。`fullQueue.remainingList()` 取回关闭前已入队的 `[0]`；关闭后 `offer` 抛异常；毒丸模式下 `take` 返回 `-1` 而非抛异常。

## 测试验证

`ClosableBlockingQueueTest` 覆盖 35 个用例，与本设计直接相关的关键项：

- `shutdownReleasesAllGuardWaitersWithoutInterruptingThreads`：关闭释放全部等待者且不中断
- `externalInterruptPropagatesUnchanged`：外部中断原样传播，与关闭正交
- `poisonObjectReturnsFromClosedConsumers`：毒丸模式
- `blockedProducerPayloadIsNotReportedAsRemaining`：阻塞 put 的元素归调用方，不进恢复列表
- `concurrentStopsAreIdempotentAndPublishRemainingOnce`：并发关闭只发布一次
- `takeVsShutdownPartitionsElementsWithoutLossOrDuplication`：关闭与 take 竞争，元素不丢不重
- `stopBeforeStartPublishesFifoRemainingListAndRejectsWrites`：未启动即关闭也能正确发布
- `blockingDrainTargetCannotDelayShutdown` / `blockingEqualsCannotDelayShutdown` / `blockingForEachCallbackCannotDelayShutdown`：关闭不被用户回调阻塞
- `serviceListenerSequenceFollowsGuavaContract`：Service 监听器时序符合 Guava 契约

> 📁 完整测试代码：[ClosableBlockingQueueTest.java](https://github.com/huatalk/parallel-in-scope/blob/main/src/test/java/io/github/huatalk/parallelinscope/queue/ClosableBlockingQueueTest.java)

## 总结

- **设计目标一句话**：把关闭做成队列一等公民——自动释放等待者、不中断线程、存量可恢复、关闭不被用户代码阻塞。
- **新契约**：关闭 ≠ 中断。被关闭释放的阻塞调用抛 unchecked `QueueShutdownException`（或返回可选毒丸）；外部 interrupt 仍抛 `InterruptedException`。两条路径互不污染。
- **关闭即谓词**：`queueState != OPEN` 折叠进双管程 guard，等待者自己"看见"关闭退出，无线程注册表。
- **admission 准入词**：closed 位 + 活跃调用计数，让 `awaitTerminated()` 能保证恢复快照固定、准入调用清零。
- **O(1) 关闭**：`stopAsync()` 原子摘下链表、延迟物化到 `remainingList()`，慢用户回调全部在锁外。

---

如果这篇文章对你有帮助，欢迎关注我，持续分享高质量技术干货，助你更快提升编程能力。

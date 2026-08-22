# 并发编程中的等待-通知模式：从 `wait/notify` 到 Guava `Monitor`

在并发程序里，很多线程并不是一直在“做事”。消费者要等队列里出现数据，生产者要等容量释放，请求线程要等异步结果，下一阶段要等上一阶段的参与者全部到达。

这些场景看起来不同，底层问题却相同：**线程要等待某个共享状态变成满足条件的状态；另一个线程改变状态后，通知等待者重新检查。**

本文先梳理等待-通知模式的常见用法，再介绍 Guava `Monitor` 如何把锁、条件和等待流程组合起来，最后讨论它的优点、限制和选型边界。

## 1. 等待的不是通知，而是条件

最容易出错的理解是把通知当成一个一次性的事件：

```text
线程 A：等待“有数据”
线程 B：通知 A
```

更准确的模型是：

```text
共享状态 + 状态谓词 + 等待/重试 + 状态提交
```

例如，消费者等待的不是“生产者发过一次通知”，而是队列谓词 `queue 非空`。生产者加入元素后，消费者被唤醒并重新检查这个谓词；如果多个消费者竞争同一个元素，某个消费者可能发现条件再次不成立，于是继续等待。

因此，通知只是“状态可能发生了变化”的提示，真正决定线程能否继续的是谓词本身。这也是为什么等待代码必须使用 `while`，而不是 `if`。

## 2. 常见的等待-通知模式

### 2.1 生产者-消费者

这是最经典的用法。

- 消费者等待：队列非空。
- 生产者等待：队列未满。
- 入队后：允许消费者重新检查。
- 出队后：允许生产者重新检查。

业务上常见于任务队列、日志缓冲区、批处理管道、网络发送队列和数据库写入队列。

优先使用 JDK 的 `BlockingQueue`：

```java
BlockingQueue<Task> queue = new ArrayBlockingQueue<>(100);

queue.put(task);       // 满时等待
Task next = queue.take(); // 空时等待
```

它已经封装了容量、等待、唤醒、中断和超时语义。只有当队列还需要生命周期状态、动态容量或特殊提交协议时，才值得自己组合锁和条件。

### 2.2 等待一次性结果或资源就绪

初始化线程发布“服务已启动”“配置已加载”“连接池已准备好”等状态，其他线程等待一次即可。

这类场景适合 `CountDownLatch`：

```java
CountDownLatch ready = new CountDownLatch(1);

// 初始化线程
loadConfiguration();
ready.countDown();

// 使用线程
ready.await();
useConfiguration();
```

`CountDownLatch` 的计数只能单向减少到零，适合一次性事件；如果需要重复使用或按阶段推进，应考虑 `CyclicBarrier` 或 `Phaser`。

### 2.3 等待多个任务完成

批量任务、并行查询和聚合请求经常需要等待一组子任务结束。可以使用 `Future.get()`、`CompletableFuture.allOf()`，或框架提供的批量结果对象。

```java
CompletableFuture<Void> all = CompletableFuture.allOf(first, second, third);
all.join();
```

这里等待的是“所有 Future 都完成”的状态，而不是某个线程手动调用的通知。异常、取消和超时也应作为完成状态的一部分设计，而不是只关注成功路径。

### 2.4 阶段同步与屏障

多线程计算常分成若干阶段：所有线程先完成读取，才能开始转换；所有线程完成转换，才能提交结果。

- `CyclicBarrier`：固定参与者在某个屏障处会合，可以重复使用。
- `Phaser`：支持动态注册和多阶段推进，适合参与者数量变化的算法。

屏障等待的是“本阶段参与者数量达到要求”，不是某一线程的单独信号。

### 2.5 等待可用许可

当并发度受到限制时，线程等待的条件是“还有可用许可”。连接池、限流器和资源槽位都属于这一类。

```java
Semaphore permits = new Semaphore(8);

permits.acquire();
try {
    callRemoteService();
} finally {
    permits.release();
}
```

`Semaphore` 比手写“当前运行数小于上限”的条件更直接，也能表达多个许可和公平获取。

### 2.6 等待线程结束

线程生命周期本身就是一种状态：目标线程从“运行中”变为“已结束”。`Thread.join()` 适合等待线程终止；在线程池或异步 API 中，则通常等待 `Future` 完成。

### 2.7 请求-响应和异步依赖

异步请求可以为每个请求保存一个 `CompletableFuture`，响应到达时完成对应 Future；下游操作通过 `thenApply`、`thenCompose` 等回调等待上游结果。

这种方式把等待关系从“线程阻塞”转换成“任务依赖”，通常比为每个请求创建一个底层条件变量更容易扩展。

## 3. 手写 `wait/notify` 的正确骨架

传统的 Java 对象监视器可以实现等待-通知，但共享状态、锁和通知时机必须严格配合：

```java
final Object lock = new Object();
final Deque<Task> queue = new ArrayDeque<>();

// 消费者
synchronized (lock) {
    while (queue.isEmpty()) {
        lock.wait();
    }
    Task task = queue.removeFirst();
    // 处理 task，或在锁外处理
}

// 生产者
synchronized (lock) {
    queue.addLast(task);
    lock.notifyAll();
}
```

必须注意以下几点：

1. `wait()`、`notify()` 和 `notifyAll()` 必须在同一个对象的监视器内调用。
2. 等待条件使用 `while`，因为可能发生虚假唤醒，也可能被其他线程先一步消费或改变状态。
3. 状态修改和通知应该在同一个临界区内完成，避免通知发生在状态发布之前。
4. `notify()` 只唤醒一个等待者；存在多个不同类型的等待者时，错误选择可能让所有线程继续睡眠，通常需要 `notifyAll()`。
5. 不要在锁内执行外部回调、网络调用或不可控的用户代码，否则通知和关闭路径都可能被拖住。

手写方式并非不能用，但随着条件数量增加，`wait/notify` 很容易变成“一个锁配一个模糊通知”的隐式协议。`Condition` 和 Guava `Monitor` 能把条件表达得更清楚。

## 4. `Condition` 的显式条件队列

使用 `ReentrantLock` 时，可以为不同的状态谓词建立独立的 `Condition`：

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition notEmpty = lock.newCondition();
private final Condition notFull = lock.newCondition();

lock.lockInterruptibly();
try {
    while (queue.isEmpty()) {
        notEmpty.await();
    }
    return queue.removeFirst();
} finally {
    lock.unlock();
}
```

生产者入队后调用 `notEmpty.signal()`，消费者出队后调用 `notFull.signal()`。相比单一的 `notifyAll()`，独立条件可以减少无关线程被唤醒的数量。

但 `Condition` 仍然要求调用者自己维护完整协议：选择哪个条件、何时 `signal`、是否需要 `signalAll`、超时如何计算、状态提交是否仍然有效，都由业务代码负责。

## 5. Guava `Monitor` 是什么

Guava `Monitor` 可以理解为“带有命名 Guard 的锁”。它把以下几个概念放在一起：

- 一把可重入锁；
- 一个描述状态谓词的 `Monitor.Guard`；
- 等待谓词满足的 `enterWhen` / `waitFor`；
- 可中断、不可中断和超时版本；
- 退出监视器时自动检查并转交满足条件的等待者。

最小用法如下：

```java
Monitor monitor = new Monitor();
AtomicReference<String> value = new AtomicReference<>();
Monitor.Guard ready = monitor.newGuard(() -> value.get() != null);

monitor.enterWhen(ready);
try {
    return value.get();
} finally {
    monitor.leave();
}
```

`Guard` 的 `BooleanSupplier` 应只读取受该 `Monitor` 保护的状态。不要让谓词读取一个没有同步关系的可变对象，否则“谓词为真”本身就没有可靠的内存可见性和一致性保证。

## 6. 用 `Monitor` 实现有界缓冲区

下面是一个精简的生产者-消费者示例：

```java
final class BoundedBuffer<E> {
    private final Monitor monitor = new Monitor();
    private final Deque<E> items = new ArrayDeque<>();
    private final int capacity;
    private final Monitor.Guard notEmpty = monitor.newGuard(
            () -> !items.isEmpty());
    private final Monitor.Guard notFull = monitor.newGuard(
            () -> items.size() < capacity);

    BoundedBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    void put(E item) throws InterruptedException {
        monitor.enterWhen(notFull);
        try {
            items.addLast(item);
        } finally {
            monitor.leave();
        }
    }

    E take() throws InterruptedException {
        monitor.enterWhen(notEmpty);
        try {
            return items.removeFirst();
        } finally {
            monitor.leave();
        }
    }
}
```

这个例子中，`put` 进入 `notFull` Guard，`take` 进入 `notEmpty` Guard。入队或出队后调用 `leave()`，`Monitor` 会根据 Guard 重新判断哪些等待者可以继续，因此不需要手动维护两组 `signal` 调用。

实际项目中还应补充 `offer`、`poll` 的超时版本：

```java
E poll(long timeout, TimeUnit unit) throws InterruptedException {
    if (!monitor.enterWhen(notEmpty, timeout, unit)) {
        return null;
    }
    try {
        return items.removeFirst();
    } finally {
        monitor.leave();
    }
}
```

如果线程已经持有 `Monitor`，可以使用 `waitFor(guard)` 等待某个 Guard；如果还没有进入，则使用 `enterWhen(guard)`。不要混淆两者的前置条件：`waitFor` 不负责替你获取初始锁。

## 7. `Monitor` 的好处

### 7.1 条件是有名字的

`notEmpty`、`notFull`、`runningOrClosed` 直接表达业务条件。阅读代码时，可以看到线程到底在等什么，而不是只看到一个没有语义的 `await()`。

### 7.2 减少通知协议的样板代码

调用者只需在改变状态后正确 `leave()`。Guava 会在退出时寻找满足条件的 Guard，减少漏写 `signal`、错写条件或唤醒错误等待队列的机会。

### 7.3 等待 API 更完整

`Monitor` 同时提供：

- 可中断的 `enterWhen` 和 `waitFor`；
- 保留中断状态的 `enterWhenUninterruptibly` 和 `waitForUninterruptibly`；
- 带 `TimeUnit` 或 `Duration` 的超时版本；
- `enterIf`、`tryEnterIf` 等不等待的条件尝试。

这让超时和中断语义可以直接写在协调层，而不是散落在业务循环中。

### 7.4 支持多个条件和可选公平性

一个 `Monitor` 可以拥有多个 Guard。构造 `new Monitor(true)` 可以启用公平模式，让等待时间较长的线程获得更强的排队保证；默认的非公平模式通常吞吐量更好。

### 7.5 适合复杂生命周期状态

例如队列的等待条件可以写成“有数据，或者队列已经关闭”：

```java
Monitor.Guard takeReady = monitor.newGuard(
        () -> !queue.isEmpty() || closed);
```

被唤醒后，方法仍然要在提交出队前检查 `closed`，决定是返回数据、抛出关闭异常，还是返回特殊结束标记。Guard 负责等待条件，不替代业务状态机。

## 8. `Monitor` 不是无条件的并发正确性

使用 `Monitor` 仍然有几个关键边界。

### 8.1 Guard 成功不等于操作已经提交

如果 Guard 只保护“选择当前状态”，而真正的提交操作还要访问另一个锁、可替换的数据结构或外部资源，那么 Guard 成功之后仍可能发生竞争：其他线程可能已经改变了状态。

这时应采用：

```text
TRY -> WAIT -> RETRY -> COMMIT
```

也就是进入 Guard 后再次验证当前代数、对象身份或容量；提交失败就释放并重试，而不是假设第一次 Guard 判断永远有效。

### 8.2 状态必须和 Monitor 建立同一同步关系

Guard 读取的字段要么在同一 Monitor 内读写，要么使用合适的原子变量或其他明确的发布协议。只把 `volatile` 字段放进 Guard，并不能自动保证一组相关字段的复合状态是一致的。

### 8.3 不要在 Monitor 内调用外部代码

集合回调、`equals`、日志处理、网络访问和 Service listener 都可能重入、阻塞或抛异常。应在 Monitor 内完成状态快照或所有权转移，然后在释放 Monitor 后执行外部代码。

### 8.4 公平性不是免费的

公平 Monitor 可以降低饥饿风险，但会增加排队和上下文切换成本。只有当等待顺序本身是契约，或者已经观察到非公平模式造成明显饥饿时，才应启用公平模式。

## 9. 如何选择

可以按下面的优先级选择工具：

| 问题 | 优先选择 |
|---|---|
| 有界任务或数据队列 | `BlockingQueue` |
| 一次性初始化完成 | `CountDownLatch` |
| 固定参与者阶段会合 | `CyclicBarrier` |
| 动态阶段参与者 | `Phaser` |
| 并发额度和资源许可 | `Semaphore` |
| 异步任务依赖和结果传播 | `CompletableFuture` / `Future` |
| 一个锁下有多个有语义的状态条件 | `Condition` 或 Guava `Monitor` |
| 需要直接控制底层线程挂起/唤醒 | `LockSupport`，并承担更低层的协议复杂度 |

`Monitor` 最适合“共享状态由一把锁保护，同时存在多个清晰等待条件”的组件，例如生命周期队列、连接池、状态机和资源协调器。它不是所有异步问题的通用替代品：如果任务可以通过回调或 Future 连接，就不必为了等待而阻塞线程。

## 10. 总结

等待-通知模式的核心不是简单的“某个线程叫醒另一个线程”，而是围绕共享状态建立可验证的协议：

1. 明确等待谓词。
2. 在同一同步边界内检查和修改状态。
3. 使用循环等待，正确处理虚假唤醒、竞争和超时。
4. 把中断、取消、关闭和失败作为正式状态处理。
5. 把外部回调放在锁外。
6. 当 Guard 判断与最终提交不在一个原子协议中时，使用重试。

`wait/notify` 是底层机制，`Condition` 提供显式条件队列，Guava `Monitor` 则进一步把“锁 + 命名谓词 + 等待 API”组织成一个更完整的协调工具。真正重要的不是选择哪一个类，而是让“线程为什么等待、什么状态会释放等待、释放后如何提交”都能在代码和测试中被清楚地说明。

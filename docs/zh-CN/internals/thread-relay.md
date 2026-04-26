# ThreadRelay 内部机制：两层 Map 如何实现上下文接力


在结构化并发中，父线程提交任务到线程池后，子线程需要感知父线程的上下文——取消令牌、执行选项、任务名称。如果每个任务都手动传递这些参数，API 会迅速膨胀。parallel-in-scope 的 `ThreadRelay` 用一种"两层 Map"设计解决了这个问题：**父级上下文复制到 `parentMap`，当前任务写入 `curMap`，两者互不覆盖**。

本文拆解这个机制的内部实现。

---

## 为什么需要跨线程上下文传播

parallel-in-scope 的核心 API 是实例方法 `Par.map()`。调用者配置 `ParOptions` 后，框架创建 `CancellationToken` 并将任务分发到线程池执行。问题在于：

```
父线程 (Thread-Main)
  ├── CancellationToken: token-A
  ├── ParOptions: parallelism=8, timeout=5s
  └── 提交任务 → 线程池
        ├── Worker-1: 需要知道 token-A（用于取消传播）
        ├── Worker-2: 需要知道 parallelism=8（用于嵌套并行）
        └── Worker-3: 需要知道任务名（用于 livelock 检测）
```

Worker 线程拿不到这些信息——它们是独立的执行实体，没有参数传递机制能自动跨越线程边界。

## ThreadLocal 的困境

Java 开发者的第一反应是 `ThreadLocal`。但 `ThreadLocal` 有一个根本限制：**它的值绑定到当前线程**。当任务从父线程提交到线程池的 Worker 线程时，Worker 线程的 `ThreadLocal` 是空的。

```java
// 父线程
ThreadLocal<CancellationToken> tokenTL = new ThreadLocal<>();
tokenTL.set(myToken);

executor.submit(() -> {
    // Worker 线程：tokenTL.get() == null
    // ThreadLocal 的值没有跟过来
});
```

阿里的 `TransmittableThreadLocal` (TTL) 解决了这个问题。它通过包装 `Runnable`/`Callable`（或使用 TTL Agent 增强线程池），在任务提交时捕获父线程的值，在 Worker 线程执行时回放。但这只是解决了"值能传过来"的问题——**如何组织这些值，让读写语义正确，才是 ThreadRelay 要解决的核心问题**。

`ThreadRelay` 负责注册和组织上下文，不会自行包装用户提供的执行器。要跨线程传播，执行器提交链路必须已通过 TTL Wrapper 或 TTL Agent 增强。

## 两层 Map 设计

`ThreadRelay` 的结构非常简洁：

```java
public class ThreadRelay {

    public enum RelayItem {
        CANCELLATION_TOKEN,
        PARALLEL_OPTIONS,
        TASK_NAME,
        EXECUTOR_NAME
    }

    private static final ThreadLocal<ThreadRelay> THREAD_RELAY_TL =
            ThreadLocal.withInitial(ThreadRelay::new);

    private final Map<RelayItem, Object> parentMap = new ConcurrentHashMap<>();
    private final Map<RelayItem, Object> curMap = new ConcurrentHashMap<>();

    public ThreadRelay() {}

    public ThreadRelay(Map<RelayItem, Object> parentContext) {
        if (parentContext != null) {
            this.parentMap.putAll(parentContext);
        }
    }
}
```

两个 Map 各有明确的职责：

### parentMap —— 只读的父上下文

`parentMap` 存储的是**父线程的上下文快照**。当 TTL 将父线程的 `ThreadRelay` 传播到子线程时，父线程的 `curMap` 成为子线程的 `parentMap`。

```java
// 父线程写入
ThreadRelay.setCurrentCancellationToken(token);
// → token 存入父线程 relay 的 curMap

// TTL 传播时，创建子线程的 ThreadRelay：
new ThreadRelay(parent.curMap)
// → parent.curMap 的内容被 putAll 到子线程 relay 的 parentMap
```

读取父上下文时，直接查 `parentMap`：

```java
public static CancellationToken getParentCancellationToken() {
    ThreadRelay relay = THREAD_RELAY_TL.get();
    if (relay == null) return null;
    Object token = relay.parentMap.get(RelayItem.CANCELLATION_TOKEN);
    return token instanceof CancellationToken ? (CancellationToken) token : null;
}
```

注意方法名是 `getParent`——语义上明确表示"读的是父级传下来的值"。

### curMap —— 当前任务的本地上下文

`curMap` 存储的是**当前任务自己设置的值**。写入操作全部指向 `curMap`：

```java
public static void setCurrentCancellationToken(CancellationToken token) {
    if (token == null) return;
    ThreadRelay relay = THREAD_RELAY_TL.get();
    if (relay != null) {
        relay.curMap.put(RelayItem.CANCELLATION_TOKEN, token);
    }
}
```

`ScopedCallable.call()` 在任务执行前初始化 `curMap`：

```java
// ScopedCallable.call() 中：
ThreadRelay.setCurrentCancellationToken(currentToken);
ThreadRelay.setCurrentParallelOptions(currentOptions);
ThreadRelay.setCurrentTaskName(taskName);
ThreadRelay.setCurrentExecutorName(getExecutorName());
```

## 数据流：从父线程到子线程

完整的传播流程如下：

```
父线程 (Thread-Main)
  ThreadRelay {
    parentMap: {}                              ← 空，主线程没有父级
    curMap:    { CANCELLATION_TOKEN: token-A,
                PARALLEL_OPTIONS:  opts-1,
                TASK_NAME:         "batch" }
  }
      │
      │  TTL 捕获：取 relay.curMap
      ▼
  ┌─────────────────────────────────────┐
  │  TTL Transmitter                    │
  │  captured = parentRelay.curMap      │
  └─────────────────────────────────────┘
      │
      │  Worker 线程执行前，TTL 回放
      ▼
Worker 线程
  ThreadRelay = new ThreadRelay(captured)
  {
    parentMap: { CANCELLATION_TOKEN: token-A,   ← 从父级 curMap 复制来
                 PARALLEL_OPTIONS:  opts-1,
                 TASK_NAME:         "batch" }
    curMap:    {}                               ← 当前任务的空画布
  }
      │
      │  ScopedCallable.call() 设置 curMap
      ▼
  {
    parentMap: { CANCELLATION_TOKEN: token-A,   ← 父级上下文（只读参考）
                 PARALLEL_OPTIONS:  opts-1,
                 TASK_NAME:         "batch" }
    curMap:    { CANCELLATION_TOKEN: token-B,   ← 当前任务自己的 token
                 PARALLEL_OPTIONS:  opts-2,     ← 可能被嵌套任务修改
                 TASK_NAME:         "subtask" }
  }
```

关键点：**子任务把自己的值写入 `curMap`，不会修改 `parentMap` 中继承的值**。父级与当前任务的上下文保持独立，可由对应的 `getParent*` 和当前上下文 API 分别读取。

## 复制的是条目，共享的是值对象

`parentMap.putAll(parentContext)` 会复制 Map 条目，但不会深拷贝 `CancellationToken`、`ParOptions` 等值对象；`parentMap` 和父线程的 `curMap` 持有相同的对象引用。

```
父线程 curMap                         子线程 parentMap
┌──────────────────────┐             ┌──────────────────────┐
│ CANCELLATION_TOKEN ───┼──── ─── ───┼── CANCELLATION_TOKEN  │
│      = 0xABCD (ref)  │             │      = 0xABCD (ref)  │
└──────────────────────┘             └──────────────────────┘
                    同一个对象引用
```

这意味着：
- `CancellationToken.cancel()` 在父线程调用，子线程通过 `parentMap` 拿到的同一个 token 立即可见取消状态
- 不存在序列化/反序列化开销
- 不存在深拷贝带来的内存翻倍

对于 `ConcurrentHashMap` 中存储的不可变或线程安全对象（`CancellationToken` 内部以 `AtomicReference` 保存状态，`ParOptions` 是不可变对象），共享引用是安全的。

## 与 TTL 的配合

ThreadRelay 本身**不继承** `TransmittableThreadLocal`。它通过 TTL 的 `registerThreadLocal` API 注册了一个普通的 `ThreadLocal`：

```java
private static final ThreadLocal<ThreadRelay> THREAD_RELAY_TL =
        ThreadLocal.withInitial(ThreadRelay::new);

static {
    TransmittableThreadLocal.Transmitter.registerThreadLocal(
            THREAD_RELAY_TL, tr -> new ThreadRelay(tr.curMap));
}
```

`registerThreadLocal` 的第二个参数是**捕获函数**：当任务提交到线程池时，TTL 调用这个函数，传入当前线程的 `ThreadRelay` 实例（`tr`），返回一个新实例。这里 `new ThreadRelay(tr.curMap)` 就是把父线程的 `curMap` 作为子线程的 `parentContext` 传入。

为什么不直接继承 TTL？因为 TTL 的传播语义是"整个值被替换"——子线程会拿到父线程值的副本。而 ThreadRelay 需要的是"父级值作为只读参考，子级有自己的可写空间"。两层 Map 设计正好满足这个需求，`registerThreadLocal` 提供了足够的灵活性来实现它。

## CancellationToken 传播：一个完整案例

`CancellationToken` 的跨线程传播是 ThreadRelay 最典型的应用场景：

```java
// 测试用例展示了完整的传播链路
@Test
public void testCancellationToken_propagatesAcrossThreads() throws Exception {
    CancellationToken token = CancellationToken.create();
    ThreadRelay.setCurrentCancellationToken(token);  // ① 父线程写入 curMap

    AtomicReference<CancellationToken> tokenInChild = new AtomicReference<>();

    Runnable task = TtlRunnable.get(() -> {
        // ③ 子线程从 parentMap 读取（TTL 回放后，parentMap = 父线程的 curMap）
        tokenInChild.set(ThreadRelay.getParentCancellationToken());
    });

    executor.submit(task);  // ② TTL 捕获父线程 ThreadRelay

    assertThat(tokenInChild.get()).isSameAs(token);  // 同一个对象
}
```

当父线程调用 `token.cancel()` 时，子线程中正在执行的任务可以通过同一个 token 实例感知到取消请求，实现协作式取消。

## TaskScopeTl vs ThreadRelay：两种上下文的分工

parallel-in-scope 有两套上下文存储，用途不同：

| 维度 | `TaskScopeTl` | `ThreadRelay` |
|------|--------------|---------------|
| 传播性 | 不传播（普通 `ThreadLocal`） | 通过 TTL 增强的提交链路传播 |
| 用途 | 当前任务执行期间的临时状态 | 跨线程的上下文接力 |
| 清理 | `finally` 块中 `remove()` | 由 TTL 在任务回放结束后恢复线程原有值 |
| 典型内容 | 当前任务的 token、options | 父级传下的 token、任务名 |

`ScopedCallable.call()` 中两者同时初始化：

```java
// ScopedCallable.call()
TaskScopeTl.init(currentToken, currentOptions);    // 任务级，不传播
ThreadRelay.setCurrentCancellationToken(currentToken);  // 可被子任务继承
```

如果子任务嵌套提交新的并行任务，子任务的 `curMap` 会成为孙任务的 `parentMap`——链条自动延伸。

## 设计取舍

**选择 `ConcurrentHashMap` 而非普通 `HashMap`**：`RelayItem` 枚举只有 4 个值，理论上用 `EnumMap` 更紧凑；当前实现选择了线程安全的 `ConcurrentHashMap`。

**`putAll` 的开销**：每次 TTL 传播最多复制 4 个条目，不涉及值对象的深拷贝。

**不提供 `getParent*` 的穿透查询**：`getParentCancellationToken()` 只查 `parentMap`，不回退到 `curMap`。这保持了语义清晰——"parent"就是 parent，不会意外读到自己设置的值。

**`curMap` 不修改 `parentMap`**：子任务把自己的值写入 `curMap`，不会修改继承的 `parentMap`。每次 TTL 捕获都会创建新的 `ThreadRelay` 并浅拷贝父线程的当前条目，因此兄弟任务各自持有独立的 Map 快照。

---

> 📁 相关源码：[ThreadRelay.java](https://github.com/huatalk/parallel-in-scope/blob/main/src/main/java/io/github/huatalk/parallelinscope/context/ThreadRelay.java)

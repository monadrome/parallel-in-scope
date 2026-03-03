# 线程池循环调用死锁 Demo 分析

## 场景描述

使用**同一个固定大小线程池**（4 线程），嵌套调用形成 A → B → A 的循环依赖，导致线程池死锁。

```
shared-pool (FixedThreadPool, size=4)

task-A forEach [1,2,3,4]       ← 占满 4 个线程
  └── task-B map [x,y]          ← 需要线程，被阻塞
        └── task-A-inner map [1,2]  ← 需要线程，永远排不上
```

## 死锁原理

```
shared-pool (4 threads)
├── thread-1: task-A-1 → 调 task-B → 等待 pool 线程 (blocked)
├── thread-2: task-A-2 → 调 task-B → 等待 pool 线程 (blocked)
├── thread-3: task-A-3 → 调 task-B → 等待 pool 线程 (blocked)
└── thread-4: task-A-4 → 调 task-B → 等待 pool 线程 (blocked)
                                       ↑
                          所有线程都被占满，新任务永远排不上
```

1. task-A 的 parallelism=4，刚好占满线程池全部 4 个线程
2. 每个 task-A 子任务内部调用 task-B，task-B 需要向**同一个线程池**提交任务
3. task-B 的子任务 task-A-inner 又需要线程池线程
4. 但 4 个线程都被 task-A 占住，在等 task-B 完成才会释放
5. task-B 在等线程池有空闲线程才能运行 → **循环等待，死锁**

## TaskGraph 检测结果

| 检测项 | 结果 | 说明 |
|---|---|---|
| Task cycle | false | A → B → A-inner 任务名不同，不构成任务级环 |
| Task self-loop | false | 没有任务自己调自己 |
| **Executor cycle** | **true** | shared-pool → shared-pool 构成执行器级环 |
| **Executor self-loop** | **true** | 同一个 pool 自己提交任务到自己 |

### 任务依赖图

```
任务级:   NA ──→ task-A ──→ task-B ──→ task-A-inner
执行器级: NA ──→ shared-pool ──→ shared-pool (self-loop!)
```

### 边信息

```
task-A:       {p=4, type=IO_BOUND, src=NA,          exec=shared-pool, count=4, timeout=5000ms}
task-B:       {p=2, type=IO_BOUND, src=shared-pool,  exec=shared-pool, count=2, timeout=5000ms}
task-A-inner: {p=2, type=IO_BOUND, src=shared-pool,  exec=shared-pool, count=2, timeout=5000ms}
```

## 检测原理

`TaskGraph` 在请求结束时（`destroyAfterRequest`）构建两层有向图：

1. **任务级图**：以 taskName 为节点，检测任务间循环依赖
2. **执行器级图**：以 executorName 为节点，将任务级边折叠到执行器维度，**过滤掉不会死锁的执行器**（如 CachedThreadPool）

关键判断 `canDeadlock()`：
- `SynchronousQueue` 队列 → 不会死锁（CachedThreadPool 模式，总能创建新线程）
- `maximumPoolSize == MAX_VALUE` → 不会死锁（无上限线程）
- 其他（FixedThreadPool 等有界线程池）→ **有死锁风险**

使用 Guava `Graphs.hasCycle()` 检测环，`self-loop` 检测自环（同一 executor 嵌套调用自己）。

## 解决方案

| 方案 | 做法 | 适用场景 |
|---|---|---|
| **拆分线程池** | 内外层使用不同的线程池 | 最推荐，彻底隔离 |
| **增大线程池** | 确保线程数 > 所有嵌套层总并发数 | 简单但不彻底 |
| **CallerRunsPolicy** | 线程池满时由调用线程执行 | 可缓解但不根治 |
| **CachedThreadPool** | 使用无界线程池 | 框架自动排除检测（canDeadlock=false） |

## 运行方式

```bash
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -pl vformation-demo exec:java \
    -Dexec.mainClass="io.github.huatalk.vformation.demo.DeadlockDemo"
```

# Structured Parallel

> **⚠️ 项目状态：开发中（Pre-release）**
>
> 本项目仍在积极开发中，API 可能会发生变化。目前尚未发布到 Maven Central，正在收集社区反馈以完善设计，待稳定后发布正式版本。
>
> 如需使用，请 fork 本仓库后自行构建：
> ```bash
> git clone https://github.com/<your-fork>/structured-parallel.git
> cd structured-parallel
> mvn clean install -DskipTests
> ```
> 欢迎通过 Issue 提交反馈和建议。

**基于 Guava ListenableFuture 和 TransmittableThreadLocal 的结构化并发工具包**

专为 Java 8+ 设计，提供协作取消、活锁检测、滑动窗口并发控制、任务感知调度和可插拔监控。

---

## 特性列表

### 1. 结构化并行执行

- **`ParallelHelper.parForEach`** — 对集合元素并行执行 Consumer 操作
- **`ParallelHelper.parMap`** — 对集合元素并行执行 Function 映射，返回结果列表
- 自动规范化并行参数（并发度不超过任务数，自动填充默认超时）
- 空集合快速返回，零开销

### 2. 协作式取消（Cooperative Cancellation）

- **`CancellationToken`** — 基于 `AtomicReference<CancellationTokenState>` 的状态机
- 支持 6 种取消状态：`RUNNING` / `SUCCESS` / `TIMEOUT_CANCELED` / `FAIL_FAST_CANCELED` / `MUTUAL_CANCELED` / `PROPAGATING_CANCELED`
- **父子令牌链**：父任务取消时自动级联到子任务
- **Late Binding 模式**：任务提交后延迟绑定 timeout、fail-fast 逻辑
- **`Checkpoints`** — 在任务中设置协作式检查点，配合 CancellationToken 抛出取消异常

### 3. 取消异常体系

- **`LeanCancellationException`** — 轻量取消异常，**不捕获堆栈**（`stackTrace.length == 0`），适用于高频取消场景
- **`FatCancellationException`** — 完整取消异常，保留堆栈信息，用于诊断分析

### 4. 滑动窗口并发控制

- **`ConcurrentLimitExecutor`** — 基于 `ExecutorCompletionService` 的滑动窗口执行器
- 先提交 `parallelism` 数量的初始批次
- 使用阻塞队列检测完成事件，空出槽位后逐步提交剩余任务
- CPU_BOUND 任务被拒绝时自动降级到 `directExecutor()` 同步执行

### 5. 任务类型感知调度

- **`TaskType`** — 区分 `CPU_BOUND` 和 `IO_BOUND` 任务
- **`SmartBlockingQueue`** — 任务类型感知的阻塞队列
  - `CPU_BOUND` + `rejectEnqueue=true` 时直接拒绝入队（返回 `false`），避免线程饥饿
  - `IO_BOUND` 任务正常排队等待
- **`ParallelOptions`** — 丰富的任务配置，支持 Builder 模式
  - 任务名称、并行度、超时时间、任务类型、优先级
  - 快捷工厂方法：`ioTask()`, `cpuTask()`, `criticalIoTask()`

### 6. 跨线程上下文传播

- **`ThreadRelay`** — 基于 TransmittableThreadLocal 的两级 Map 接力设计
  - 父线程的 `curMap` 自动成为子线程的 `parentMap`
  - 传播内容：CancellationToken、ParallelOptions、TaskName
- **`TaskScopeTl`** — 当前任务作用域的 ThreadLocal 绑定

### 7. 活锁/死锁检测

- **`TaskGraph`** — 基于 TransmittableThreadLocal 的任务依赖图
- 使用 Guava `Graph` API 构建有向图，`Graphs.hasCycle()` 检测环路
- 双层检测：
  - **任务级**：检测任务间的循环依赖
  - **执行器级**：检测线程池间的循环依赖（同一个线程池内的嵌套提交 = 自环 = 潜在死锁）
- 通过 `LivelockListener` SPI 接收检测事件

### 8. SPI 可插拔架构

所有外部依赖通过 SPI 接口解耦，无任何硬编码业务依赖：

| SPI 接口 | 用途 | 注册方式 |
|-----------|------|----------|
| `TaskListener` | 任务生命周期回调（耗时、排队时间、异常） | `StructuredParallel.addTaskListener()` |
| `ExecutorResolver` | 线程池解析（按名称查找 ThreadPoolExecutor） | `StructuredParallel.setExecutorResolver()` |
| `LivelockListener` | 活锁检测事件通知 | `StructuredParallel.addLivelockListener()` |
| `ParallelLogger` | 框架内部日志输出（默认 JUL，用户可桥接到 SLF4J/Log4j2） | `StructuredParallel.setLogger()` |

### 9. 全生命周期任务包装

- **`ScopedCallable`** — 核心任务包装器，提供完整的执行生命周期：
  - 上下文建立（TaskScopeTl、ThreadRelay 初始化）
  - 协作式取消检查点
  - 精确计时（submitTime → startTime → endTime）
  - SPI 回调通知
  - 上下文清理
- 实现 `Attachable` 接口，支持通过 `ConcurrentHashMap` 在提交和执行阶段之间传递上下文

### 10. 批量结果管理

- **`AsyncBatchResult`** — 批量 Future 结果容器
  - 封装所有子任务的 `ListenableFuture` 列表
  - 提供 `report()` 方法统计成功/失败/取消/运行中数量
  - 管理 `submitCanceller` 用于取消后续提交
- **`FutureInspector`** — Future 状态检测工具
  - 非阻塞查询 Future 状态：`SUCCESS` / `FAILED` / `CANCELED` / `RUNNING`
  - 安全提取异常：`exceptionNow()`

### 11. 线程池清理服务

- **`PurgeService`** — 周期性清理线程池中的已取消任务引用
- 超时触发时自动清理对应线程池
- 可配置清理间隔和最小池大小阈值

### 12. 执行器适配

- **`ListeningExecutorAdapter`** — 将普通 `ExecutorService` / `ScheduledExecutorService` 适配为 Guava `ListeningExecutorService` / `ListeningScheduledExecutorService`

### 13. 动态容量队列

- **`VariableLinkedBlockingQueue`** — 支持运行时动态调整容量的 `LinkedBlockingQueue`
  - 基于双锁算法（putLock / takeLock）
  - `setCapacity()` 运行时安全调整队列容量

---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>io.github.linzee1</groupId>
    <artifactId>structured-parallel</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 基本用法

```java
import io.github.linzee1.parallel.*;
import com.google.common.util.concurrent.*;

// 创建线程池
ListeningExecutorService executor = MoreExecutors.listeningDecorator(
    Executors.newFixedThreadPool(10));

// 配置任务参数
ParallelOptions options = ParallelOptions.ioTask("fetchData")
    .parallelism(5)
    .timeout(3000)  // 3秒超时
    .build();

// 并行执行
List<String> urls = Arrays.asList("url1", "url2", "url3", "url4", "url5");
AsyncBatchResult<String> result = ParallelHelper.parMap(
    urls,
    url -> httpClient.fetch(url),  // 你的业务逻辑
    options,
    executor
);

// 获取结果
List<ListenableFuture<String>> futures = result.getResults();
```

### 注册监控回调

```java
// 注册任务耗时监控
StructuredParallel.addTaskListener(event -> {
    System.out.printf("Task [%s] completed in %dms (waited %dms in queue)%n",
        event.getTaskName(),
        event.executionTimeMillis(),
        event.waitTimeMillis());

    if (event.getException() != null) {
        System.err.println("Task failed: " + event.getException().getMessage());
    }
});
```

### 活锁检测

```java
// 启用活锁检测
StructuredParallel.setLivelockDetectionEnabled(true);

// 注册活锁监听器
StructuredParallel.addLivelockListener(event -> {
    if (event.isExecutorSelfLoop()) {
        log.warn("Potential deadlock: executor self-loop detected! {}",
            event.getExecutorEdges());
    }
});

// 提供任务到线程池的映射关系
StructuredParallel.setExecutorResolver(new ExecutorResolver() {
    @Override
    public ThreadPoolExecutor resolveThreadPool(String name) {
        return executorMap.get(name);
    }

    @Override
    public Map<String, String> getTaskToExecutorMapping() {
        return taskToPoolMapping;  // e.g., {"fetchPrice": "io-pool", "calculate": "cpu-pool"}
    }
});

// 在请求入口初始化
TaskGraph.initOnRequest();
try {
    // ... 执行业务逻辑，期间所有 ParallelHelper 调用会自动记录依赖关系
} finally {
    // 请求结束时自动检测并通知
    TaskGraph.destroyAfterRequest();
}
```

### 协作式取消

```java
// 父任务中
CancellationToken parentToken = CancellationToken.create();
CancellationToken childToken = new CancellationToken(parentToken);

// 取消父任务 → 自动级联到子任务
parentToken.cancel(false);
// childToken 状态也会变为 PROPAGATING_CANCELED

// 在子任务代码中设置检查点
Checkpoints.checkpoint("myTask", true);  // 如果已取消，抛出 LeanCancellationException
```

### CPU-Bound 任务调度

```java
// CPU 密集型任务：拒绝入队，宁可同步执行也不阻塞工作线程
ParallelOptions cpuOptions = ParallelOptions.cpuTask("compute")
    .parallelism(Runtime.getRuntime().availableProcessors())
    .build();

// IO 密集型任务：允许入队等待
ParallelOptions ioOptions = ParallelOptions.ioTask("fetchRemote")
    .parallelism(20)
    .timeout(5000)
    .build();
```

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    ParallelHelper                        │
│              (parForEach / parMap Facade)                │
├─────────────────────────────────────────────────────────┤
│  ParallelOptions    │  ConcurrentLimitExecutor           │
│  (Task Config)      │  (Sliding Window Scheduler)        │
├─────────────────────┼───────────────────────────────────┤
│         ScopedCallable (Lifecycle Wrapper)                │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐              │
│  │ Context  │  │Checkpoint│  │  Metrics   │              │
│  │ Setup    │  │  Check   │  │  (SPI)     │              │
│  └─────────┘  └──────────┘  └────────────┘              │
├─────────────────────────────────────────────────────────┤
│  CancellationToken          │  ThreadRelay (TTL)         │
│  (Cooperative Cancel)       │  (Context Propagation)     │
├─────────────────────────────┼───────────────────────────┤
│  TaskGraph                  │  PurgeService              │
│  (Livelock Detection)       │  (Pool Cleanup)            │
├─────────────────────────────────────────────────────────┤
│                    SPI Layer                              │
│  TaskListener │ ExecutorResolver │ LivelockListener       │
└─────────────────────────────────────────────────────────┘
│          Guava ListenableFuture + TTL                    │
└─────────────────────────────────────────────────────────┘
```

---

## 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Guava | 33.2.1-jre | ListenableFuture, FluentFuture, Graph API |
| TransmittableThreadLocal | 2.14.5 | 跨线程上下文传播 |

---

## 包结构

```
io.github.linzee1.parallel
├── ParallelHelper              # 主入口门面
├── ParallelOptions             # 任务配置 (Builder)
├── StructuredParallel          # 中央配置与 SPI 注册中心
├── CancellationToken           # 协作式取消令牌
├── CancellationTokenState      # 取消状态枚举
├── Checkpoints                 # 协作式检查点
├── ScopedCallable              # 任务生命周期包装器
├── ConcurrentLimitExecutor     # 滑动窗口并发控制
├── AsyncBatchResult            # 批量结果容器
├── FutureInspector             # Future 状态检测工具
├── TaskGraph                   # 活锁/死锁检测图
├── ThreadRelay                 # 跨线程上下文接力 (TTL)
├── TaskScopeTl                 # 任务作用域 ThreadLocal
├── TaskType                    # 任务类型枚举 (CPU/IO)
├── Attachable                  # 键值对附件接口
├── ListeningExecutorAdapter    # 执行器适配器
├── PurgeService                # 线程池清理服务
├── spi/
│   ├── TaskListener            # 任务监控回调 SPI
│   ├── ExecutorResolver        # 线程池解析 SPI
│   ├── LivelockListener        # 活锁检测回调 SPI
│   └── ParallelLogger          # 日志输出 SPI（默认 JUL）
├── exception/
│   ├── LeanCancellationException   # 轻量取消异常(无堆栈)
│   └── FatCancellationException    # 完整取消异常(有堆栈)
└── queue/
    ├── SmartBlockingQueue          # 任务类型感知队列
    └── VariableLinkedBlockingQueue # 动态容量阻塞队列
```

---

## 构建

```bash
# 编译
mvn clean compile

# 运行测试
mvn test

# 打包
mvn clean package
```

---

## License

Apache License 2.0

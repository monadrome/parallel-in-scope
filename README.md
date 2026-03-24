# 🪿 VFormation（雁阵）

> **⚠️ 项目状态：开发中（Pre-release）**
>
> 本项目仍在积极开发中，API 可能会发生变化。欢迎通过 Issue 提交反馈和建议。

🪿 **VFormation**（雁阵）是一个 Java 8+ 结构化并发工具包，将现代结构化并发思想——协作式取消、快速失败、死锁检测、上下文传播——带入 Java 8 生态。🛡️🚀🎯

正如大雁以 V 字阵型分担飞行阻力，雁阵通过协作式取消、快速失败、死锁检测、上下文传播和滑动窗口调度来编排你的并行任务——**失败即止，取消级联，死锁可见**。

---

## 核心特性

- **🛡️ Cooperative Cancellation** — 父子令牌级联，Late-Binding 避免竞态，轻量异常零堆栈开销
- **⚡ Fail-Fast Only** — 任一子任务失败立即取消同批剩余任务。这是刻意的设计选择：框架只提供 fail-fast 语义，不提供"忽略失败继续执行"模式。如需容错，请在任务内部自行 catch 异常
- **🔍 Deadlock Detection** — DAG 环路检测，覆盖任务级循环依赖和执行器级自环
- **🔗 Context Propagation** — 两级 Map 接力，取消令牌、任务配置自动传播到子线程
- **🚀 Sliding-Window Scheduling** — 完成一个补一个，不淹没线程池
- **🎯 Task-Type-Aware Dispatch** — CPU 密集型拒绝入队防饥饿，IO 密集型正常排队
- **🔌 Pluggable SPI** — TaskListener / ExecutorResolver / LivelockListener

---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>io.github.huatalk</groupId>
    <artifactId>vformation</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 基本用法

```java
import io.github.huatalk.vformation.scope.*;
import com.google.common.util.concurrent.*;

// 创建配置与 Par 实例
ParConfig config = new ParConfig();
Par par = new Par(config);

// 创建并注册线程池
ExecutorService executor = Executors.newFixedThreadPool(10);
config.registerExecutor("io-pool", executor);

// 配置任务参数
ParOptions options = ParOptions.ioTask("fetchData")
    .parallelism(5)
    .timeout(3000)  // 3秒超时
    .build();

// 并行执行
List<String> urls = Arrays.asList("url1", "url2", "url3", "url4", "url5");
AsyncBatchResult<String> result = par.parMap(
    "io-pool",                      // 注册的执行器名称
    urls,
    url -> httpClient.fetch(url),   // 你的业务逻辑
    options
);

// 获取结果
List<ListenableFuture<String>> futures = result.getResults();
```

### 注册监控回调

```java
ParConfig config = new ParConfig();

// 注册任务耗时监控
config.addTaskListener(event -> {
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
ParConfig config = new ParConfig();

// 启用活锁检测
config.setLivelockDetectionEnabled(true);

// 注册活锁监听器
config.addLivelockListener(event -> {
    if (event.hasExecutorSelfLoop()) {
        log.warn("Potential deadlock: executor self-loop detected! {}",
            event.getExecutorEdges());
    }
});

// 提供任务到线程池的映射关系
config.setExecutorResolver(new ExecutorResolver() {
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
    // ... 执行业务逻辑，期间所有 Par 调用会自动记录依赖关系
} finally {
    // 请求结束时自动检测并通知
    TaskGraph.destroyAfterRequest(config);
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
ParOptions cpuOptions = ParOptions.cpuTask("compute")
    .parallelism(Runtime.getRuntime().availableProcessors())
    .build();

// IO 密集型任务：允许入队等待
ParOptions ioOptions = ParOptions.ioTask("fetchRemote")
    .parallelism(20)
    .timeout(5000)
    .build();
```

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                        Par                                │
│              (parForEach / parMap Facade)                │
├─────────────────────────────────────────────────────────┤
│  ParOptions           │  ConcurrentLimitExecutor           │
│  (Task Config)        │  (Sliding Window Scheduler)        │
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
│                                                           │
│  Logging: java.util.logging (JUL)                        │
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
io.github.huatalk.vformation
├── scope/
│   ├── Par                          # 主入口门面
│   ├── ParOptions                   # 任务配置 (Builder)
│   ├── ParConfig                    # 中央配置与 SPI 注册中心
│   ├── AsyncBatchResult            # 批量结果容器
│   └── TaskType                    # 任务类型枚举 (CPU/IO)
├── cancel/
│   ├── CancellationToken           # 协作式取消令牌
│   ├── CancellationTokenState      # 取消状态枚举
│   ├── Checkpoints                 # 协作式检查点
│   ├── PurgeService                # 线程池清理服务
│   ├── LeanCancellationException   # 轻量取消异常(无堆栈)
│   └── FatCancellationException    # 完整取消异常(有堆栈)
├── context/
│   ├── ThreadRelay                 # 跨线程上下文接力 (TTL)
│   ├── TaskScopeTl                 # 任务作用域 ThreadLocal
│   └── graph/
│       ├── TaskGraph               # 活锁/死锁检测图
│       ├── TaskEdge                # 任务依赖边
│       └── TaskEdgeEntry           # 边条目
├── internal/
│   ├── ConcurrentLimitExecutor     # 滑动窗口并发控制
│   ├── ScopedCallable              # 任务生命周期包装器
│   ├── Attachable                  # 键值对附件接口
│   └── ListeningExecutorAdapter    # 执行器适配器
├── queue/
│   ├── SmartBlockingQueue          # 任务类型感知队列
│   └── VariableLinkedBlockingQueue # 动态容量阻塞队列
└── spi/
    ├── TaskListener                # 任务监控回调 SPI
    ├── ExecutorResolver            # 线程池解析 SPI
    └── LivelockListener            # 活锁检测回调 SPI
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

## Context

vformation 框架通过 `ThreadRelay`（Two-Map TTL 模式）和 `TaskScopeTl`（Plain ThreadLocal）实现了框架内部上下文（CancellationToken、ParOptions、taskName、executorName）的跨线程传播。`ScopedCallable.call()` 在执行前设置上下文、执行后清理上下文。

当前存在两个架构问题：

1. **上下文传递路径分散**：`ThreadRelay` 使用 `TransmittableThreadLocal.withInitialAndCopier()` 直接创建 TTL 实例，而 `ScopedCallable` 通过实现 `TtlAttachments` 接口 + `ConcurrentHashMap` 传递 CancellationToken/ParOptions/executorName。两套机制并行，内层 Callable 无法感知外层 `ScopedCallable`。
2. **TTL 集成模式不统一**：框架直接继承 `TransmittableThreadLocal`，而 TTL 推荐的第三方库集成方式是 `Transmitter.registerThreadLocal(tl, copier)` — 注册 plain `ThreadLocal` 参与 Transmitter 的 capture/replay/restore 生命周期。用户扩展自定义上下文传播也应使用同样的模式。

## Goals / Non-Goals

**Goals:**
- 将 `ThreadRelay` 从 `TransmittableThreadLocal` 改为 plain `ThreadLocal` + `Transmitter.registerThreadLocal(tl, copier)` 注册模式
- 通过 ThreadLocal 暴露当前外层 `ScopedCallable` 引用，让内层 Callable 可通过 TL 直接获取 attachments
- 移除 `ScopedCallable` 的 `TtlAttachments` 实现，简化上下文获取路径

**Non-Goals:**
- 不提供框架层面的 ContextHook SPI — 用户自定义上下文传播通过 TTL 原生的 `Transmitter.registerThreadLocal` 机制实现
- 不改变 `TaskScopeTl` 的设计或生命周期
- 不改变 Two-Map（parentMap/curMap）的传播语义

## Decisions

### Decision 1: ThreadRelay 改用 Transmitter.registerThreadLocal

**选择：** 将 `ThreadRelay` 的 `THREAD_RELAY_TTL` 从：
```java
private static final TransmittableThreadLocal<ThreadRelay> THREAD_RELAY_TTL =
    TransmittableThreadLocal.withInitialAndCopier(
        ThreadRelay::new, tr -> new ThreadRelay(tr.curMap));
```
改为：
```java
private static final ThreadLocal<ThreadRelay> THREAD_RELAY_TL = ThreadLocal.withInitial(ThreadRelay::new);

static {
    TransmittableThreadLocal.Transmitter.registerThreadLocal(
        THREAD_RELAY_TL, tr -> new ThreadRelay(tr.curMap));
}
```

**理由：**
- `Transmitter.registerThreadLocal` 是 TTL 框架推荐的第三方库集成方式，让非 TTL 类型的 ThreadLocal 也能参与 Transmitter 的 capture/replay/restore 生命周期
- 与用户自行通过 `Transmitter.registerThreadLocal` 扩展自定义上下文传播保持一致的集成模式
- 行为语义不变：copier 函数 `tr -> new ThreadRelay(tr.curMap)` 保持 Two-Map 传播逻辑

**替代方案：** 保持 `TransmittableThreadLocal` 不变。但这与 TTL 推荐的第三方库集成方式不一致。

### Decision 2: 通过 ThreadLocal 暴露外层 ScopedCallable

**选择：** 在 `ScopedCallable` 中新增一个 static `ThreadLocal<ScopedCallable<?>>` 字段。在 `call()` 方法的 prepareContext 阶段设置 `this`，在 finally 阶段 remove。`ScopedCallable` 不再需要实现 `TtlAttachments` 接口，attachments 改为普通实例字段（保留 getter）。

**效果：** 内层 Callable（用户代码）可以通过 `ScopedCallable.current()` 获取外层 `ScopedCallable` 的引用，从而读取 CancellationToken、ParOptions、executorName 等 attachments。

**理由：**
- 消除了 `TtlAttachments` 的 `ConcurrentHashMap` 开销（不再需要字符串 key 查找）
- `Par.executeParallel()` 中设置 attachment 的代码可简化为直接设置 ScopedCallable 的字段
- 内层代码获取上下文的路径从 ThreadRelay + TtlAttachments 两条路统一为 ThreadLocal 一条路

**替代方案：** 将 ScopedCallable 引用放在 `ThreadRelay.curMap` 中。但这混淆了 ThreadRelay 的跨线程传播语义（ScopedCallable 是线程本地的，不应被传播到子线程）。

### Decision 3: 不引入 ContextHook SPI

**选择：** 不在框架层面提供自定义的 ContextHook SPI 接口。

**理由：** TTL 的 `Transmitter.registerThreadLocal(tl, copier)` 已经是一个成熟的上下文扩展机制。用户只需将自己的 plain `ThreadLocal`（如 MDC context、traceId 等）通过该 API 注册，即可自动参与跨线程传播。框架再封装一层 hook 是不必要的抽象，增加了学习成本和维护负担。

## Risks / Trade-offs

- **[ThreadRelay 重构影响面]** → `ThreadRelay` 是核心基础设施，改变 TTL 集成方式有引入回归的风险。缓解：行为语义（copier 函数）完全不变，只是注册方式从 TTL 子类改为 Transmitter 注册；需要充分测试跨线程传播行为。
- **[ScopedCallable TL 的 remove 时序]** → ScopedCallable 的 TL 在 finally 中 remove，需要保证 `TaskScopeTl.remove()` 和 `notifyListeners()` 之前 `ScopedCallable.current()` 仍可用。缓解：ScopedCallable TL 的 remove 放在 finally 块最末尾。
- **[用户需了解 TTL API]** → 用户扩展自定义上下文传播需要直接使用 TTL 的 `Transmitter.registerThreadLocal` API。缓解：这是 TTL 的标准公开 API，文档完善。

## Why

当前 `ThreadRelay` 直接使用 `TransmittableThreadLocal`，而 `ScopedCallable` 实现 `TtlAttachments` 接口通过 `ConcurrentHashMap` 传递上下文。这两套机制并行存在，导致上下文传递路径分散。内层 Callable 无法感知外层 `ScopedCallable`，必须依赖 `TtlAttachments` 间接获取 attachments。

用户自定义上下文（如 traceId、MDC、租户 ID 等）的跨线程传播需求，可以通过 TTL 自身提供的 `Transmitter.registerThreadLocal(tl, copier)` 扩展机制实现，无需框架额外封装。但前提是框架自身先统一到 `Transmitter.registerThreadLocal` 模式。

## What Changes

- **重构 `ThreadRelay`**：从 `TransmittableThreadLocal<ThreadRelay>` 改为 plain `ThreadLocal<ThreadRelay>` + `Transmitter.registerThreadLocal(tl, copier)` 注册方式，统一框架的 TTL 集成模式
- **暴露外层 `ScopedCallable`**：通过 ThreadLocal 让内层 Callable 能感知外层 `ScopedCallable`，从而直接获取 attachments（CancellationToken、ParOptions 等），简化上下文获取路径
- **移除 `TtlAttachments`**：`ScopedCallable` 不再实现 `TtlAttachments` 接口，attachments 改为直接实例字段

## Capabilities

### New Capabilities
- `threadrelay-transmitter-refactor`: 重构 `ThreadRelay` 使用 `Transmitter.registerThreadLocal` 模式，并通过 TL 暴露外层 `ScopedCallable` 引用

### Modified Capabilities

（无需修改现有 spec 级别的行为要求，ThreadRelay 是内部实现变更，对外行为不变）

## Impact

- **重构代码**：`ThreadRelay`（TTL 集成方式变更）、`ScopedCallable`（移除 `TtlAttachments` 实现，改用 TL 暴露自身引用）
- **修改代码**：`Par`（适配新的上下文传递方式）
- **API 影响**：`ScopedCallable` 不再实现 `TtlAttachments`（内部类，非公开 API）
- **依赖**：无新外部依赖，仍使用 TTL 2.14.5（`Transmitter` API）
- **兼容性**：完全向后兼容，对外行为不变。用户可通过 TTL 原生的 `Transmitter.registerThreadLocal` 扩展自定义上下文传播

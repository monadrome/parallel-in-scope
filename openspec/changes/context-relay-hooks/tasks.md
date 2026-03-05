## 1. ThreadRelay 重构

- [x] 1.1 将 `ThreadRelay.THREAD_RELAY_TTL` 从 `TransmittableThreadLocal<ThreadRelay>` 改为 `ThreadLocal<ThreadRelay>`（`ThreadLocal.withInitial(ThreadRelay::new)`），并在 static 初始化块中调用 `TransmittableThreadLocal.Transmitter.registerThreadLocal(tl, tr -> new ThreadRelay(tr.curMap))`
- [x] 1.2 更新 `ThreadRelay` 中所有 `THREAD_RELAY_TTL.get()` 引用为新的 `THREAD_RELAY_TL.get()`
- [x] 1.3 更新 import，移除 `TransmittableThreadLocal` 的直接使用（改为仅导入 `TransmittableThreadLocal.Transmitter`）

## 2. ScopedCallable 暴露当前实例

- [x] 2.1 在 `ScopedCallable` 中新增 `private static final ThreadLocal<ScopedCallable<?>> CURRENT = new ThreadLocal<>()` 字段和 `public static ScopedCallable<?> current()` 方法
- [x] 2.2 在 `ScopedCallable.call()` 的 prepareContext 阶段设置 `CURRENT.set(this)`，在 finally 块最末尾 `CURRENT.remove()`
- [x] 2.3 移除 `ScopedCallable` 的 `TtlAttachments` 实现和 `ConcurrentHashMap<String, Object> attachments` 字段
- [x] 2.4 将 CancellationToken、ParOptions、executorName 改为直接实例字段，提供 setter 方法
- [x] 2.5 更新 `Par.executeParallel()` 中设置 attachment 的代码，改为调用 ScopedCallable 的直接 setter

## 3. 测试

- [x] 3.1 编写 ThreadRelay 重构回归测试：验证跨线程 Two-Map 传播行为（CancellationToken、ParOptions、taskName）与重构前一致
- [x] 3.2 编写 `ScopedCallable.current()` 测试：验证任务执行中返回正确实例、任务外返回 null、异常后清理
- [x] 3.3 运行全量测试确保无回归

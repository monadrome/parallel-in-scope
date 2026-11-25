# 从每个任务手写 finally 到一次注册：上下文传播的 CRR 协议

## 问题：上下文不是没设置，而是没走到任务里

在 Web 请求入口，我们常常设置一个 `traceId`，希望后续日志都能串起来：

```java
MDC.put("traceId", traceId);
par.map(orders, order -> {
    log.info("pricing order");       // 这里的 MDC 可能是空的
    return price(order);
}, options);
```

`par.map` 可能把任务交给另一个线程。MDC 基于普通 `ThreadLocal`，新线程不会自动继承它。更麻烦的是，即使用库自带的 TTL 解决了跨线程读取，应用仍然常常需要在任务里手写保存和恢复：

```java
Map<String, String> captured = MDC.getCopyOfContextMap(); // 必须在提交线程执行
par.map(orders, order -> {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    if (captured != null) MDC.setContextMap(captured); else MDC.clear();
    try {
        return price(order);
    } finally {
        if (previous != null) MDC.setContextMap(previous); else MDC.clear();
    }
}, options);
```

这段代码有四个容易漏掉的地方：

1. 捕获写进任务内部，就会读到 worker 上别人留下的值，而不是提交者的值。
2. 结束时只 `clear()`，会把 worker 原来的上下文擦掉；如果任务在线程调用方内联执行，还会擦掉调用方自己的上下文。
3. 每个批次、每个组成员、每个汇总任务都要重复一遍，漏一个入口就少一段日志。
4. 再增加 tracing、租户等机制，嵌套顺序和多个 `finally` 都要靠调用方自己维护。

## 核心想法：把一次传播拆成三个阶段

库现在把一种上下文机制表示为 `ContextPropagator`。它不是装饰任务的通用 SPI，只负责一件事：把提交时的上下文带到执行线程，并在结束后恢复现场。

三个阶段可以写成三个嵌套的 lambda：

```java
ContextPropagator mdc = () -> {
    // 1. capture：在准入（提交）线程执行
    Map<String, String> captured = MDC.getCopyOfContextMap();
    return () -> {
        // 2. install：在真正执行任务代码的线程执行
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (captured != null) MDC.setContextMap(captured); else MDC.clear();
        return () -> {
            // 3. close：仍在该线程执行，恢复 install 前的值
            if (previous != null) MDC.setContextMap(previous); else MDC.clear();
        };
    };
};
```

注册一次，之后所有经这个 runtime 准入的任务都使用它：

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("pricing"), pricingPool)
        .propagate(mdc)
        .build();

TaskBatchResult<Price> result = runtime.par(ParId.of("pricing"))
        .map(orders, order -> price(order),
                BatchOptions.timeout("pricing", Duration.ofSeconds(3)));
```

这里的“快照”是捕获到的上下文值；“作用域”是撤销一次安装的权利。快照本身没有线程亲和性，也不需要额外的释放回调。一个任务如果只排队、后来被取消或拒绝，就不会安装快照，快照随任务引用一起丢弃。

多个机制组成一个栈：TTL 始终是内置的第一个参与者，之后按注册顺序安装，关闭时逆序执行。这样后面的机制可以读前面的机制已经安装好的上下文，也不会把恢复顺序交给每个调用点决定。

## 失败时发生什么

协议沿用 try-with-resources 的异常优先级，而不是把上下文失败悄悄记成一条日志：

| 情况 | 结果 |
| --- | --- |
| `capture()` 抛异常 | 准入调用直接抛出；这个单元里的任务代码不运行 |
| 某个 `install()` 抛异常 | 已打开的作用域逆序关闭；任务代码不运行，任务以该安装失败结束 |
| 任务代码抛异常，同时 `close()` 也抛异常 | 任务代码的异常是主异常，关闭异常放在 `suppressed` 中 |
| 任务代码成功，但 `close()` 抛异常 | 任务失败；所有作用域仍会尝试关闭，后续关闭异常也保留下来 |
| 任务在运行前被取消或被拒绝 | 不安装、不运行任务代码 |

最后一条很重要：恢复失败意味着借来的线程可能还留着当前请求的上下文。如果此时仍报告任务成功，下一个无关任务就可能读到它。因此“任务成功但恢复失败”不能被隐藏。

内置 TTL 参与者仍遵守 TTL 自己的 copier、transmittee 和日志行为；上面的失败优先级针对应用注册的参与者。

## 覆盖范围与边界

覆盖单任务、批次元素、超过初始并行窗口而延迟提交的元素、组成员，以及批次和组的末端汇总。它们都经过同一个准备点，所以不会因为某个入口少包一层而表现不同。普通线程池、直接执行器和拒绝处理器的内联路径也使用同一套安装/恢复逻辑。

不覆盖以下情况：

- 任务代码自己把逻辑交给另一个线程，或留到任务返回后执行；
- 收养的外部 future——它没有由库运行的任务代码；
- 库内部的完成监听和结果记账；
- 静态 `ContextExecutors` 中超出 TTL 的 runtime 参与者。

上下文恢复的是绑定关系，不是值本身的深拷贝。如果快照里放的是可变 `Map`，多个执行仍可能共享它；需要隔离时，在 `capture()` 中复制，或使用不可变值。

## 迁移与取舍

不注册 `ContextPropagator` 时，现有行为不变，TTL 仍是必需依赖，不需要修改现有代码。已经写好自包装的任务也可以继续保留；如果同一种机制已经在 runtime 注册，又在任务里重复安装，通常应删除重复的那一层，避免不必要的嵌套。

没有把扩展点直接设成 TTL 的全局 `Transmittee` 注册表，主要是因为它是 JVM 全局状态，而且 TTL 对参与者失败采用“记录并继续”。对结构化并发库来说，跨 runtime 的全局状态和静默丢失上下文都比一次显式注册更难排查。

完整的接口约束见 [完整使用指南](../user-guide.md#integration-recipes)；设计边界和拒绝的替代方案见仓库中的 `design/extension-and-wrapping.md`。

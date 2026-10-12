# 共享 root 预算

## 蒸馏摘要

- 核心出发点：不同同步动态范围中的 root 调用共用端到端预算，减少人工折算剩余时间。
- 不变量与拟议 MUST：预算 MUST 是显式、只读且携带单调时钟域的值；有效 deadline MUST
  取本次、祖先与共享预算的最小值。耗尽 MUST 走 bind-before-submit 的 TIMEOUT 路径。
- 被否决方向及理由：单条流水线已有 direct 协调 body，不能只为少一层 lambda 引入类型；
  不混用 Instant/nanoTime，不构造 ambient RequestScope，不将零客户端 timeout 当无限等待。

## 目标与论证

依据：[MultiTaskContext](../../src/main/java/io/github/monadrome/parallelinscope/MultiTaskContext.java)、
[第一性原理](../first-principles.md)。嵌套调用已有 min deadline；缺口在多个独立 root。

当前最稳妥的结构化写法是放进一个外层成员，然后让顺序步骤继承预算：

```java
runtime.group("request", totalTimeout)
        .par("pipeline", coordinatorPar, new TypeToken<List<Report>>() {}, () -> {
            List<Value> values = ioPar.map(ids, this::fetch,
                    BatchOptions.inheritTimeout("fetch")).valuesOrThrow();
            return reportPar.map(values, this::render,
                    BatchOptions.inheritTimeout("render")).valuesOrThrow();
        })
        .runAll().valuesOrThrow().typedValues();
```

这是可行替代。`coordinatorPar` 可以注册 Guava 的 direct executor service，在调用线程执行
协调 body，避免占用一个等待子任务的池 worker；与子调用共用受限池才可能饥饿。单条
同步流水线优先用这个方案，不应仅为少写一层 lambda 引入预算类型。也可由应用逐次计算
绝对截止点的剩余时间，但需要正确处理预算耗尽、单位取整和每段调用前的再次校验。

提议后的同一流水线，以一个显式、只读、单调时钟预算作为上界：

```java
ExecutionBudget budget = runtime.budget(totalTimeout);
List<Value> values = ioPar.map(ids, this::fetch,
        BatchOptions.timeout("fetch", fetchTimeout).within(budget)).valuesOrThrow();
List<Report> reports = reportPar.map(values, this::render,
        BatchOptions.timeout("render", renderTimeout).within(budget)).valuesOrThrow();
```

新增类型和方法均未实现。有效 deadline 为 min(本次请求、结构化祖先、共享预算)；预算在
创建时开始计时，不在每次入口重新开始。耗尽预算继续走 bind-before-submit 的 TIMEOUT
路径，不能将零剩余量作为正 timeout 传入现有工厂，也不能转成无限等待。

需要一个值对象的理由是：不同入口或框架回调无法包在同一同步动态范围内时，把跨 root
的绝对上界交给库计算，省掉人工贯穿的时间折算。不能把“不占额外 worker”作为它优于
现有 direct 协调方案的理由。
预算不注册任务、不关闭资源、不持 Callable/Future；先与请求取消分开，避免一次引入
动态 RequestScope。时钟域必须随值保留并验证，不能将 `Instant` 与 `nanoTime` 混算。
跨 runtime 的域兼容规则和取消已经发生但预算仍为正的情况都要先明确。

兼容与契约：可选的额外 deadline 上界，无旧调用迁移；修改 deadline 解析规则，不修改
caller interruption 和 body-exit 保证。需要与请求取消提案 P0001 分别评审，避免一个入口同时承担
“预算值”和“可变取消所有者”两种角色。

与 [P0001](P0001-request-cancellation.md) 分开评审；跨 runtime 时钟域兼容规则与预算耗尽
行为明确后才可实施。新增选择是兼容的，无旧调用迁移。接口草图没有编译或行为验证。

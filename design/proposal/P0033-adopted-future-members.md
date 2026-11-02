# P0033 组成员收养外部 ListenableFuture 与丢弃值关闭

## 蒸馏摘要

**出发点。** 应用里大量并发工作并不是库能执行的 `Callable`，而是已经在途的
`ListenableFuture`：异步 HTTP/RPC 客户端、`ListeningExecutorService.submit`、`FluentFuture`
链、`ClosingFuture.finishToFuture()`、缓存加载器。把多个这样的 future 汇合成一个结构化作用域时，
用户今天必须手写 `allAsList` + 超时 + 取消 + 关闭，每一步都容易「忘记」：

1. `Futures.allAsList` 一个输入失败时**不取消**其余输入，兄弟请求继续占连接；
2. 超时只停止等待，不取消输入，更不继承外层作用域的 deadline；
3. 取消与成功完成竞争：取消前一刻已经产出的资源值（响应体、连接、流）落到没有人读的
   future 里，**泄漏**；作用域失败时已成功的兄弟值同样没有人关闭；
4. 等待线程是池线程时，等待外部 future 不进入执行器等待图，同池自等待的饥饿风险不可见。

**拟议能力。** 组声明新增 `adopt`：把一个外部 future 作为组成员交给组「收养」。成员与
`par` 成员共用同一个 token 树、fail-fast、deadline、`cancelOn`、收敛屏障、结果冻结与
body-exit 跟踪；可选的 `ResourceCloser` 对**没有交出去的值**恰好调用一次；可选的生产者
`Par` 让嵌套等待进入执行器等待图。这是参数化既有成员机制，不是第二条执行管道：收养成员没有
body、不提交到任何执行器、不捕获 TTL。

**不变量与拟议条目（落地时并入活契约）。**

- A1 所有权：`runAll()` 被调用起，组拥有每个被收养的 source。组 token 的任何取消（fail-fast、
  组或祖先 deadline、`cancelOn`、直接取消的级联）MUST 以同一取消意图取消 source。`runAll()`
  在准入前抛出（运行时已关闭、继承 deadline 却无外层任务等）时，MUST 取消全部 source 并按 A4
  处理已产出的值。同一 source 在一个草稿内 MUST NOT 被收养两次。
- A2 先 bind 后连接：source 与成员 future 的连接 MUST 发生在组与成员 token bind 之后，
  已完成的 source 也不例外；连接前已被取消的成员 MUST 取消 source。
- A3 归因：source 成功即 `SUCCESS`；失败即 `USER_FAILURE`（被取消引起的失败按 token 归因，
  同 `par` 成员）；source 被组以外的一方取消即直接成员取消，级联整组。MUST NOT 用
  `isCancelled()` 事后猜测组级归因。
- A4 值交接恰好一次：组里每个产出的非 null 值恰好有一个所有者——声明了 combine 时，combine
  body 进入时取得全部收养值；未声明 combine 时，组以 `SUCCESS` 收敛即交给 `runAll()` 调用方。
  其余每个值（组未成功、combine 从未进入、取消之后迟到的成功值）MUST 恰好交给 closer 一次；
  交接与丢弃由同一个原子决定裁决，MUST NOT 既交出又关闭。
- A5 closer 隔离：closer 的异常 MUST NOT 改变任何 outcome，只记 `WARNING`；
  `InterruptedException` 恢复中断标志。closer 在完成 source 或收敛组的线程上运行，
  MUST NOT 在收敛发布 completion 之前运行，因此阻塞的 closer 不能让 `runAll()` 越过有界清理等待。
- A6 退出确认：收养成员在 body-exit 跟踪中占一个槽，source 落定且（被丢弃时）closer 返回后才
  释放。`bodyCompletionConfirmed()` 为 true 意味着所有 source 已落定、所有丢弃值已关闭；
  `awaitQuiescence` 同样覆盖。
- A7 等待图：声明了生产者 `Par` 时，成员单元的节点取该 `Par` 的执行器身份，嵌套 `runAll()`
  按 task-graph P2–P5 原样记录等待边；未声明时节点没有执行器，MUST NOT 成边（不伪造边）。
- A8 观测兼容：收养成员不做 TaskType 提交诊断、不参与负载匹配计时（没有库执行的 body）；
  线程池饥饿观测照常对生产者池采样，收养不包装、不提交、不改变池上的任务。

**被否决的方向。**

- *批次形状 `Par.map(List<ListenableFuture<T>>)`*：批次是同构映射、需要 body 与滑窗，
  future 列表没有可映射的 body，也没有可限流的提交；动态个数用组循环收养加 `valueOf(key)` 读取。
- *返回异步句柄或在 future 上做链式编排*：违反同步出口与 idea-graveyard 的编排否决；结果仍是冻结的
  `TaskGroupResult`。
- *作用域退出时总是关闭（ClosingFuture 式）*：没有 combine 时调用方拿到的是已关闭的值，
  use-after-close；有 combine 时 combine 自己用 try-with-resources 关闭，再由作用域关一次就是
  双重关闭。改为「交出一次，其余关闭」。
- *closer 在库自有线程池上运行*：运行时关闭后线程池可能已停，需要回退路径；同时让「丢弃值在
  有界清理前关闭」变成非确定。改为在落定线程上运行并计入 body-exit 槽。
- *由 `T extends AutoCloseable` 推断 closer*：隐式行为，且不能表达「归还连接池」这类关闭；
  必须显式写出 closer。
- *强制生产者 `Par`*：Netty/客户端 IO 线程完成的 future 不属于任何注册执行器，强制即伪造边。
- *收养成员的 `TaskOptions`*：taskType/rejectEnqueue 无消费者；成员级 timeout 暂不提供，
  组 deadline 已是活性来源，需要时再按真实用例参数化。
- *专门适配 `ClosingFuture`*：`finishToFuture()` 已是 `ListenableFuture`，按普通 source 收养即可。

## 用户代码

改前（今天能写出的最好代码）：

```java
ListenableFuture<Response> profile = client.get("/profile");
ListenableFuture<Response> orders = client.get("/orders");
List<ListenableFuture<Response>> inputs = Arrays.asList(profile, orders);
try {
    List<Response> both = Futures.allAsList(inputs).get(2, TimeUnit.SECONDS);
    return render(both.get(0), both.get(1));      // render 内负责关闭两个响应
} catch (TimeoutException | ExecutionException failure) {
    for (ListenableFuture<Response> input : inputs) {
        input.cancel(true);                       // allAsList 不会替你取消兄弟
        closeIfSucceeded(input);                  // 取消竞争中已成功的值；迟到值仍会泄漏
    }
    throw failure;
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();           // 同样要取消与关闭，常被遗漏
    throw interrupted;
}
```

改后：

```java
TaskKey<Response> profile = TaskKey.named("profile");
TaskKey<Response> orders = TaskKey.named("orders");
TaskKey<Page> page = TaskKey.named("page");
TaskGroupResult<Tuple2<Response, Response>, Page> result = runtime.group("page", Duration.ofSeconds(2))
        .adopt(profile, client.get("/profile"), Response::close)
        .adopt(orders, client.get("/orders"), Response::close)
        .combine(page, cpuPar, both -> {
            try (Response p = both.first(); Response o = both.second()) {
                return render(p, o);
            }
        })
        .runAll();
```

**被消除的失败模式**：兄弟输入不被取消、外层 deadline 不被继承、失败/超时/取消竞争中产出的资源
值泄漏、等待外部 future 的同池饥饿不可见。非破坏性新增，无迁移路径。

## 第一性原理六问

1. 消除的「忘记」：忘记取消、忘记关闭、忘记继承 deadline、忘记恢复中断（见上）。
2. 参数化既有机制：成员仍是 `MemberState`，复用组 token、bind、收敛、冻结、body-exit 跟踪与
   等待图记录；只把成员的结果来源从「库执行的 body」参数化为「被收养的 future」。
3. 取消、deadline、上下文：取消与 deadline 自动继承；没有 body 所以没有 TTL 回放需求。
4. 无隐式状态、无第二条管道：不提交、不包装执行器、不新增 ThreadLocal。
5. outcome 词汇不变：`SUCCESS`/`USER_FAILURE`/`MEMBER_CANCELLED`/`FAIL_FAST`/`TIMEOUT`/`CANCELLED`。
6. 抽象边界：Group 是异构协调范围，收养是异构成员；Batch 不受影响。

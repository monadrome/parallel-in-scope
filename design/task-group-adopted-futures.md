# 组成员收养外部 future 契约

> 本文是 `adopt` 组成员的活契约：出发点、所有权与取消、值交接与丢弃值关闭、退出确认、与执行器等待图及
> 线程池饥饿观测的兼容、被排除的替代方案。历史来源：`proposal:P0033`。组声明、token 拓扑与同步出口的
> 通用规则仍由 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md)、
> [task-group-cancellation.md](task-group-cancellation.md)、[synchronous-scope-exit.md](synchronous-scope-exit.md) 持有。

## 1. 出发点

很多并发工作在进入作用域之前就已在途：异步 HTTP/RPC 客户端、`ListeningExecutorService.submit`、
`FluentFuture` 链、`ClosingFuture.finishToFuture()`、缓存加载器都只交出一个 `ListenableFuture`。把多个这样的
future 汇合成一个结构化作用域时，手写 `Futures.allAsList` + 超时会让用户「忘记」四件事：

1. `allAsList` 一个输入失败时不取消其余输入；
2. 超时只停止等待，既不取消输入，也不继承外层作用域的 deadline；
3. 取消与成功完成竞争时，已产出的资源值（响应体、连接、流）落进无人读取的 future 而泄漏；作用域失败时，
   已成功的兄弟值同样无人关闭；
4. 池线程等待外部 future 不进入执行器等待图，同池自等待的饥饿风险不可见。

`adopt` 把一个外部 future 声明为组成员。它参数化既有成员机制：同一组 token、fail-fast、deadline、
`cancelOn`、收敛屏障、结果冻结与 body-exit 跟踪；只把成员结果的来源从「库执行的 body」换成「被收养的
future」。收养成员没有 body，不提交到任何执行器，不捕获或回放 TTL，不是第二条执行管道。

改前改后的用户代码：

```java
// 改前：取消、关闭、deadline 与中断全部手写，取消竞争中迟到的值仍会泄漏
List<Response> both = Futures.allAsList(profile, orders).get(2, TimeUnit.SECONDS);

// 改后
TaskGroupResult<Tuple2<Response, Response>, Page> result = runtime.group("page", Duration.ofSeconds(2))
        .adopt(PROFILE, client.get("/profile"), Response::close)
        .adopt(ORDERS, client.get("/orders"), Response::close)
        .combine(PAGE, cpuPar, both -> {
            try (Response p = both.first(); Response o = both.second()) {
                return render(p, o);
            }
        })
        .runAll();
```

## 2. 契约

**所有权与取消**

- A1 `runAll()` 被调用起组拥有每个被收养的 source。组取消未完成成员的任何原因（fail-fast、组或祖先
  deadline、`cancelOn`、直接取消成员的级联）MUST 以 `cancel(true)` 取消 source（框架取消一律中断，见[取消传播契约](cancellation-propagation.md) §5.1）：
  成员 future 是库自有的镜像，镜像被取消时在 `afterDone` 中以 `wasInterrupted()` 取消 source。source 的监听器在被取消时抛出（含 `Error`）
  MUST 只记 `WARNING`，MUST NOT 逃进组的取消级联或清理：级联逐个取消绑定的 future，一个逃出的 `Error` 会让其余
  成员永不取消，而 token 已经终态，deadline 也不再触发，`runAll()` 永久挂起。抛出的监听器还会让 Guava 跳过
  source 之后的监听器（可能包括本成员的落定监听器），因此取消之后 source 已完成时由取消方补做落定，恰好一次。
  `runAll()` 在准入前抛出（运行时已关闭、
  继承 deadline 却没有外层任务、准备或启动失败）时，MUST 取消全部未连接的 source，按 A4 关闭其产出的值，
  并释放已登记的 body 槽。同一 source 在一个草稿内 MUST NOT 被收养两次（按引用身份拒绝）。
- A2 source 与镜像的连接（登记落定监听器）MUST 发生在组 bind 与成员 bind 之后，与 `par` 成员「先 bind 后提交」
  同一位置；连接 MUST NOT 因镜像已完成而跳过。准入时就被取消（例如 `cancelOn` 的信号已完成）的成员在 bind 中
  取消镜像，镜像随即取消 source；连接仍登记监听器，使 body 槽在 source 落定后释放。镜像 MUST 只经由这一个落定
  监听器完成，MUST NOT 直接复制一个已完成的 source（`setFuture` 的快捷路径不等 source 先前的监听器跑完）。
- A3 归因：source 成功即 `SUCCESS`；失败即 `USER_FAILURE` 并保留原始原因，由取消引起的失败按 token 归因；
  被组以外的一方取消即直接成员取消，成员读 `MEMBER_CANCELLED`，整组按既有规则级联。归因 MUST 在收敛时读
  token，MUST NOT 用 `isCancelled()` 事后猜测组级原因。

**值交接与关闭**

- A4 组里每个产出的非 null 值恰好有一个所有者：声明 combine 时，combine body 进入时取得全部收养值，此后
  combine 失败也不再由组关闭；未声明 combine 时，组以 `SUCCESS` 收敛即交给 `runAll()` 调用方。其余每个值——
  组未成功、combine 从未进入、取消之后才到达的成功值——MUST 恰好交给该成员的 `ResourceCloser` 一次。交接与
  丢弃由组内同一个原子决定裁决（combine 入口与收敛竞争同一次 CAS），MUST NOT 既交出又关闭；null 值与失败、
  被取消的 source 不调用 closer。所有权不同于可见性：冻结结果仍报告被丢弃成员的 outcome 与值，供失败诊断；
  该值归 closer 释放，调用方 MUST NOT 在 `runAll()` 返回后使用它（Javadoc 与使用指南写明）。有 combine 的组，
  成员值同样归 combine。
- A5 closer 是用户代码：异常 MUST NOT 改变任何 outcome 或退出确认，只记 `WARNING`；`InterruptedException`
  恢复运行它的线程的中断标志。丢弃路径 MUST 在收敛发布 completion 之后才交出 closer，且 closer MUST 在运行时
  自有的超时动作线程池上运行，MUST NOT 在落定线程上内联：落定线程可能就是 `runAll()` 调用方（source 已完成时
  组在 `submitPrepared` 内同步收敛，准入失败时在调用方放弃 source），也可能是运行时投递 deadline 的线程。该池
  按需扩容，阻塞的 closer 只占它的一个线程，`runAll()` 不会越过有界清理等待。收养组的运行时保留 MUST 持续到
  收敛把丢弃值交出之后（`ownershipSettled` 与成员 future 一同保留），因此在 `runAll()` 等待期间调用
  `ParRuntime.close()` 也不会让池在交出之前停止。运行时服务关闭后池拒绝任务，
  被拒绝的释放在交出它的线程上内联运行：迟到值的落定线程，或对已关闭运行时调用 `runAll()`、因准入失败放弃
  已完成 source 的调用方；这条路径不承诺有界返回，Javadoc 与使用指南写明。一次性守卫保证执行器既启动又抛出
  时也不重复关闭。交接路径不运行用户代码，
  在发布前完成，使成功组的成员最终观测确定可读。

**退出确认**

- A6 每个收养成员在组的 body-exit 跟踪中占一个槽，连接时视为已运行：source 落定后，且（声明了 closer 时）
  所有权已决定、丢弃值的 closer 已返回，才释放。`bodyCompletionConfirmed()` 为 true 即所有 source 已落定、
  每个丢弃值的 closer 已运行完毕（closer 失败只记日志，不改变确认结果，确认不保证值已被释放）；
  忽略取消的 source 在清理预算耗尽时出现在 `unfinishedBodies()` 中；`awaitQuiescence`
  同样等待这些槽。成员观测的开始时间是连接时刻，结束时间是 source 落定时刻，并在镜像落定之前写入，
  使经由 source 收敛的组报告读到最终结束时间。准入即被取消的组在连接之前就已收敛，组报告中该成员的时间为 0，
  与连接前被取消的 `par` 成员一致；冻结结果中的成员观测在 source 落定后发布真实时间。准入失败时放弃的 source
  也取消其镜像，使运行时对成员 future 的保留随之结束。

**观测兼容**

- A7 声明生产者 `Par` 时，成员单元的节点取该 `Par` 的执行器身份与 starvation-prone 判定，嵌套 `runAll()`
  的等待按 [task-graph.md](task-graph.md) P2–P5 原样记录：在执行器 A 上的 body 收养由 A 完成的 future 是自环。
  生产者是用户声明、库不核实的事实；未声明时节点没有执行器，MUST NOT 成边。生产者必须属于同一运行时。
- A8 收养成员不做 TaskType 提交诊断，不参与负载匹配筛查计时（没有库执行的 body）。线程池饥饿观测照常对生产者
  池采样：收养 MUST NOT 包装、提交、取消（source 之外）或重排池上的任务；source 排在等待者自己占住的池
  队列里时，两个信号互补可见——等待图给出自环，采样给出 `POTENTIAL_STARVATION`，组按 deadline 以 `TIMEOUT`
  结束并取消 source。

## 3. 被排除的替代方案

- **批次形状 `Par.map(List<ListenableFuture<T>>)`**：批次是同构映射，依赖 body 与滑窗限流；future 列表既没有
  可映射的 body，也没有可限流的提交。动态个数的输入循环收养，再按保存的 key 读取。
- **返回异步句柄或在 future 上链式编排**：违反同步出口与 idea-graveyard 的编排否决；结果仍是冻结的
  `TaskGroupResult`。
- **作用域退出时总是关闭（`ClosingFuture` 式）**：没有 combine 时调用方拿到已关闭的值（use-after-close）；
  有 combine 时 combine 用 try-with-resources 关一次、作用域再关一次即双重关闭。改为「交出一次，其余关闭」。
- **closer 在落定线程上内联运行**（首版实现）：独立评审复现，source 已完成时组在 `runAll()` 调用线程的
  `submitPrepared` 内同步收敛，阻塞的 closer 让 `runAll()` 永不返回；deadline 驱动的收敛则在运行时内部线程上
  运行用户代码。改为交给运行时的超时动作池，仅在其拒绝（服务已关闭）时回退到落定线程；body-exit 槽仍保证
  有界清理等待覆盖 closer。
- **冻结结果中扣下被丢弃的值**：成员 outcome 仍是 `SUCCESS`，没有「已释放」的 outcome 可表达，把值换成 null
  会伪造结果；改为写明所有权与可见性的区别。
- **收敛发布前运行 closer**：阻塞的 closer 会挡住 completion，`runAll()` 失去以 deadline 为唯一活性来源的保证。
- **由 `T extends AutoCloseable` 推断 closer**：隐式行为，也表达不了「归还连接池」一类释放；closer 必须显式。
- **强制生产者 `Par`**：客户端 IO 线程完成的 future 不属于任何注册执行器，强制即伪造边（task-graph P2/P4）。
- **收养成员接受 `TaskOptions`**：taskType 与 rejectEnqueue 没有消费者；成员级 timeout 暂不提供，组 deadline
  已是活性来源，需要时按真实用例参数化。
- **专门适配 `ClosingFuture`**：`finishToFuture()` 已是 `ListenableFuture`，作为普通 source 收养；取消它会取消
  管道并由 `ClosingFuture` 关闭其登记的资源。

## 4. 验证基线

`TaskGroupAdoptedFutureTest` 覆盖：成功交给调用方且不关闭、与 `par` 成员及 combine 混用、成员失败以中断取消
在途 source 并关闭已产出值、source 失败为 `USER_FAILURE` 且取消兄弟、组 deadline 取消 source、调用方取消
source 为直接成员取消、准入即取消仍取消 source、忽略取消的 source 迟到值被关闭且清理预算内报告未完成、
combine 进入即拥有、combine 未进入由 closer 关闭、deadline 与 combine 入口竞争下每个值恰好一个所有者、
closer 失败不改变结果、阻塞 closer 不挡住 `runAll()`（含组在 `runAll()` 内同步收敛）、closer 不在调用线程
上运行、收敛报告的结束时间、准入失败与缺少继承 deadline 时放弃 source、声明期校验、Guava 的 `ListenableFutureTask`/`FluentFuture`/`ClosingFuture`
形状、有生产者时同池自环与无生产者时不成边、source 排在等待者自己的池上时等待图与饥饿观测同时可见。
`AdoptedSourceTest` 覆盖放弃已准备的 source（取消镜像、释放槽）、连接后放弃是空操作、null 值不交给 closer、
closer 失败与中断的日志与中断恢复、执行器拒绝时回退到落定线程。A5 的发布顺序与 A6 的结束时间顺序登记在
`verification/race-claims/claims.json`。`verification/maven-central-consumer` 在 Java 8 运行时上覆盖收养成员。

## 5. 独立评审与处置

第一轮（`/multi-review`：kiro、codex、claude、ocr 四路均读到变更文件，评审范围为提案、实现与测试三个提交）：

- **保留并修复**：kiro 复现 source 已完成时组在 `runAll()` 调用线程上同步收敛，内联 closer 阻塞即 `runAll()`
  永不返回（P0）；claude 指出 deadline 驱动的丢弃在运行时内部线程上内联运行 closer，同一根因。修复为 A5 的
  派发规则，两条回归测试在撤掉派发后失败。kiro 指出放弃 source 时镜像不落定，运行时对成员 future 的保留
  永不结束；修复为放弃时取消镜像，回归测试在撤掉后失败。内部不变量改用 `Verify`、Javadoc 重排属规范项。
- **降级为文档**（三方独立投票 1:2 不改实现）：被丢弃值在冻结结果中仍可见（kiro）——没有「已释放」的 outcome，
  改写为 A4 的所有权与可见性区分；准入即取消的组报告成员时间为 0（codex、claude）——与连接前被取消的 `par`
  成员一致，门控收敛会改变每个收养组的收敛协议，改写为 A6 的限定。
- claude 关于「阻塞 closer 拖慢其它组 deadline」的推论被驳回：deadline 动作本就派发到按需扩容的动作池，
  计时线程不运行用户代码；但内联运行用户代码这一事实随 A5 一并移除。

第二轮（同四路，评审第一轮的修复提交）：

- **保留并修复**：kiro 与 codex 复现对已关闭运行时调用 `runAll()` 时，已完成 source 的 closer 因动作池拒绝而在
  调用线程上运行，与「从不在调用线程上」的文档相矛盾；保留拒绝回退（新建线程兜底需引入原始线程机制），
  改写 A5、`ResourceCloser`、两份指南与 CHANGELOG 的例外条款（claude 的同类 P3 一并处理）。codex、claude、kiro
  指出 deadline 路径测试只断言线程名前缀，撤掉派发仍通过；改为经包内 `timeoutScheduler` 测试缝注入命名可辨识的
  投递线程，断言组在其上收敛而 closer 不在其上运行，反向验证撤掉派发即失败。kiro 指出竞态登记的说明把一个
  检不出内联的测试列为证据，已更正。

第三轮（kiro、claude、ocr 读到文件；codex 首次运行连接中断后重跑一次）：

- **保留并修复**：kiro 复现 `runAll()` 等待期间调用 `close()` 时，运行时对成员 future 的保留先于收敛完成，
  动作池在 closer 交出前停止，丢弃值在 source 的完成线程上内联关闭（这些值并非迟到值）；修复为收养组额外保留
  `ownershipSettled`，收敛交出 closer 后才释放，回归测试在撤掉保留后失败。claude 指出带 closer/producer 的
  `adopt` 重载缺少 `@throws`，已补齐；Java 8 消费者测试的导入分组已整理。
- **驳回**：claude 认为 closer 恢复的中断标志会残留在复用的动作池线程上。`ThreadPoolExecutor.runWorker` 在每个
  任务之前清除未停止池的中断标志，标志不会带到下一个任务。
- codex 重跑后保留两项并修复：其一，source 自己的监听器在组取消它时抛出 `Error`，原实现经 `setFuture` 的取消
  传播把它抛进 token 级联与放弃清理，其余 source 不再取消、已产出值不关闭；反向验证显示只捕获
  `RuntimeException` 时级联测试永久挂起、放弃测试失败。改为 A1 的隔离与补做落定。其二，`setFuture` 复制已完成
  source 的快捷路径可在落定监听器写入结束时间之前收敛，成功组的报告结束时间为 0；改为 A2 的单一落定路径，
  确定性的窗口测试在恢复快捷路径后失败。
- 三轮已满，按根 AGENTS.md 停止评审；第三轮的修复由回归测试与反向验证支撑，未再经独立评审。

接管收尾（暂停 goal 运行后在主会话完成）：对 `AdoptedSource*`、`TaskGroup`、`GroupDraft*`、`TaskGroupDefinition*`
跑含 `VOID_METHOD_CALLS` 的 PIT（组相关全部契约测试，无历史输入）。首轮暴露的四个缺口已补测试并反向验证：
`adopt` 的重名校验、阶段与线程限制、带生产者与 closer 的四参重载（此前无覆盖）、收敛完成 `ownershipSettled`
后运行时不再保留收养组（撤掉该完成即 `awaitQuiescence` 超时）。变更行上的剩余存活均已分类：
`slot -> slot.adopted` 恒真的两处为等价（无收养成员时 combine 的认领与额外保留都不可观察：combine 若晚于
丢弃决定启动，其 future 已随组取消而跳过 body；`ownershipSettled` 在同一次收敛的 finally 中完成）；
`Verify` 调用删除三处为防御性内部不变量（参数中的 CAS 与状态迁移仍执行，合法调用图不触发）；
`takePayloads` 清空身份集合为已消费草稿的引用卫生，不可观察；`par` 分支的 `diagnoseAdmission` 删除是
原有调用移位，属既有 TaskType 诊断测试不在本范围内的覆盖限制。未变更行上的存活不属本次变更。

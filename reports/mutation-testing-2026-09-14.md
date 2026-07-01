# 变异测试报告（2026-09-14）

## 运行方式

```bash
mvn test-compile   # default-cli 直接调 goal 不触发编译，必须先编译
mvn -Ppitest org.pitest:pitest-maven:mutationCoverage

# 报告位置
# HTML: target/pit-reports/index.html
# 数据: target/pit-reports/mutations.xml
```

环境：Maven 3.9.15 运行于 JDK 25.0.2（PITest 要求 JDK ≥ 11），pitest-maven 1.25.9 +
pitest-junit5-plugin 1.2.3，8 worker，单次耗时 9 分 14 秒。

## 总体结果

| 指标 | 数值 |
|---|---|
| 变异总数 | 1441 |
| KILLED | 1210 |
| TIMED_OUT | 21 |
| SURVIVED | 156 |
| NO_COVERAGE | 53 |
| MEMORY_ERROR | 1 |
| **变异得分** | **85.4%**（1231/1441） |
| 测试强度 | 89%（1231/(1231+156)） |
| 行覆盖（仅被变异类） | 3041/3190（95%） |
| 执行测试数 | 6421（平均每个变异 4.46 个） |

## 结果可复现性（两次独立运行对比）

同一份代码连续跑两次完整变异测试，结果高度一致：

| | 第 1 次 | 第 2 次 |
|---|---|---|
| KILLED | 1210 | 1209 |
| SURVIVED | 156 | 156 |
| NO_COVERAGE | 53 | 52 |
| TIMED_OUT | 21 | 23 |
| 变异得分 | 85.4% | 85.5% |

变异集合完全相同（1441 个），状态只有 **3 处**（0.2%）发生变化：

- `VariableLinkedBlockingQueue:237` SURVIVED → KILLED（边界变异，由时序敏感的测试决定）
- `TaskGroup:254` KILLED → TIMED_OUT
- `SlidingWindowSubmitter:199` NO_COVERAGE → SURVIVED

**结论：存活变异集合稳定在 156 个，得分可信。** 0.2% 的抖动集中在并发相关变异上，
属于并发测试的固有噪声，不改变任何结论。

> 注意一个容易误判的陷阱：同一行源码会产生**多个** PIT 变异（不同 `indexes`/`blocks`），
> 它们的判定可以完全不同。例如 `SmartBlockingQueue:67` 同时存在一个 KILLED 和一个
> SURVIVED；`ExecutionPhaseHintFuture:206` 同一行有 KILLED + SURVIVED + NO_COVERAGE。
> 手工改源码跑一次套件来"验证某个变异是否被杀"，验证的是**该行语义**，不是 PIT 报告里
> 那个具体的字节码变异——据此得出"报告过期"的结论是不成立的。

## 与上次（2026-08-21）对比

| 指标 | 2026-08-21 | 2026-09-14 |
|---|---|---|
| 变异总数 | 1176 | 1441 |
| KILLED | 839 | **1210** |
| TIMED_OUT | 29 | 21 |
| SURVIVED | 122 | 156 |
| NO_COVERAGE | 186 | **53** |
| 变异得分 | 73.8% | **85.4%** |
| 测试数 | 274 | 572 |

无覆盖从 186 降到 53，主要来自 demo 类已被 `excludedClasses` 排除，以及新增测试的补齐。
两次之间代码库经历了 0.2.0 的单包合并（`e0e8849`）与 queue 包重写（`4a941c2`），
变异集合并不可比，上表只作趋势参考。

## 关键发现

### 1. pom 中两个 PIT 参数已失效（构建配置缺陷）

PIT 1.25.9 在两次运行中都输出：

```
[WARNING] Parameter 'testPlugin' is unknown for plugin 'pitest-maven:1.25.9:mutationCoverage'
[WARNING] Parameter 'coverageTimeout' is unknown for plugin 'pitest-maven:1.25.9:mutationCoverage'
```

对照该版本 `META-INF/maven/plugin.xml` 的参数清单确认：`coverageTimeout` 与 `testPlugin`
**都已不存在**，pom 中的取值被静默忽略。影响：

- `coverageTimeout=300` 是 pom 注释里明确写着"防止覆盖阶段被 10s 默认值误杀"的保护，
  现在这层保护**实际不存在**。两次运行覆盖阶段分别耗时 21 秒且未触发问题，但注释描述的
  防护与实际行为已不一致。
- `testPlugin=junit5` 被忽略，但 junit5 是通过插件依赖自动探测的，功能上无影响。

pom 的版本从 1.19.1 升到 1.25.9 时没有同步迁移这两个参数。

### 2. 基线存在一个 flaky 测试（影响变异得分可信度）

`VariableLinkedBlockingQueueSignalTest.clearOnFullQueueReleasesBlockedProducer:95`

```
[ERROR] VariableLinkedBlockingQueueSignalTest.clearOnFullQueueReleasesBlockedProducer:95
        expected: <true> but was: <false>
```

- 单独运行该类 5 次全绿；干净的全量套件运行 6 次中失败 1 次（约 1/6）。
- 原因是**测试自身的竞态**，不是库缺陷：`clear()`（`VariableLinkedBlockingQueue.java:396`）
  在 `count.getAndSet(0)` 后 `notFull.signal()`，被唤醒的生产者重新检查 `count >= capacity`
  （`put()` L189-192）成立后立即入队；主线程随后的 `assertTrue(queue.isEmpty())`
  （测试 L95）与这次入队形成竞态。
- 已确认该竞态与任何存活变异都不相关（`clear()` 相关变异由其他测试击杀），是纯粹测试缺陷。
- 前一份报告也记录过 flaky 失败。并发测试的不稳定性会让"存活/击杀"判定带噪声。

### 3. 最大真实缺口：队列信号测试被"有界超时"掩盖

`DrainingBlockingQueue` 有 **8 个漏唤醒变异存活**：

| 位置 | 方法 | 变异 |
|---|---|---|
| L461 | offer | `if (oldCount == 0) signalTakeReady();` 取反 |
| L491 | put | 同上 |
| L523 | 定时 offer | 同上 |
| L559 | 定时 poll | `if (oldCount == capacity) signalPutReady();` 取反 |
| L588 | take | 同上 |
| L623 | poll | 同上 |
| L713 | remove() | 同上 |
| L1087 | drainTo | `if (amount > 0)` 取反，跳过 `signalPutReady()` |

取反后语义反转：`oldCount == 0` 表示"入队前队列为空"，正是必须唤醒消费者的时刻；
取反后跳过信号，被阻塞的消费者/生产者不会被唤醒。这是**活性缺陷**，不是等价变异。

之所以存活，是因为现有信号测试几乎全部使用**有界等待**，超时把漏信号掩盖掉了。例如
`DrainingBlockingQueueTest.capacityTwoEnqueueToEmptySignalsConsumer`：

```java
result.set(queue.poll(2, TimeUnit.SECONDS));   // 有界等待
...
waitUntilBlocked(consumer);
assertTrue(queue.offer(1));
consumer.join(2000);
assertFalse(consumer.isAlive());               // 超时唤醒也满足这条断言
```

变异体漏掉信号后，消费者的 `poll(2s)` 仍会在 2 秒超时后醒来，重新检查 `count != 0`
并取到元素——`assertFalse(consumer.isAlive())` 照样通过（`join(2000)` 与被掩盖的 2s 超时
之间只有约 3ms 余量，所以测试既打不中变异，自身也随时可能翻车）。同类问题：

- `capacityTwoDequeueFromFullSignalsProducer` 用 `offer(3, 2, SECONDS)` + `join(2000)`，
  同样是"测试预算 = 被测超时"，约 3ms 余量。
- `ContractTest.timedOfferReleasedByCloseReturnsFalsePromptlyWithoutStoring` 在
  `producer.start()` 后**没有等待生产者真正阻塞**就 `close()`，所以生产者在
  `beginBlockingCall` 就返回了（L508），L516 那行 `return false` 从未被执行
  （这正是 L516 两个变异 NO_COVERAGE 的原因）。

**修法**：改用无界等待（`take()` / `put()`，无信号则永久阻塞）+ 较短的 join 超时，
让"是否被唤醒"与"是否超时"可区分。`VariableLinkedBlockingQueueSignalTest` 用的是
`queue.take()` + `finished.await(4, SECONDS)`，`DrainingBlockingQueueTest.addSignalsWaitingConsumerWhenQueueTransitionsFromEmpty`
也是可区分的正确写法——把这两处的模式推广到上面 8 个点即可。

**`VariableLinkedBlockingQueue` 同源缺口**：L216（定时 offer）、L237（offer）、
L281（定时 poll）、L301（poll）的 `if (c + 1 < capacity) notFull.signal();` /
`if (c > 1) notEmpty.signal();` 取反。取反后当队列仍有元素时不唤醒下一个等待者。
`take()` L258 的同一变异**已被击杀**，说明该场景测试只覆盖了 `take`，没覆盖其余三个变体。

> 设计提示：`offer(E)` L237 与 `poll()` L301 的中继信号在"不被中断"的执行里是冗余的
> （被唤醒方的 put/take 一定会继续中继），而 `put` L194 与 `take` L258 的中继是**必需的**。
> 不要以 L237/L301 为依据把这些信号"简化掉"。

### 4. `toArray(T[])` 未遵守"就地返回"契约

两处边界变异存活，都违反了 `Collection.toArray(T[])` 的语义
（"若集合能放入指定数组，则返回该数组"）：

- `VariableLinkedBlockingQueue:381` `if (a.length < size)` → `<=`
- `DrainingBlockingQueue:1196` `destination.length >= snapshot.length` → `>`

变异后当传入数组**长度恰好相等**时会另分配新数组返回，调用方传入的数组既没被填充、
也不再是返回值。现有断言只比较内容，所以打不中。击杀方式：

```java
String[] caller = new String[size];
assertSame(caller, queue.toArray(caller));   // 变异体返回新数组，失败
```

### 5. 8 个存活变异是"构造上不可击杀"的空操作

PIT 会对 `return true;` / `return false;` 生成"把返回值替换为同一个常量"的变异，
这类变异不存在任何可观测差异：

- `VariableLinkedBlockingQueue` L211 / L329 / L332 / L351 / L353（5 个）
- `GlobalPar` L287 / L291（2 个）
- `DrainingBlockingQueue` L776 / L516 / L891（`addAll` / 定时 `offer` / `unlinkIfPresent`）

剔除后调整口径：**有效测试强度 89.3%**（1231/1379），有效变异得分 85.9%（1231/1433）。
这类变异无法通过补测消除。

### 6. `VariableLinkedBlockingQueue` 声明了 `Serializable` 但实际不可用

```java
public class VariableLinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, Serializable {
    private static final long serialVersionUID = 1L;
    transient Node<E> head;
    private transient Node<E> last;
```

`head` / `last` 都是 `transient`，且全包内**没有任何 `readObject`/`writeObject`**
（`grep` 确认）。`Condition` 字段（`ReentrantLock.newCondition()` 返回的
`AQS.ConditionObject`）本身是 `Serializable`，所以序列化会正常完成，但反序列化后
`head == last == null`，首次使用即 NPE。JDK 的 `LinkedBlockingQueue` 正因如此才实现了
`readObject`。

这不是变异测试发现的（该路径无变异点），是审查 queue 类时顺带发现的契约违背。
修法二选一：补 `readObject`，或去掉 `Serializable` / `serialVersionUID`。

### 7. 死代码与不可达分支

| 位置 | 说明 |
|---|---|
| `DrainingBlockingQueue.prepend` (L1112) | 在 `src/main` 和 `src/test` 中**均无调用方**，其变异永远无法覆盖 |
| `DrainingBlockingQueue.unlinkLast` (L1133) | 同上；其 `NullReturn` 变异也是永久 NO_COVERAGE |
| `TaskBatchResult.of(BodyCompletionTracker,List)` (L128) | 无调用方 |
| `TaskBatchResult.of(ListenableFuture,List)` (L140) | 无调用方 |
| `ListenableCompletionService.submitOrRunInline(Callable)` (L122) | 无调用方（生产代码走 `ExecutionPhaseHintFuture` 重载） |
| `Task.transformAsync` / `Task.catchingAsync` | `src/main`、`src/test` 均无调用方 |
| `Task.withTimeout(long,TimeUnit,…)` (L249) | 只有 `Duration` 重载被使用 |
| `DrainingBlockingQueue.peek` L646-650 / `element` L736-743 的 `head.next == null` 兜底 | `count != 0` 时该状态在 `takeMonitor` 下不可能出现（注释里已说明 happens-before 依据），属不可达防御分支，三个变异永久 NO_COVERAGE |

建议删除无调用方的方法，或明确标注为"不可达防御分支"，避免后续每次变异测试都要重新甄别。

### 8. 其他值得注意的存活变异

- `TaskGroup:641` `index++` → `index--`——`memberPars.get(index)` 在成员数 ≥ 2 且
  TaskGraph 观察作用域有效时会越界抛 `IndexOutOfBoundsException`。存活说明观察路径只在
  单成员场景被测过。
- **整个"观察作用域内提交 TaskGroup / 顶层 par.submit"分支未被测试**：
  `Par:140/142/148/153/155/158`（`submitWhileOpen`）、`TaskGroup:581/677/766`
  （`buildWhileOpen`、`logForking`）、`TaskGraphObservationScope:193` 的 3 个极性项
  都属于这一簇。其中 641 在变异下直接抛异常，说明这不是无害缺口。
- `TaskBatchResult:199` `close()` 的标签三元取反——空批次 `results.get(0)` 会抛
  `IndexOutOfBoundsException`，而 `Par.emptyBatchResult()` 正好返回这种批次，可达。
- `BatchOptions.closeGrace` 实际上未被测试（只覆盖了 `Duration.ZERO` 与派生截止时间两条路径），
  是 `TaskBatchResult:54/56/211` 三个变异存活的共同根因；`TaskGroup` 侧有对应测试，
  batch 侧是用户可见的盲区。
- `MultiTaskContext:130` 仅在 `requestedParallelism() == 0` 时不同，而
  `BatchOptions` 默认 -1、测试只传 > 0 的值。
- `DrainingBlockingQueue$QueueSpliterator.trySplit`（L1305/1314/1325）4 个真实缺口：
  取反后恒返回 null、或在空队列上 NPE、或 `estimateSize()` 归零。现有测试
  `trySplitPartitionsElementsWithoutLossOrDuplication` **接受 null**，所以打不中。
- `DrainingBlockingQueue$Itr.forEachRemaining:1465`：预扫描在单元素尾部会 NPE。
- `TaskKey:63` / `ParName:56` `hashCode` 返回 0：仍满足 equals/hashCode 契约，
  只有性能差异，属等价变异。
- `MEMORY_ERROR` 1 个：`SlidingWindowSubmitter:100` `submitAll` 条件取反，minion
  因内存错误退出，**该变异没有有效判定**，需要单独重跑确认（两次运行都是同一个变异）。

### 9. NO_COVERAGE 53 个的分布

| 类 | 数量 | 主要方法 |
|---|---|---|
| DrainingBlockingQueue | 13 | element / peek / offer / awaitDrained / bulkRemove / unlinkIfPresent / unlinkLast / prepend |
| TaskGroupResult | 6 | orThrow / startTimeNanos / endTimeNanos / memberCount |
| Par | 5 | submitWhileOpen |
| VariableLinkedBlockingQueue | 4 | offer（L234 复查分支）/ drainTo |
| ExecutionPhaseHintFuture | 4 | run |
| Task | 3 | transformAsync / catchingAsync / withTimeout |
| TaskBatchResult | 3 | of / saturatedNanos |

其中 `Par.submitWhileOpen`、`TaskGroupResult.orThrow`、`TaskGroupResult.startTimeNanos/endTimeNanos/memberCount`
均为公开/半公开 API 且**从未被任何测试或生产代码读取**，优先级高于队列内部辅助方法。

## 建议的后续动作

按性价比排序：

1. **修 flaky 测试**（第 2 节）——成本最低，且让后续每一次变异得分的可信度都提高。
2. **把队列信号测试改为无界等待**（第 3 节）——预计可击杀 8 个 DBQ 漏唤醒变异 + 4 个
   VLBQ 中继变异，是唯一能实质性提升得分的动作；同时消除若干"测试预算 = 被测超时"的
   潜在 flake。
3. **补 `toArray(T[])` 的 `assertSame` 断言**（第 4 节）——两行测试，修掉一个真实契约违背。
4. **处理 `Serializable` 声明**（第 6 节）——补 `readObject` 或删除声明。
5. **删除无调用方方法**（第 7 节）——减少永久噪声。
6. **清理 pom 中失效的 `coverageTimeout` / `testPlugin`**（第 1 节）。
7. **补 TaskGraph 观察作用域内的 submit/TaskGroup 测试**（第 8 节）——覆盖面最大的一簇缺口。

## 备注

- 本次未新增或修改任何测试与源码；报告只记录现状与缺口，上述修正尚未实施。
- 全量套件在无并发干扰下的两次干净运行均为 572 通过 / 0 失败。
- `target/pit-reports/index.html` 可交互查看每个变异的源码定位。

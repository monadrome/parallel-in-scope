# `TaskCompletion` 公开工厂与 `SmartBlockingQueue.create(int)` 的处置

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未实现。`TaskCompletion.succeeded`/`failed` 与 `SmartBlockingQueue.create(int)` 仍是公开成员。

> 状态：**设计提案，待拍板**。对应最终清单 #7（小拍板）。基线：dev/v0.3.0 HEAD dd12641。
> 两项互相独立，可分别拍板。`queue` 包与 `SmartBlockingQueue` 的产物边界已由 `adr/0006` 定案，
> 本文不涉及。

## 0. 结论

1. **不把「用户自建 `TaskCompletion` 当测试替身」列为受支持用法**；删除 `TaskCompletion.succeeded`
   与 `failed`。`TaskCompletion` 与 `TaskBatchResult`/`TaskGroupResult` 一样，成为只由库产出的类型。
2. **删除 `SmartBlockingQueue.create(int)`。** 正容量用构造器，零容量交接直接写 `new SynchronousQueue<>()`。

两项都是 v0.2.0 已发布成员（`git show v0.2.0:…` 可见），删除是破坏性变更，迁移条目进
`docs/{en,zh}/migration-v0.3.md`。

## 1. `TaskCompletion.succeeded` / `failed`

### 1.1 现状

- 签名：`succeeded(String taskName, String unitId, int taskIndex, long submitTimeNanos,
  long startTimeNanos, long endTimeNanos, T result)`（7 个参数），`failed(…, TaskOutcome outcome,
  Throwable failure)`（8 个，`TaskCompletion.java:56-99`）。三个相邻的裸 `long` 是产出它的 runtime
  ticker 的读数（不是 epoch，可以为负）；`(0, 0)` 是「从未开始」的哨兵（`:268-279`）；
  `enqueued()` 用写死的 3 ms 阈值（`:21`）。
- 调用点：`src/main`、`demo`、`docs`、`README` 零调用；只有同包 `TaskCompletionTest` 用（13 处）。
  库自己构造记录走包私有的 `snapshot`/`memberSnapshot`/`withResult`/`groupSummary`。
- 公开面上**没有任何方法接收 `TaskCompletion`**；结果容器都没有公开构造器（`javap`）。用户自建的
  记录只能喂给用户自己的代码。
- 文档：用户指南只记载了 `ImmediateResult.failed(outcome, failure)`（`docs/en/user-guide.md:225`）；
  `TaskCompletion` 的工厂没有任何文档。`design/overdesign-analysis-code-only-2026-10-02.md` §10
  曾写「下游写单测需要构造结果对象，保留」，但没有指出消费方。

所以要拍板的问题是：用户为自己的消费代码（SLA 报告、指标、告警）自建 `TaskCompletion` 当测试
替身，是否受支持。

### 1.2 现状下用户能写出的最好代码

一个读 `completions()` 的慢任务报告器，用户想用确定的时序单测它：

```java
@Test
void reportsTasksOverBudget() {
    TaskCompletion<String> slow = TaskCompletion.succeeded(
            "price", "unit-1", 0, 0L, 1_000_000L, 900_000_000L, "p");
    TaskCompletion<String> fast = TaskCompletion.succeeded(
            "price", "unit-1", 1, 0L, 1_000_000L, 2_000_000L, "q");
    assertThat(reporter.slowTasks(Arrays.asList(slow, fast), Duration.ofMillis(500)))
            .containsExactly("price#0");
}
```

问题：submit/start/end 三个 `long` 互换不会编译失败；负时长被 `Math.max(0, …)` 静默截成 0；想写
「瞬时完成」的 `(0, 0)` 会被读成「从未开始」；测试替身把库内部的读数约定（哪个 ticker、与谁相减）
复制进了用户代码。实测（dd12641 编译产物）：submit 与 end 互换后 `totalTime()`、`executionTime()`
均为 `PT0S`，不报错。

### 1.3 改后的同一段代码

把判断抽成只读原始量的函数，单测它；`TaskCompletion` 到它的适配是一行，由真实 `Par.map`
（direct executor 即可）的集成测试覆盖：

```java
final class SlowTaskReporter {
    List<String> slowTasks(List<? extends TaskCompletion<?>> completions, Duration budget) {
        List<String> slow = new ArrayList<>();
        for (TaskCompletion<?> completion : completions) {
            if (completion != null && exceeds(completion.totalTime(), budget)) {
                slow.add(completion.taskName() + "#" + completion.taskIndex());
            }
        }
        return slow;
    }

    static boolean exceeds(Duration total, Duration budget) {
        return total.compareTo(budget) > 0;
    }
}

@Test
void budgetComparisonIsStrict() {
    assertThat(SlowTaskReporter.exceeds(Duration.ofMillis(899), Duration.ofMillis(500))).isTrue();
    assertThat(SlowTaskReporter.exceeds(Duration.ofMillis(500), Duration.ofMillis(500))).isFalse();
}
```

按结局判断的消费者（成功 / 失败 / `FAIL_FAST` / `TIMEOUT`）可以直接用真实 `Par.map` 加确定性的
body 得到真实记录；只有时序判断需要抽函数。

被消除的失败模式：相邻裸纳秒互换导致的静默错算（截成 0、`(0, 0)` 被读成从未开始）；测试替身与库
真实产出的记录随库演进而脱节（新增字段或时序语义变化时，手写替身照样通过）；公开面上一个无人
消费、7/8 个位置参数的工厂。

### 1.4 迁移路径

- 用户：把对 `TaskCompletion` 的判断抽成读 `Duration`/`TaskOutcome`/`Throwable` 的函数（1.3）；需要
  整条记录的断言改用真实 `completions()`/`members()`。
- 库内：`TaskCompletionTest` 改用同包可见的 `TaskCompletion.snapshot(...)`（参数顺序相同，多一个
  `outcome` 与 `failure`），断言不变。
- 文档：`docs/{en,zh}/migration-v0.3.md` 记一行「`TaskCompletion.succeeded/failed` 删除；
  `TaskCompletion` 只由库产出」及替代写法；`CHANGELOG.md`。

### 1.5 不推荐的方向及理由

- **列为受支持用法，保留现签名。** 7/8 个位置参数加三个相邻裸纳秒，是公开面上最易错的签名之一；
  保留等于把库内部的时间读数约定冻结为公开契约。
- **改成 builder 或 `Duration` 参数的工厂。** 为一个测试替身用例新增公开机制；`Duration` 形态要么
  丢掉 submit/start/end 三个时刻，要么另造虚构时间原点，成本与收益不成比例。若出现真实需求再
  以此为起点重开。
- **等 #21（公开 TimeSource）。** #21 若落地，用户可注入假 ticker 让真实执行产出确定时序的
  `TaskCompletion`，测试替身就更没有必要；若不落地，1.3 的写法照样成立。本项不依赖 #21。
- **只补 javadoc。** 不解决易错签名，也不回答谁消费它。

`ImmediateResult.succeeded/failed` 不受影响：它是与执行生命周期无关的值容器，有文档化的用户
用途（`asFuture()` 适配、自建结果交给下游）；`TaskCompletion` 是某个 runtime 的观测记录。区分
依据是「值」与「观测」，不是「能否伪造」——库里没有接收方，伪造论证不成立（FINAL 分歧裁决、
opus Q6）。

## 2. `SmartBlockingQueue.create(int)`

### 2.1 现状

```java
public SmartBlockingQueue(int capacity) {
    if (capacity <= 0) {
        throw new IllegalArgumentException("capacity must be positive");
    }
    this.delegate = new VariableLinkedBlockingQueue<>(capacity);
}

public static <T> BlockingQueue<T> create(int capacity) {
    if (capacity <= 0) {
        return new SynchronousQueue<>();
    }
    return new SmartBlockingQueue<>(capacity);
}
```

（库源码摘录，`SmartBlockingQueue.java:40-45, 93-98`。）同一个整数，构造器当调用方错误（IAE），
工厂当队列类型切换。工厂的静态类型是 `BlockingQueue<T>`，要调 `setCapacity`/`capacity()` 得强转，
`capacity <= 0` 时强转抛 `ClassCastException`。

| | `create(n)`，`n > 0` | `create(n)`，`n <= 0` |
|---|---|---|
| 运行时类型 | `SmartBlockingQueue` | `SynchronousQueue` |
| `rejectEnqueue` / `CPU_BOUND` | 生效（`offer:79`） | 不读（`ExecutorRuntime.rejectEnqueueEffective:98-101` 为 false） |
| `starvationProne`（嵌套死锁诊断） | true（`queueCapacity > 0`，`:110-112`） | false |
| 默认选项下的元素 | 入队等待 | 不排队：池扩到最大后交给拒绝策略 |

每一列单独看都正确（`SynchronousQueue` 确实不会饥饿）。问题在于同一个整数悄悄切换两套语义，
而同名类型的构造器对同一个整数拒绝。

调用点：`src/main`、`demo`、`docs` 零调用；用户指南写的是 `new SmartBlockingQueue<>(64)`
（`docs/en/user-guide.md:99`）；`adr/0006` 写明「库自身从不构造它——它由用户自行实例化并安装到自己的
`ThreadPoolExecutor` 上」。测试里 6 个文件用它，其中 5 个只传正数。

### 2.2 实测

dd12641 归档副本，同包探针，`ThreadPoolExecutor(4, 4, …, queue)` 默认 `AbortPolicy`，64 个默认选项
（`IO_BOUND`、`rejectEnqueue=false`）元素，每个睡 50 ms：

| 构造 | 运行时类型 | `starvationProne` | `rejectEnqueueEffective` | 结局 |
|---|---|---|---|---|
| `new SmartBlockingQueue<>(0)` | — | — | — | 构造时抛 IAE `capacity must be positive` |
| `SmartBlockingQueue.create(0)` | `SynchronousQueue` | false | false | `SUBMISSION_FAILURE=60, FAIL_FAST=4` |
| `SmartBlockingQueue.create(64)` | `SmartBlockingQueue` | true | true | `SUCCESS=64` |

`create(0)` 不在构造时失败，而在第一次有并发压力时批量失败。默认选项下**没有任何告警**：
`Par.warnIfRejectEnqueueInert` 只在调用方请求了 `rejectEnqueue` 时检查（`Par.java:543-546`），#9 落地后
也只多覆盖显式 `CPU_BOUND`。

### 2.3 现状下用户能写出的最好代码

最好的写法就是绕开工厂、用构造器（用户指南的写法）——工厂本身是陷阱。踩到它的形态是容量来自配置：

```java
BlockingQueue<Runnable> queue = SmartBlockingQueue.create(config.getInt("pool.queueCapacity"));
ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, queue);
```

配置写成 0（或负数）时不报错，换成另一种队列。

### 2.4 改后的同一段代码

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(config.getInt("pool.queueCapacity")));
```

配置错误在启动时以 IAE 失败。确实想要「0 表示零容量交接」时，把选择写在调用点：

```java
int capacity = config.getInt("pool.queueCapacity");
BlockingQueue<Runnable> queue = capacity > 0 ? new SmartBlockingQueue<>(capacity) : new SynchronousQueue<>();
```

被消除的失败模式：配置错误静默换队列；默认选项下零告警、压力下批量 `SUBMISSION_FAILURE`；三项语义
（`rejectEnqueue`/`CPU_BOUND` 是否生效、`starvationProne`）藏在一个整数背后；为调 `setCapacity` 强转
时的 `ClassCastException`。删掉工厂后，「池用的不是 `SmartBlockingQueue`」只能是用户显式写出来的
选择，#9 的告警语义也随之更准确。

### 2.5 迁移路径

| 现在 | 改后 |
|---|---|
| `SmartBlockingQueue.create(n)`，`n > 0` 是常量 | `new SmartBlockingQueue<>(n)` |
| `SmartBlockingQueue.create(0)` 或负常量 | `new SynchronousQueue<>()` |
| `SmartBlockingQueue.create(n)`，`n` 来自配置 | `new SmartBlockingQueue<>(n)`；需要零容量语义时写 2.4 的显式分支 |

库内：5 个测试文件的 `create(正数)` 改构造器；删 `SmartBlockingQueueTest` 的工厂用例。文档：
`docs/{en,zh}/migration-v0.3.md` 一行加上表；`CHANGELOG.md`。

### 2.6 不推荐的方向及理由

- **让工厂与构造器契约一致（`n <= 0` 也抛 IAE）。** 工厂变成返回类型更弱的构造器（丢掉
  `setCapacity`/`capacity()`），没有存在理由。
- **改名保留分支（如 `orHandoff(int)`）。** 名字能提示，但三项语义切换仍藏在一个整数里；调用点直接
  写 `new SynchronousQueue<>()` 更清楚，也不需要库为此留一个公开成员。
- **只改 javadoc。** javadoc 已经写明「`capacity <= 0` 返回 `SynchronousQueue`」（`:86-87`），问题
  不是没写，而是同一参数承载两套契约。

## 3. 六问

两项都是纯删除，不引入新类型、新工厂或新 SPI，`first-principles.md` §四 不适用。

## 4. 待拍板点

1. **「用户自建 `TaskCompletion` 当测试替身」是否受支持。** 推荐否，删 `succeeded`/`failed`（第 1 节）。
2. **删除 `SmartBlockingQueue.create(int)`。** 推荐删（第 2 节）。
3. **是否与 #4（`has*()`、`CancellationToken`）、#10 合成一次公开面收敛变更**，共用一组迁移条目
   （`BACKLOG.md`「建议的起步顺序」第 3 步原本就是这样排的）。推荐合成。

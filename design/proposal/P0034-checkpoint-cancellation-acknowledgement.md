# 检查点取消确认：按 token 归因、按来源静默

## 蒸馏摘要

- 核心出发点：取消提交之后，body 在检查点退出是对取消的**正确响应**，目的是结束线程占用、
  不再执行没有意义的后续操作。此时任务 future 大多已被级联 `cancel(true)` 终态，检查点异常
  不再承载结果。现行内核把它与真正的迟到失败同等对待：`ExecutionPhaseHintFuture.run()` 中
  `setException` 失败且 future 已取消时一律记 `WARNING "failed after cancellation"`。正确使用
  `Checkpoints` 的程序因此在每次取消时为每个运行中的 body 产生一条告警。
- 归因不变：检查点异常抢先落定时，现有 `TokenOutcomes.causedByCancellation` 改道已按 token 归因；
  future 已取消时 outcome 已由 token 决定。**不新增 `TaskOutcome`**（见 §4 待决项）。
- 拟议 MUST / MUST NOT：
  - `Checkpoints` 仅在**读到已提交的取消 token**（含 deadline backstop 自行提交 `TIMEOUT`）时，
    抛出带库来源标记的取消确认异常；标记 MUST 由库在构造时设置，用户代码 MUST NOT 能构造带标记的实例。
  - 内核记录迟到异常时，带标记且无 suppressed 的确认 MUST NOT 记 `WARNING`（可记 `FINE`）；
    带 suppressed、不带标记或包装后的异常 MUST 照常记 `WARNING`。
  - 区分 MUST 依据异常的库来源，MUST NOT 依据异常类型：body 自抛或 close 抛出的
    `InterruptedException` / `CancellationException` 继续按现行规则记录。
  - 公开面收敛：移除 `checkRunnable`、`checkSupplier`（把用户异常类型映射为取消，违背按 token 归因）、
    `checkpoint(String, boolean)`（名字校验只制造出错机会）、`sleep(long)`（与 `checkSleep` 重复）。
- 被否决方向：按异常类型过滤取消形状的迟到异常（同步出口契约 §7 的 R4 已证明会隐藏独立的 close 失败）；
  新增 `TaskOutcome` 表示"body 读到取消"（把同一个取消事实按竞态拆成两个名字，见 §4）。

## 1. 失败模式与改前最佳代码

```java
par.map(ids, id -> {
    for (Page page : pages(id)) {
        Checkpoints.checkpoint();   // 取消后在这里退出，不再拉下一页
        consume(page);
    }
    return id;
}, BatchOptions.timeout("crawl", Duration.ofSeconds(2)));
```

deadline 到达时，每个运行中的元素都在检查点按设计退出；其中 future 已被级联取消的元素各产生一条
`WARNING ... failed after cancellation`，日志里是一条 `LeanCancellationException`。用户今天能做的是调高
`ExecutionPhaseHintFuture` 的 logger 级别，代价是同时隐藏真正的迟到 close 失败。

同一 API 的另一处陷阱：

```java
Checkpoints.checkSupplier(() -> client.call(), SocketTimeoutException.class);
```

它把 `SocketTimeoutException` 转成 `CancellationException`。token 未提交取消时，结果仍归 `USER_FAILURE`，
`orThrow()` 抛出的 CE 却与框架取消同类型——方法名承诺的"取消"不成立。

## 2. 改后

第一段用户代码不变；取消时不再为确认退出产生告警，真实的迟到失败（例如 close 抛出并以 suppressed
挂在确认上）仍然告警。第二段改为用户自己的异常处理：超时作为业务失败抛出，或在需要停止时调用
`Checkpoints.checkpoint()` 读 token。

## 3. 机制草图

- 检查点私有助手在 token 已提交时构造带标记的 `LeanCancellationException`（或其包私有子类）；
  中断转译路径（阻塞适配器捕获 `InterruptedException`）先读 token：已提交才带标记，
  未提交则保持现行无标记异常与 `USER_FAILURE` 归因。
- `run()` 的迟到失败日志前增加来源判定；判定只读异常对象自身，不读 token，避免与级联竞态。

## 4. 待决项

**是否新增 `TaskOutcome`。** 建议不新增。理由：

1. 检查点确认发生时取消已经提交；outcome 由 token 决定（`TIMEOUT`、`FAIL_FAST`、`GROUP_CANCELLED` 等），
   这是唯一的归因事实。
2. 确认是落定前还是落定后到达取决于与级联的竞态；新枚举会让同一次取消在不同元素上显示成不同名字。
3. "body 已按取消退出"属于 body 退出事实，已由 `bodyCompletionConfirmed()` / `unfinishedBodies()` 表达。

若需要观测"有多少 body 是通过检查点确认退出的"，应作为观测计数另行提出，不进入 outcome 词汇表。

## 5. 迁移

- 移除的四个方法：`checkRunnable` / `checkSupplier` 改为业务异常处理或显式 `checkpoint()`；
  `checkpoint(String, boolean)` 改为 `checkpoint()`；`sleep(long)` 改为 `checkSleep(long, TimeUnit.MILLISECONDS)`。
- 日志：依赖 `failed after cancellation` 告警统计取消次数的监控会看到数量下降；取消计数改读 `outcomeCounts()`。
- 0.4 迁移指南（en/zh）与 CHANGELOG 同步记录。

## 6. 验证计划

- 检查点在 future 已取消后退出：无 `WARNING`；在确认上附加 suppressed close 失败：仍 `WARNING` 且保留原异常。
- 独立 close 抛 `InterruptedException` / `CancellationException`（R4 回归）：仍 `WARNING`。
- token 未提交时阻塞适配器被中断：异常无标记、`USER_FAILURE`，告警行为不变。
- 反向验证：去掉来源判定或改为按类型判定时，上述对应用例分别变红。

对照来源：`Checkpoints`、`ExecutionPhaseHintFuture.run()`、`TokenOutcomes`；
[同步出口契约](../synchronous-scope-exit.md) §4、§7；[中断契约](../interruption-contract.md)。接口草图未编译、未实现。

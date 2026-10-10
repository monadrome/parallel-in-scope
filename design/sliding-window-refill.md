# 滑窗补窗契约（事件驱动）

约束 `SlidingWindowSubmitter`：把一批已 prepared 的元素按窗口宽度送进业务 executor。本文件是
当前机制的权威描述；[interruption-contract.md](interruption-contract.md) §7.3 保留的是旧提交线程
缺陷的历史记录，机制细节以本文为准。

## 机制

1. 调用方线程先认领初始窗口（`min(元素数, parallelism)`），逐个 handoff 并返回 `TaskBatch`；
   调用方不等待补窗。
2. 每个已提交元素的 completion listener 在元素终态时释放自己占用的窗口槽，并自己认领下一个
   尚未提交的索引（若仍有空槽和剩余元素）。补窗因此发生在**让元素到达终态的那条线程**上。
3. 单条批次内用一个非可重入的认领闸（CAS 布尔）串行化补窗：inline completion（direct executor
   或饱和 `CallerRunsPolicy` 在完成线程上直接跑下一个 body）会让同一条线程在闸持有者的循环里
   继续认领，而不是递归。
4. 批次终态由 `SettableFuture`（`submitCanceller`）发布：全部元素认领完成为成功，handoff 失败
   为共享提交失败，取消回调为剩余未认领元素的放弃。

## MUST

- MUST 在调用方线程上完成初始窗口的 handoff，且在 handoff 前已完成元素视图与 token 绑定
  （bind-before-submit 不变）。
- MUST 只让每个索引被恰好一条路径认领：正常补窗、取消放弃、handoff 失败放弃三者互斥。
- MUST 让取消（cancel）先认领全部未认领索引：取消路径自身永不提交元素，正在 handoff 的补窗
  就地处置自己已认领的索引——提交前发现已终态则放弃，已经提交的元素照常运行并对外呈现真实结果
  （见"取消与窗口一致性"）。
- MUST 保持窗口上界：任一时刻该批次在飞元素数不超过 `parallelism`。
- MUST 在补窗 handoff 失败时沿用与初始窗口相同的共享裁决（其后全部元素 `SUBMISSION_FAILURE`
  且保留原始 cause）。

## MUST NOT

- MUST NOT 为每个批次驻留一条等待提交线程；补窗不得依赖阻塞队列上的 `take()`。
- MUST NOT 把补窗放到唯一 deadline timer 线程上（handoff 可能间接阻塞）。
- MUST NOT 用固定池替换业务池来"限流"；`execute()` 可能通过 direct executor 或
  `CallerRunsPolicy` 同步运行用户 body，嵌套活性依赖既有机制。
- MUST NOT 让日志失败改变补窗终态路径（沿用 quiet logging）。

## 资源上界

框架不再为等待补窗的批次创建线程：线程成本从 O(并发有限窗口批次数) 降为 O(1)（只取决于业务
executor 自身的线程）。回归 `SlidingWindowResourceBoundTest` 用公开入口锁住该上界，反向验证在
基线 `346d389` 上得到 24 条 `submitRemaining` 等待线程、在新实现上为 0。

## 取消与窗口一致性

`cancelDuringHandoffKeepsClaimedElementConsistent` 锁住：补窗窗口内取消时，已认领元素照常执行
并对外呈现真实结果，未认领元素以 `SUBMISSION_FAILURE` 放弃；`repeatedSubmitAndCancelNeverReportsARanTaskAsUnsubmitted`
多轮打同一条竞态。该测试把取消点门控在已认领元素的 `execute()` 返回之后（handoff 已完成、body
尚未运行）；`settled` 检查与 `execute()` 之间的 TOCTOU 窗口不在此用例门控范围内，但该窗口下的
行为是同一语义（已认领元素照常运行）。

## 残留

- 独立评审：R2 改造尚未取得项目要求的异模型/harness 评审。
- 每个历史（已终态）子批次仍会让父 token 的 listener 栈累积一个 `ParentLink` 节点常数——见
  cancellation-propagation §8 的残留记录。

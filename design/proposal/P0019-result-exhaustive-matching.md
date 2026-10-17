# 冻结结果的穷尽匹配

## 蒸馏摘要

- 核心出发点：评估能否让调用方在编译期完整处理成功与非成功结果，减少遗漏归因或误用
  failure 的分支；收益必须超过现有 valueOrThrow/outcome 消费方式和新增类型的成本。
- 不变量与拟议 MUST：匹配 MUST 作用于现有 ImmediateResult，保持成功 null、原 cause、
  中断独立读取和冻结归因；MUST NOT 等待、取消、重新分类或把 Future 终态当 body 退出。
  若承诺穷尽，未覆盖的分支 MUST 在编译期可见，并明确未来增加 outcome 时的兼容规则。
- 被否决方向及理由：不新增平行快照类型，不借恢复历史提案重开公开 async/submit 入口。
  单任务结果获取是否需要新入口仍待举证；已有单元素 map 和单成员组能表达同步执行。

## 恢复依据与当前实现

`c4ddfc7` 的 BACKLOG 待决策条目与 PR #56 正文保留了原未跟踪稿
`design/task-result-exhaustive-matching-proposal.md` 的两个独有方向：typestate 匹配链、
单任务公开快照获取入口，并要求收敛到 ImmediateResult 词汇。原稿与其处置稿已于 2026-10-27 按
维护者要求入库为 [design/analysis/task-result-exhaustive-matching-proposal.md](../analysis/task-result-exhaustive-matching-proposal.md)
与 [design/analysis/task-result-exhaustive-matching-disposition-proposal.md](../analysis/task-result-exhaustive-matching-disposition-proposal.md)，
二者都是分析输入而非已批准设计；以下保留目标与重验条件，不补造历史签名。

原稿关联的 failure 中断敏感问题已按
[中断契约](../interruption-contract.md) 窄修；公开 ImmediateResult.failure 已是
纯数据查询。单次内部分类归 [P0006](P0006-execution-protocol-consolidation.md)，不能把
这些修复的必要性当作新增匹配 API 的证据，也不能覆盖现行落定 pin。

## 最佳现有写法与拟议消费方式

当前公开调用已经直接返回冻结结果；单个输入也无需拿到运行句柄：

```java
ImmediateResult<String> result = par.map(Collections.singletonList(id), this::load,
        BatchOptions.timeout("load", timeout)).results().get(0);
String text;
try {
    text = renderSuccess(result.valueOrThrow());
} catch (ExecutionException failed) {
    text = renderFailure(result.outcome(), failed.getCause());
}
```

改后同一 result 的消费目标如下，使用伪代码表示能力，方法名与链顺序尚未设计：

```text
text = match result
    success(value)                 -> renderSuccess(value)
    nonSuccess(outcome, sameCause)  -> renderFailure(outcome, sameCause)
```

typestate 候选要求处理全部承诺的分支后才能得到 text。必须先选择“成功/非成功两分支”
还是“每种终态各一分支”；前者已有 try/catch 可表达，后者更精确但会增加链接口、迁移和
扩展成本。需用真实消费代码证明漏分支的失败模式，不能只以少写 try/catch 证明值得新增。
RUNNING 不是 ImmediateResult 的合法输入；非成功的 cause 与业务异常类型不能互相推断。

## 边界、取舍与验收

按 [第一性原理](../first-principles.md) 六问，本候选只减少结果消费遗漏，复用现有冻结数据，
不增加执行管道、上下文或取消状态。优先比较不加 API、应用层 helper、两分支函数与逐终态
typestate 链；如果没有比现有写法更强的错误预防，维持现状。

单任务获取先明确下游无法用单元素 map/单成员组完成的具体需求。若需要便利入口，只评估
同步返回现有 ImmediateResult 的形状；不得恢复运行句柄或把 TaskCompletion 当第二结果类型。
当前没有已选定的签名或破坏性迁移；新 API 若改变现有入口，必须另列完整迁移路径。

验收需在 Java 8 外部消费者验证泛型推导、成功 null、全部终态、cause 身份、中断标志和
遗漏分支的编译失败；同时验证完整分支可编译，不能以内部测试通过代替类型层面的承诺。
本轮只恢复提案，不实现匹配链或新增入口，不把这些验收项报告成已通过。

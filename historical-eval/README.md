# Historical repair evaluation

这个目录保存从历史修复提交构建的盲修复评测样本。LLM 输入只位于每个样本的 `prompt/`，验证器使用的修复补丁和隐藏测试位于 `oracle/`，正式评测时不应把 `oracle/` 挂载给 LLM。

## 第一个黄金样本

`BUG-0001` 来自 `a5c2d823`：`Duration.toNanos()` 在极大或极小 duration 上会抛出 `ArithmeticException`，导致 deadline budget 转换失去既有的“无界/已过期”语义。样本的 buggy source 来自其父提交 `aeba97f3`，隐藏测试覆盖可表示值、正溢出和负溢出。

验证器从历史 buggy commit 创建临时工作区，复制隐藏测试，然后可选地应用候选 unified diff。隐藏测试不会写入 LLM 工作区。

```bash
# 基线：应失败（buggy source 不含修复 API）
./historical-eval/tools/verify-sample.sh

# 使用历史正确补丁：应通过
./historical-eval/tools/verify-sample.sh historical-eval/samples/BUG-0001/oracle/fixed.patch
```

`oracle/` 只供评测器使用；提交给 LLM 的内容仅包括 `prompt/task.md` 和 `prompt/src/`。

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

## 历史候选盘点

`tools/inventory-fixes.py` 扫描所有分支的非 merge `fix` 提交，按生产代码、测试、工具和文档路径分类。它只把包含生产代码修改的提交标记为主候选；文档-only、测试-only 和 tooling-only 提交会保留在目录中，便于审计但不会自动进入 LLM 样本。

```bash
python3 historical-eval/tools/inventory-fixes.py \
  -o historical-eval/catalog/fix-inventory.jsonl
```

当前基于评测分支所指向的历史，共盘点 419 个 `fix` 提交，其中 299 个含生产代码修改、17 个文档-only、4 个测试-only、62 个 tooling-only。评测目录自身的提交会被自动排除。连续修复仍需在生成样本前按根因聚类，不能把这个清单直接当作最终样本数量。

## 连续修复去重

`tools/dedupe-fixes.py` 按生产代码 diff 的 SHA-256 对候选提交去重。同一修复在多个分支或镜像提交中的重复记录会合并，只保留一个代表提交供后续样本生成；不同父版本或不同代码 diff 不会因为标题相同而误合并。

```bash
python3 historical-eval/tools/dedupe-fixes.py \
  historical-eval/catalog/fix-inventory.jsonl \
  -o historical-eval/catalog/fix-groups.jsonl
```

当前 270 条“生产代码 + 回归测试”候选被归并为 103 个不同生产 diff。这个结果仍需人工检查根因和测试稳定性，之后才进入正式盲测集。

## 多模型比较

模型只需要把修复结果保存为 unified diff；比较器不会把隐藏测试日志返回给模型，只写入是否通过和耗时。多个模型可以在同一黄金样本上批量运行：

```bash
python3 historical-eval/tools/compare-patches.py \
  --candidate historical-fix=historical-eval/samples/BUG-0001/oracle/fixed.patch \
  --candidate model-a=/path/to/model-a.patch \
  --candidate model-b=/path/to/model-b.patch \
  -o /tmp/bug-0001-results.json
```

当前已有两条黄金样本：

- `BUG-0001`：deadline duration 溢出；
- `BUG-0002`：动态队列容量收缩后的边界约束。

比较第二条样本时增加 `--sample BUG-0002`。两条样本都已确认 buggy baseline 失败、历史正确补丁通过。

- `BUG-0003`：带有中断标志的调用线程读取已完成失败 Future 时，仍应得到原始失败原因。

## LLM 输入导出与防泄漏检查

正式给模型的内容从样本的 `prompt/` 导出，不直接暴露样本目录。导出包只包含中性任务和修复前源码，并在生成后拒绝 `oracle`、`hidden`、`fixed.patch` 等评测器文件：

```bash
python3 historical-eval/tools/export-prompt.py BUG-0001 -o /tmp/BUG-0001-prompt.zip
unzip -l /tmp/BUG-0001-prompt.zip
```

`oracle/` 只能由验证器读取，不能挂载到 LLM 工作区。

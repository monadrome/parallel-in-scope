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

## 黄金样本总体验收

增加样本后，用总体验收入口确认所有历史正确补丁仍能通过各自隐藏测试：

```bash
python3 historical-eval/tools/verify-all-gold.py \
  -o /tmp/golden-results.json
```

只要有一条黄金样本失败，命令就以非零状态退出。

## 从历史修复准备样本骨架

从候选 fix 生成样本骨架时，只提取父提交中的生产源码和指定生产 diff；任务描述与隐藏测试仍必须人工审查后才能进入正式集：

```bash
python3 historical-eval/tools/prepare-sample.py \
  BUG-XXXX <fix-commit> \
  --source src/main/java/.../Example.java \
  --test-class ExampleGoldenTest \
  -o historical-eval/samples/BUG-XXXX
```

生成结果会标记为 `needs-curation`，不能直接作为黄金样本运行。

## 103 条去重修复的逐条盘点

`tools/review-fix-groups.py` 将 103 个去重组展开为一行一条的复核表：

```bash
python3 historical-eval/tools/review-fix-groups.py \
  historical-eval/catalog/fix-groups.jsonl \
  historical-eval/catalog/fix-inventory.jsonl \
  -o historical-eval/catalog/fix-review.csv
```

当前表中每条记录都有代表提交、重复成员数、生产文件、测试文件、自动类别和复核状态：

```text
103 条总记录
98 条初步候选
5 条需要优先排查的 review/文档/验证噪声
3 条已验证黄金样本
100 条待人工复核
```

类别分布：`executor` 21、`cancellation` 19、`deadline` 14、`batch/task-group` 14、`graph/observation` 14、`queue` 11、`interruption` 5、`context/propagation` 5。

## 103 组逐条盘点

`tools/report-groups.py` 会把 103 个唯一生产 diff 展开成 Markdown 和 JSONL 两份审阅清单，包含代表提交、主题分类、难度启发式、生产文件、重复落地次数和样本状态：

```bash
python3 historical-eval/tools/report-groups.py \
  historical-eval/catalog/fix-groups.jsonl \
  historical-eval/samples \
  -o historical-eval/catalog/fix-groups-report.md
```

报告中的 `gold` 只有已经人工整理并通过隐藏测试的样本；`needs-curation` 需要继续确认根因、最小上下文、隐藏测试和复现稳定性。

## 每组历史 sandbox 验证

`tools/run-group-sandbox.py` 对排序清单中的每个 group 独立创建临时目录：

1. 从代表修复的父提交恢复 buggy 工程；
2. 注入代表修复提交中的回归测试；
3. 运行 buggy baseline；
4. 从修复提交恢复 fixed 工程并运行同一批测试；
5. 记录 `verified`、`baseline-pass`、`fixed-fail`、`no-test-files` 或 `sandbox-error`。

```bash
python3 historical-eval/tools/run-group-sandbox.py \
  historical-eval/catalog/fix-groups-vote-ranking.jsonl \
  -o historical-eval/catalog/sandbox-results.jsonl
```

每次运行使用独立临时目录，日志单独保存，不会写回源码工作区。

## 全量 sandbox 结果

本次按三 agent 难度排序对 103 组逐一运行了独立 before/after sandbox：

```text
verified:      88
baseline-pass: 12
fixed-fail:     3
```

只有 `verified` 适合进入后续 LLM 修复能力评测；`baseline-pass` 表示历史回归测试没有捕获父版本缺陷，`fixed-fail` 表示历史代表提交在其父版本上无法稳定构建或通过测试，二者需要人工复核。

## Codex + gpt-5.6-terra 模型结果

模型评测使用独立临时 prompt 工作区，参数固定为：

```text
agent: codex
model: gpt-5.6-terra
reasoning_effort: medium
```

当前 3 条正式黄金样本的结果保存在 `catalog/model-runs/`：3 次编译均通过，隐藏测试 2 次通过、1 次失败。模型候选补丁与历史 oracle 分开保存。

模型候选补丁可用 `tools/evaluate-group-patch.py` 在对应历史 group 的独立 sandbox 中验证：

```bash
python3 historical-eval/tools/evaluate-group-patch.py \
  historical-eval/catalog/fix-groups-vote-ranking.jsonl \
  <group-id> \
  /path/to/candidate.patch \
  -o /tmp/model-result.json
```

`tools/run-codex-group.py` 可按投票排名启动独立 Codex 会话。第 1 组试跑已完成：模型编译/会话成功，但历史回归测试为 `model-fail`，结果保存在 `catalog/model-runs/all/001-abaf2bf6e921d7eb/`。

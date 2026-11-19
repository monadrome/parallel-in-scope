# core JavaDoc 知识图谱

> Committed at the user's request on 2026-11-19. Purpose: preserve the core JavaDoc
> knowledge-graph analysis (tooling plus generated artifacts) as a dated snapshot.
> This is an analysis input, not a live contract; it does not constrain the
> implementation. Regenerate with the commands at the bottom before relying on it.

分析稿：解析 `core/src/main/java` 下全部类型（含嵌套、包私有、私有类）的类级
JavaDoc，提取对外指针并构建有向图。一个"指针"是读者理解该类所需跳转的另一处知识：

- `{@link Target}` → 边类型 `link`（显式引用，最强）
- `@see Target` → 边类型 `see`
- 正文提及其他已知类名 → 边类型 `mention`（最弱）

`#member` 形式的自引用指针（如 `{@link #bind}`）不产生新节点，不计入边。
JDK / 第三方类型不作为节点。泛词（Kind/Outcome/State/Status）不作为 mention 解析（历史误报，
见 `edge-taxonomy.json` 的 dropped 节）。每条边带 `reason`（指针出处句子）与 `rel`
（人工归类关系，单一事实源 `edge-taxonomy.json`；重跑构建会对失配边告警）。

## 结果（2026-11-19 的 dev/v0.5.0 快照；终止模型取代 TaskClassification 之后）

- 115 个类节点；文档指针 196 条（类级 114 + 成员级 82）；代码依赖边 450 条（加权）。
- 38 个弱连通分量：一个 77 节点主图（核心：`Par`），其余为小图或孤立类。
- 文档枢纽：`Par`(18)、`ParRuntime`(16)、`TaskType`(11)；
  代码枢纽：`Par`(15)、`MultiTaskContext`(14)、`CancellationToken`(14)。
- 近期重构在图上的留痕：幻影标签 `TaskUnit`/`TaskGroupRun` → `TaskId` 封闭层级（e17423fb）；
  归因词汇 `TaskClassification` → `TaskTermination`+`CancellationReason`+`FailureCause`
  终止模型（562e7f47），`TaskStatus` 更名 `TaskState`。
- 对照差异：`ExecutionPhaseHintFuture → ExecutionPhase`（代码多次引用、文档零指针）等
  codeOnly 边暴露"文档未记录的耦合"；docOnly 边多为概念性/词汇指针。

## 可视化功能（index.html，自包含离线可用）

- **三种视图**：文档指针 / 代码依赖（线宽 ∝ √权重）/ 对照差异（绿=一致、蓝=仅文档、橙=仅代码）。
- **两种布局**：力导向；分层（按 SCC 凝聚图的阅读层级自上而下，枢纽在底部）。
- 节点大小 = 当前视图内的被引次数；深色边框 = 嵌套类。
- 文档视图过滤：link/see/mention、成员级指针开关（默认关）、孤立类开关。
- 点击节点看出/入清单；点击边 toggle 看出处句、关系类别、引用成员名或代码权重。
- 夜间模式（跟随系统，可手动切换并记忆）。

## 文件

- `graph.json` — 全部数据（节点含 SCC/层级，文档边含出处/关系/成员，代码边含权重）。
- `edge-taxonomy.json` — 关系分类单一事实源（仅类级边）；`edge-taxonomy.md` — 逐条分类文档。
- `build_graph.py` — 解析器（三类边 + SCC 层级）；`make_html.py` — 渲染器；
  `make_taxonomy.py` — 分类文档生成器。
- `vis-network.min.js` — 渲染依赖的本地副本。

## 重新生成

```bash
python3 design/analysis/knowledge-graph/build_graph.py
python3 design/analysis/knowledge-graph/make_taxonomy.py
python3 design/analysis/knowledge-graph/make_html.py
open design/analysis/knowledge-graph/index.html
```

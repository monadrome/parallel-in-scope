#!/usr/bin/env python3
"""Render edge-taxonomy.md from graph.json + edge-taxonomy.json."""

import json
import os
from collections import defaultdict

ROOT = os.path.dirname(os.path.abspath(__file__))

ANALYSIS = {
    "entry": "几乎全部指向 `Par`/`ParRuntime`：文档把\"我从哪里来\"统一锚定在两个入口上，"
    "这是向心结构的来源。读者从任何类出发都能跳到入口，而入口自身几乎不再外指。",
    "options": "配置链清晰：`BatchOptions`/`TaskOptions`（用户声明）→ `UnitSpec`（规格）→ "
    "`MultiTaskContext.Resolution`（解析为可执行值）。队列侧的拒入策略也从选项读取。",
    "result": "最大的簇之一。核心不变量反复出现：**调用方只拿到冻结视图**"
    "（`ImmediateResult`/`TaskExitObservation`/`TaskGroupResult`），`TaskFuture` 永不暴露。"
    "`TaskExitObservation` 是快照的唯一发布形式。",
    "builder": "分阶段链的互指是结构性的（`GroupStart`↔`GroupStep` 互相定义对方契约），"
    "属于环簇中可豁免的一类；`ParRuntime`↔`Builder` 同理（组合根模式）。",
    "vocabulary": "归因词汇（`TaskTermination`+`CancellationReason`+`FailureCause` 终止模型，"
    "已取代 `TaskClassification`）与 `TaskType`（诊断声明）是两套公共词汇。"
    "多处文档刻意声明\"仅诊断、不改变执行\"——词汇与语义的边界被反复强调。",
    "identity": "`TaskId`/`TaskKey`/`ParId`/`ExecutorIdentity` 互相区分\"谁是谁的标识、谁不是谁\"。"
    "e17423fb 重构后幻影标签消失，`TaskId` 改为封闭层级（嵌套 `Unit`/`GroupRun`），"
    "原先的 3 节点 6 边全互指三角收敛为 `TaskId` 指向两个种类的 2 条边——语义权威进一步集中到 `TaskId`。",
    "observability": "三个诊断面共享一个模式：常开、runtime 级、报告经 `ParRuntime` 读取"
    "（等待图 / 负载匹配 / 饥饿采样 + 类型不匹配记忆）。指针多指向 `ParRuntime` 这一读取入口。",
    "kernel": "取消/生命周期/归因的内部机制网。`TokenOutcomes` 是归因映射的单一实现"
    "（token 终态 → `CancellationReason`），被 `TaskGroup`/`TaskFuture` 等消费侧共同指向；"
    "终止模型重构后映射目标从 `TaskClassification` 变为 `CancellationReason` 及其 `Kind`。",

    "cancellation": "独立小图的全部内容：检查点确认在包内是标记类型，包外是 `LeanCancellationException`。",
    "implementation": "结构性指针：嵌套类型归属外层、唯一实现声明、冻结产物的创建点。"
    "这类指针回答\"代码在哪里\"而非\"概念是什么\"。",
}

ORDER = [
    "entry", "builder", "options", "result", "vocabulary", "identity",
    "kernel", "cancellation", "observability", "implementation",
]


def main():
    graph = json.load(open(os.path.join(ROOT, "graph.json"), encoding="utf-8"))
    tax = json.load(open(os.path.join(ROOT, "edge-taxonomy.json"), encoding="utf-8"))
    cats = tax["categories"]
    gloss = tax["edges"]

    class_edges = [e for e in graph["edges"] if e.get("level", "class") == "class"]
    member_edges = [e for e in graph["edges"] if e.get("level") == "member"]

    by_rel = defaultdict(list)
    for e in class_edges:
        by_rel[e.get("rel")].append(e)

    out = []
    out.append("# core JavaDoc 指针关系分类（逐条）\n")
    out.append(
        "对知识图谱中全部 %d 条**类级**有向边逐条归类（另有 %d 条成员级指针未归类，"
        "见 index.html 的\"成员级指针\"开关）。关系类型共 %d 类；"
        "一条边只归一个主类（以指针出处句子的语义为准）。\n"
        % (len(class_edges), len(member_edges), len(ORDER))
    )
    out.append("## 总览\n")
    out.append("| 关系 | 边数 | 含义 |")
    out.append("|---|---|---|")
    for rel in ORDER:
        out.append("| %s（%s） | %d | %s |" % (
            cats[rel]["label"], rel, len(by_rel[rel]), cats[rel]["desc"]))
    out.append("")
    out.append("剔除的误报边（mention 提取的泛词噪音，已从图谱移除）：")
    for d in tax.get("dropped", []):
        out.append("- `%s → %s`：%s" % (d["from"], d["to"], d["why"]))
    out.append("")

    for rel in ORDER:
        edges = sorted(by_rel[rel], key=lambda e: (e["from"], e["to"]))
        out.append("## %s（%d 条）\n" % (cats[rel]["label"], len(edges)))
        out.append(cats[rel]["desc"] + "\n")
        out.append("| 源 | 目标 | 提取 | 关系 |")
        out.append("|---|---|---|---|")
        for e in edges:
            g = gloss.get(e["from"] + "||" + e["to"], {}).get("gloss", "")
            out.append("| `%s` | `%s` | %s | %s |" % (e["from"], e["to"], e["kind"], g))
        out.append("")
        out.append("**分析**：" + ANALYSIS[rel] + "\n")

    path = os.path.join(ROOT, "edge-taxonomy.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(path)


if __name__ == "__main__":
    main()

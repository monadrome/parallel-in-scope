#!/usr/bin/env python3
"""Join difficulty ranking, historical oracle status, and model outcomes."""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    ranking = read_jsonl(args.ranking)
    results = {}
    for path in sorted(args.results_dir.glob("*/result.json")):
        result = json.loads(path.read_text(encoding="utf-8"))
        results[int(result["rank"])] = result
    missing = [row["rank"] for row in ranking if row["rank"] not in results]
    if missing:
        raise SystemExit(f"missing model results for ranks: {missing}")

    joined = []
    for row in sorted(ranking, key=lambda item: item["rank"]):
        result = results[row["rank"]]
        status = result.get("model_evaluation_status", result.get("status", "unknown"))
        joined.append(
            {
                "rank": row["rank"],
                "group": row["group"],
                "difficulty": row["consensus"],
                "complexity_score": row["complexity_score"],
                "votes": row["votes"],
                "agreement": row["agreement"],
                "priority": row["priority"],
                "historical_sandbox_status": result.get("historical_sandbox_status", "not-run"),
                "eligible": result.get("historical_sandbox_status") == "verified",
                "model": result.get("model", "gpt-5.6-terra"),
                "reasoning_effort": result.get("reasoning_effort", "medium"),
                "model_status": status,
                "representative": row["representative"],
                "subject": row["subject"],
                "patch": result.get("patch"),
                "evaluation": result.get("evaluation"),
            }
        )

    status_counts = Counter(item["model_status"] for item in joined)
    historical_counts = Counter(item["historical_sandbox_status"] for item in joined)
    by_difficulty = defaultdict(Counter)
    for item in joined:
        by_difficulty[item["difficulty"]][item["model_status"]] += 1
    model_names = sorted({item["model"] for item in joined})
    effort_names = sorted({item["reasoning_effort"] for item in joined})
    summary = {
        "total": len(joined),
        "model": model_names[0] if len(model_names) == 1 else model_names,
        "agent": "codex",
        "reasoning_effort": effort_names[0] if len(effort_names) == 1 else effort_names,
        "status_counts": dict(sorted(status_counts.items())),
        "historical_sandbox_counts": dict(sorted(historical_counts.items())),
        "by_difficulty": {key: dict(sorted(value.items())) for key, value in sorted(by_difficulty.items())},
        "eligible": sum(1 for item in joined if item["eligible"]),
        "model_pass_rate_on_eligible": round(
            status_counts.get("model-pass", 0) / max(1, sum(1 for item in joined if item["eligible"])),
            4,
        ),
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    jsonl_path = args.output.with_suffix(".jsonl")
    json_path = args.output.with_suffix(".json")
    jsonl_path.write_text("\n".join(json.dumps(item, ensure_ascii=False, sort_keys=True) for item in joined) + "\n", encoding="utf-8")
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Ranked model evaluation",
        "",
        f"- Total groups: **{summary['total']}**",
        f"- Agent/model: `codex` + `{summary['model']}`",
        f"- Reasoning effort: `{summary['reasoning_effort']}`",
        f"- Eligible historical oracles: **{summary['eligible']}**",
        f"- Model pass rate on eligible groups: **{summary['model_pass_rate_on_eligible']:.2%}**",
        "",
        "## Status counts",
        "",
        "| Status | Count |",
        "|---|---:|",
    ]
    for status, count in sorted(status_counts.items()):
        lines.append(f"| `{status}` | {count} |")
    lines.extend(["", "## Difficulty breakdown", "", "| Difficulty | Total | Eligible | Pass | Fail | Not eligible |", "|---|---:|---:|---:|---:|---:|"])
    for difficulty in ("hard", "medium", "easy"):
        rows = [item for item in joined if item["difficulty"] == difficulty]
        counts = Counter(item["model_status"] for item in rows)
        eligible = sum(1 for item in rows if item["eligible"])
        lines.append(f"| {difficulty} | {len(rows)} | {eligible} | {counts.get('model-pass', 0)} | {counts.get('model-fail', 0)} | {counts.get('not-eligible', 0)} |")
    lines.extend(["", "## Ranked groups", "", "| Rank | Difficulty | Group | Historical | Model | Subject |", "|---:|---|---|---|---|---|"])
    for item in joined:
        subject = item["subject"].replace("|", "\\|")
        lines.append(f"| {item['rank']} | **{item['difficulty']}** | `{item['group']}` | `{item['historical_sandbox_status']}` | `{item['model_status']}` | {subject} |")
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()

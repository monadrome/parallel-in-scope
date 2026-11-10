#!/usr/bin/env python3
"""Generate sanitized JSON, Markdown, and 16:9 HTML model scorecards."""
from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import Counter
from pathlib import Path
from typing import Any


def load_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 3)


def registry_by_key(registry: Any) -> dict[str, dict[str, Any]]:
    items = registry.get("models", registry) if isinstance(registry, dict) else registry
    if not isinstance(items, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        for key in (item.get("id"), item.get("name"), item.get("model")):
            if key:
                result[str(key)] = item
    return result


def collect_model(run_dir: Path, registry: dict[str, dict[str, Any]], denominator: int) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for path in sorted(run_dir.rglob("result.json")):
        data = load_json(path, {})
        if isinstance(data, dict):
            rows.append(data)
    first = rows[0] if rows else {}
    model = str(first.get("model", run_dir.name.replace(".runs", "")))
    provider = str(first.get("provider", "unknown"))
    effort = str(first.get("effort", first.get("reasoning_effort", "unknown")))
    config = registry.get(model, {})
    if not config:
        config = registry.get(run_dir.name.replace(".runs", ""), {})
    tier_value = first.get("service_tier")
    if tier_value is None:
        tier_value = config.get("service_tier")
    service_tier = "n/a" if tier_value in (None, "") else str(tier_value)
    statuses = Counter(str(row.get("model_status", row.get("status", "unknown"))) for row in rows)
    public_statuses = Counter(str(row.get("public_status", "unknown")) for row in rows)
    durations = [float(row["seconds"]) for row in rows if isinstance(row.get("seconds"), (int, float))]
    passes = statuses.get("model-pass", 0)
    rescue_passes = sum(1 for row in rows if row.get("rescue_hidden_status") == "rescue-pass")
    rescue_fails = sum(1 for row in rows if row.get("rescue_hidden_status") == "rescue-fail")
    public_rescue_fails = sum(
        1 for row in rows
        if row.get("model_status") == "model-pass" and row.get("rescue_hidden_status") == "rescue-fail"
    )
    behavior_passes = passes - public_rescue_fails
    unresolved_timeouts = statuses.get("model-timeout", 0)
    retry_entries = [row.get("timeout_retries") for row in rows if isinstance(row.get("timeout_retries"), list)]
    retry_initial = len(retry_entries)
    retry_recovered = sum(
        1 for entries in retry_entries
        if entries and isinstance(entries[-1], dict) and entries[-1].get("model_status") != "model-timeout"
    )
    completed = len(rows)
    return {
        "model_id": config.get("id", run_dir.name.replace(".runs", "")),
        "provider": provider,
        "model": model,
        "effort": effort,
        "service_tier": service_tier,
        "completed": completed,
        "denominator": denominator,
        "state": "complete" if completed >= denominator else "incomplete",
        "pass_count": passes,
        "behavior_pass_count": behavior_passes,
        "rescue_hidden_passes": rescue_passes,
        "rescue_hidden_fails": rescue_fails,
        "pass_rate_on_completed": round(behavior_passes / completed, 4) if completed else None,
        "pass_rate_on_denominator": round(behavior_passes / denominator, 4) if denominator else None,
        "status_counts": dict(sorted(statuses.items())),
        "public_status_counts": dict(sorted(public_statuses.items())),
        "unresolved_timeout_count": unresolved_timeouts,
        "timeout_retry_initial": retry_initial,
        "timeout_retry_recovered": retry_recovered,
        "latency_seconds": {
            "mean": round(statistics.mean(durations), 3) if durations else None,
            "p50": percentile(durations, 0.50),
            "p90": percentile(durations, 0.90),
            "max": round(max(durations), 3) if durations else None,
        },
    }


def markdown(report: dict[str, Any]) -> str:
    policy = report.get("execution_policy", {})
    lines = [
        "# Historical model evaluation",
        "",
        f"- Benchmark: `{report['benchmark_version']}`",
        f"- State: **{report['state']}**",
        f"- Behavioural denominator: **{report['denominator']}**",
        f"- Model timeout: **{policy.get('model_timeout_seconds', '—')}s**; timeout retries: **{policy.get('timeout_retries', '—')}**",
        "",
        "| Provider | Model | Effort | Service tier | Completed | Behavior pass | Pass / completed | Pass / denominator | Rescue fail | Timeout | p50 (s) | p90 (s) | State |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|",
    ]
    for item in report["models"]:
        latency = item["latency_seconds"]
        rate = item["pass_rate_on_completed"]
        denominator_rate = item["pass_rate_on_denominator"]
        fmt = lambda x: "—" if x is None else f"{x:.2%}" if isinstance(x, float) and x <= 1 else str(x)
        lines.append(
            f"| `{item['provider']}` | `{item['model']}` | `{item['effort']}` | `{item['service_tier']}` | "
            f"{item['completed']}/{item['denominator']} | {item['behavior_pass_count']} | {fmt(rate)} | {fmt(denominator_rate)} | "
            f"{item['rescue_hidden_fails']} | {item['unresolved_timeout_count']} | {latency['p50'] if latency['p50'] is not None else '—'} | "
            f"{latency['p90'] if latency['p90'] is not None else '—'} | `{item['state']}` |"
        )
    lines += ["", "Timeouts remain separate from capability scoring until the configured retry policy resolves them.", ""]
    return "\n".join(lines)


def html(report: dict[str, Any]) -> str:
    cards = []
    for item in report["models"]:
        l = item["latency_seconds"]
        rate = item["pass_rate_on_completed"]
        rate_text = "—" if rate is None else f"{rate:.1%}"
        cards.append(
            f"<article class='card'><h2>{item['model']}</h2>"
            f"<p class='muted'>{item['provider']} · effort={item['effort']} · service_tier={item['service_tier']}</p>"
            f"<div class='stats'><div><b>{item['behavior_pass_count']}</b><span>behavior pass</span></div>"
            f"<div><b>{item['completed']}/{item['denominator']}</b><span>completed</span></div>"
            f"<div><b>{item['unresolved_timeout_count']}</b><span>timeout</span></div>"
            f"<div><b>{rate_text}</b><span>pass / completed</span></div></div>"
            f"<p class='latency'>p50 {l['p50'] if l['p50'] is not None else '—'}s · p90 {l['p90'] if l['p90'] is not None else '—'}s</p>"
            f"<span class='state'>{item['state']}</span></article>"
        )
    return f"""<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Historical model evaluation</title><style>
:root{{--bg:#0b1020;--panel:#141d35;--line:#2d3c63;--text:#edf3ff;--muted:#aab8d5;--cyan:#64d9ef;--green:#69e6ad;--amber:#ffd166}}*{{box-sizing:border-box}}html,body{{margin:0;width:100%;height:100%;overflow:hidden}}body{{background:radial-gradient(circle at 8% 0,#18365c 0,transparent 38%),var(--bg);font-family:Inter,system-ui,sans-serif;color:var(--text)}}.page{{width:100vw;height:100vh;min-height:665px;padding:4vh 4vw;display:flex;flex-direction:column;gap:2vh}}h1{{margin:0;font-size:clamp(26px,3.2vw,54px);letter-spacing:-.04em}}.sub,.muted{{color:var(--muted);line-height:1.45}}.sub{{font-size:clamp(12px,1vw,17px);margin:.8vh 0 0}}.badge{{align-self:flex-start;color:var(--cyan);border:1px solid #64d9ef66;border-radius:999px;padding:7px 12px;font-size:12px;font-weight:800}}.grid{{flex:1;min-height:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1vw}}.card{{position:relative;background:linear-gradient(145deg,#141d35f4,#0f172be6);border:1px solid var(--line);border-radius:18px;padding:1.2vw;min-height:0}}.card h2{{margin:0 0 .5vh;font-size:clamp(15px,1.3vw,23px)}}.card .muted{{font-size:clamp(10px,.75vw,14px);margin:0}}.stats{{display:grid;grid-template-columns:repeat(4,1fr);gap:.6vw;margin:2.2vh 0}}.stats div{{border:1px solid var(--line);border-radius:11px;padding:.9vh .6vw;background:#ffffff08}}.stats b{{display:block;font-size:clamp(20px,2vw,34px);color:var(--green)}}.stats span{{font-size:clamp(9px,.67vw,12px);color:var(--muted)}}.latency{{font-size:clamp(10px,.75vw,14px);color:var(--amber)}}.state{{position:absolute;right:1.1vw;bottom:1vw;color:var(--cyan);font-size:11px;font-weight:800;border:1px solid #64d9ef55;padding:4px 8px;border-radius:999px}}footer{{color:#7f90b2;font-size:12px;display:flex;justify-content:space-between}}</style></head><body><main class='page'><header><span class='badge'>benchmark={report['benchmark_version']} · state={report['state']}</span><h1>Historical repair scorecard</h1><p class='sub'>质量优先；timeout 单独记录；耗时作为次级指标。分数不包含 patch、日志或 hidden test 内容。</p></header><section class='grid'>{''.join(cards)}</section><footer><span>full provider / model / effort / service tier</span><span>denominator={report['denominator']}</span></footer></main></body></html>"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--registry", type=Path)
    parser.add_argument("--metrics", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True, help="base path; writes .json, .md, .html")
    args = parser.parse_args()
    manifest = load_json(args.manifest, {}) if args.manifest else {}
    registry = registry_by_key(load_json(args.registry, {})) if args.registry else {}
    population = manifest.get("population", {}) if isinstance(manifest, dict) else {}
    denominator = int(manifest.get("behavioral_denominator", manifest.get("eligible_behavior_cases", population.get("behavior_denominator", 90))))
    models = []
    for directory in sorted(args.run_root.glob("*.runs")):
        if directory.is_dir():
            models.append(collect_model(directory, registry, denominator))
    all_rows_complete = bool(models) and all(item["completed"] >= denominator for item in models)
    has_unresolved_timeout = any(item["unresolved_timeout_count"] > 0 for item in models)
    if not all_rows_complete:
        state = "provisional"
    elif has_unresolved_timeout:
        state = "complete-with-unresolved-timeouts"
    else:
        state = "complete"
    execution_policy = manifest.get("execution_policy", {}) if isinstance(manifest, dict) else {}
    report = {
        "schema": "historical-model-scorecard/v1",
        "benchmark_version": manifest.get("benchmark_version", "historical-v1"),
        "state": state,
        "denominator": denominator,
        "execution_policy": {
            "model_timeout_seconds": execution_policy.get("model_timeout_seconds"),
            "timeout_retries": execution_policy.get("timeout_retries"),
            "unresolved_timeout_is_model_failure": execution_policy.get("unresolved_timeout_is_model_failure"),
        },
        "models": models,
    }
    output = args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_suffix(".json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output.with_suffix(".md").write_text(markdown(report) + "\n", encoding="utf-8")
    output.with_suffix(".html").write_text(html(report), encoding="utf-8")
    print(json.dumps({"state": state, "models": len(models), "output": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

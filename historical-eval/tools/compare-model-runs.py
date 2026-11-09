#!/usr/bin/env python3
"""Compare historical model runs and private rescue attempts.

The original reports were produced with an 88-group eligible denominator.  This
tool keeps those reports immutable and applies evaluator-only rescue results to
produce a comparable denominator (currently 90 behavioural groups).

Private rescue directories are accepted deliberately: their paths and contents
never become part of the checked-in report.  The output contains only status
codes and counts, not test names, logs, or patches.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any


RANK_RE = re.compile(r"(?:^|[-_])(\d{1,3})(?:[-_.]|$)")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_assignment(value: str) -> tuple[str, Path]:
    name, sep, raw_path = value.partition("=")
    if not sep or not name or not raw_path:
        raise argparse.ArgumentTypeError(f"expected NAME=PATH, got {value!r}")
    return name, Path(raw_path).expanduser()


def report_summary(path: Path) -> dict[str, Any]:
    data = read_json(path)
    if not isinstance(data, dict):
        raise ValueError(f"model report is not an object: {path}")
    statuses = Counter(data.get("status_counts", {}))
    return {
        "source": str(path),
        "model": data.get("model"),
        "agent": data.get("agent"),
        "reasoning_effort": data.get("reasoning_effort"),
        "historical_eligible": int(data.get("eligible", 0)),
        "base_status_counts": dict(sorted(statuses.items())),
    }


def rank_from_path(path: Path) -> int | None:
    match = RANK_RE.search(path.stem)
    return int(match.group(1)) if match else None


def priority(path: Path) -> tuple[int, int, str]:
    """Prefer corrected evaluator files when old exploratory files coexist."""
    name = path.name.lower()
    version = 2 if "v2" in name else 1
    hidden = 1 if "hidden" in name or "rescue" in name else 0
    return version, hidden, name


def rescue_records(directory: Path) -> dict[int, dict[str, Any]]:
    if not directory.is_dir():
        raise ValueError(f"rescue directory does not exist: {directory}")
    records: dict[int, dict[str, Any]] = {}

    def record(rank: int) -> dict[str, Any]:
        return records.setdefault(rank, {"rank": rank, "source_files": []})

    for path in sorted(directory.glob("*.json"), key=priority):
        try:
            data = read_json(path)
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(data, dict):
            continue
        rank = data.get("rank")
        if not isinstance(rank, int):
            rank = rank_from_path(path)
        if rank is None:
            if isinstance(data.get("results"), list):
                for item in data["results"]:
                    if isinstance(item, dict) and isinstance(item.get("rank"), int):
                        _merge_rescue(record(item["rank"]), item, path)
            continue
        _merge_rescue(record(rank), data, path)
    return records


def _merge_rescue(target: dict[str, Any], data: dict[str, Any], path: Path) -> None:
    target["source_files"].append(str(path))
    if isinstance(data.get("model_returncode"), int):
        target["model_returncode"] = data["model_returncode"]
    if data.get("status") in {
        "patch-ready",
        "no-change",
        "model-timeout",
        "model-pass",
        "model-fail",
    }:
        target["model_status"] = data["status"]
    if data.get("historical_eval"):
        target["historical_status"] = data["historical_eval"]
    if data.get("stage") == "hidden-rescue" and isinstance(data.get("returncode"), int):
        target["hidden_returncode"] = data["returncode"]
    # Terra's compact summary stores the private evaluator result directly on
    # each item instead of emitting a separate hidden-eval JSON file.
    if isinstance(data.get("rescue_after_returncode"), int):
        target["hidden_returncode"] = data["rescue_after_returncode"]
    if isinstance(data.get("rescue_before_returncode"), int):
        target["rescue_before_returncode"] = data["rescue_before_returncode"]
    after = data.get("after")
    if isinstance(after, dict):
        hidden = after.get("hidden")
        if isinstance(hidden, dict) and isinstance(hidden.get("returncode"), int):
            target["hidden_returncode"] = hidden["returncode"]
        historical = after.get("historical")
        if isinstance(historical, dict) and isinstance(historical.get("returncode"), int):
            target["historical_returncode"] = historical["returncode"]
    if data.get("patch"):
        target["patch"] = str(data["patch"])


def normalise_rescue(record: dict[str, Any]) -> dict[str, Any]:
    hidden = record.get("hidden_returncode")
    model_rc = record.get("model_returncode")
    hidden_pass = hidden == 0
    model_pass = hidden_pass and model_rc == 0
    if model_pass:
        classification = "model-pass"
    elif hidden_pass and model_rc == 124:
        classification = "timeout-with-valid-patch"
    elif model_rc == 124:
        classification = "model-timeout"
    elif hidden is not None:
        classification = "model-fail"
    else:
        classification = "incomplete"
    return {
        "rank": record["rank"],
        "classification": classification,
        "model_returncode": model_rc,
        "hidden_pass": hidden_pass,
        "model_pass": model_pass,
        "historical_status": record.get("historical_status"),
        "source_files": sorted(set(record.get("source_files", []))),
    }


def compare(name: str, report: Path, rescue_dir: Path | None, denominator: int) -> dict[str, Any]:
    summary = report_summary(report)
    base = Counter(summary["base_status_counts"])
    base_eligible = summary["historical_eligible"]
    rescued = {rank: normalise_rescue(value) for rank, value in rescue_records(rescue_dir).items()} if rescue_dir else {}
    # Only rescue ranks outside the old eligible set.  The current catalog uses
    # ranks 19 and 34, both baseline-pass, so this is a direct +2 adjustment.
    additional = Counter()
    for value in rescued.values():
        if value["classification"] == "model-pass":
            additional["model-pass"] += 1
        elif value["classification"] in {"model-timeout", "timeout-with-valid-patch"}:
            additional["model-timeout"] += 1
        elif value["classification"] == "model-fail":
            additional["model-fail"] += 1
    adjusted = base + additional
    pass_count = adjusted["model-pass"]
    rescue_hidden_pass = sum(1 for value in rescued.values() if value["hidden_pass"])
    rescue_model_pass = sum(1 for value in rescued.values() if value["model_pass"])
    return {
        "name": name,
        "model": summary["model"],
        "agent": summary["agent"],
        "reasoning_effort": summary["reasoning_effort"],
        "source_report": summary["source"],
        "rescue_directory": str(rescue_dir) if rescue_dir else None,
        "historical_eligible_before_rescue": base_eligible,
        "behavioural_denominator": denominator,
        "base_status_counts": dict(sorted(base.items())),
        "rescue_attempts": {str(rank): rescued[rank] for rank in sorted(rescued)},
        "adjusted_status_counts": dict(sorted(adjusted.items())),
        "pass_at_1": {
            "passes": pass_count,
            "denominator": denominator,
            "rate": round(pass_count / denominator, 4) if denominator else 0.0,
        },
        "rescue_metrics": {
            "hidden_patch_passes": rescue_hidden_pass,
            "model_passes": rescue_model_pass,
            "timeout_with_valid_patch": sum(
                1 for value in rescued.values() if value["classification"] == "timeout-with-valid-patch"
            ),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--report",
        action="append",
        type=parse_assignment,
        required=True,
        metavar="NAME=REPORT.json",
    )
    parser.add_argument(
        "--rescue-dir",
        action="append",
        type=parse_assignment,
        default=[],
        metavar="NAME=PRIVATE_DIR",
    )
    parser.add_argument("--denominator", type=int, default=90)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()
    rescue_by_name = dict(args.rescue_dir)
    models = [
        compare(name, report, rescue_by_name.get(name), args.denominator)
        for name, report in args.report
    ]
    result = {
        "schema": "historical-model-comparison/v1",
        "behavioural_denominator": args.denominator,
        "models": models,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()

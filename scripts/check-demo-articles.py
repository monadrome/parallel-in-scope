#!/usr/bin/env python3
"""Check known drift hazards in the runnable demo articles."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
ARTICLES = ROOT / "demo" / "docs" / "zh-CN" / "articles"
DELETED_CLAIMS = (
    "TaskGraph` 会自动记录",
    "通过 Guava `Graphs.hasCycle()`",
    "内置了 `canDeadlock()` 判断逻辑",
    "使用专用的 `SubmitterPool` 来运行",
    "独立的 `submitterPool` 运行提交循环",
)


def main() -> int:
    failures = []
    for path in sorted(ARTICLES.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        builder = re.search(r"ParRuntime\.builder\(\).*?\.build\(\);", text, re.S)
        if builder and ".defaultPar(" not in builder.group(0) and "config.defaultPar()" in text:
            failures.append(f"{path}: builder used with config.defaultPar() but no defaultPar registration")
        for claim in DELETED_CLAIMS:
            if claim in text:
                failures.append(f"{path}: deleted mechanism claim {claim}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"checked {len(list(ARTICLES.glob('*.md')))} demo articles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

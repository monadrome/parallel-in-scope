#!/usr/bin/env python3
"""Collect conservative, diff-scoped Java complexity evidence.

This module is intentionally an evaluation aid, not a quality gate.  It uses a
small source scanner so the gray-stage experiment has no new dependency.  The
output names its scope and stage explicitly; a future AST collector may replace
the scanner without changing the refactoring-review contract.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path


CONTROL_NAMES = {"if", "for", "while", "switch", "catch", "synchronized"}
METHOD_RE = re.compile(
    r"(?m)^[ \t]*(?:(?:public|protected|private|static|final|native|synchronized|abstract|default|strictfp)\s+)*"
    r"(?:[A-Za-z_$][\w$<>?,.\[\] ]*\s+)?([A-Za-z_$][\w$]*)\s*\(([^()]*)\)\s*(?:throws\s+[^\{]+)?\{" 
)
CLASS_RE = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")


@dataclass(frozen=True)
class MethodMetric:
    path: str
    symbol: str
    start_line: int
    end_line: int
    cyclomatic: int
    parts: dict[str, int]
    code_lines: int
    max_nesting: int
    nesting_path: str

    def as_dict(self) -> dict:
        return {
            "path": self.path,
            "symbol": self.symbol,
            "startLine": self.start_line,
            "endLine": self.end_line,
            "cyclomatic": self.cyclomatic,
            "cyclomaticParts": self.parts,
            "codeLines": self.code_lines,
            "maxNesting": self.max_nesting,
            "nestingPath": self.nesting_path,
        }


def _mask_comments_and_strings(source: str) -> str:
    """Keep line structure while masking comments and Java string/char literals."""
    out = list(source)
    i = 0
    state = "code"
    while i < len(source):
        if state == "code" and source.startswith("//", i):
            state = "line"
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if state == "code" and source.startswith("/*", i):
            state = "block"
            out[i] = out[i + 1] = " "
            i += 2
            continue
        if state == "code" and source[i] in ('"', "'"):
            state = "string" if source[i] == '"' else "char"
            out[i] = " "
            i += 1
            continue
        if state == "code":
            i += 1
            continue
        if state == "line":
            if source[i] == "\n":
                state = "code"
            else:
                out[i] = " "
            i += 1
            continue
        if state == "block":
            if source.startswith("*/", i):
                out[i] = out[i + 1] = " "
                state = "code"
                i += 2
            else:
                if source[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        if state in ("string", "char"):
            if source[i] == "\\" and i + 1 < len(source):
                out[i] = out[i + 1] = " "
                i += 2
            elif (state == "string" and source[i] == '"') or (state == "char" and source[i] == "'"):
                out[i] = " "
                state = "code"
                i += 1
            else:
                if source[i] != "\n":
                    out[i] = " "
                i += 1
    if state != "code":
        raise ValueError(f"unterminated Java {state} literal or comment")
    return "".join(out)


def _line_of(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _matching_brace(masked: str, opening: int) -> int | None:
    if opening < 0:
        return None
    depth = 0
    for index in range(opening, len(masked)):
        char = masked[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    return None


def _class_name(masked: str, offset: int) -> str:
    names = list(CLASS_RE.finditer(masked, 0, offset))
    return names[-1].group(1) if names else "<top-level>"


def _code_lines(source: str, start: int, end: int) -> int:
    lines = source[start:end].splitlines()
    return sum(1 for line in lines if line.strip() and not line.lstrip().startswith(("//", "/*", "*", "*/")))


def _body_metrics(body: str) -> tuple[int, dict[str, int], int, str]:
    parts = {
        "if": len(re.findall(r"\bif\s*\(", body)),
        "loop": len(re.findall(r"\b(?:for|while|do)\b", body)),
        "case": len(re.findall(r"\bcase\b", body)),
        "catch": len(re.findall(r"\bcatch\s*\(", body)),
        "ternary": body.count("?") - body.count("??"),
        "boolean": len(re.findall(r"&&|\|\|", body)),
    }
    parts["ternary"] = max(0, parts["ternary"])
    cyclomatic = 1 + sum(parts.values())

    token_re = re.compile(r"\b(if|for|while|do|switch|catch|synchronized)\b|[{}]")
    depth = 0
    pending: list[str] = []
    path: list[str] = []
    max_depth = 0
    max_path: list[str] = []
    for match in token_re.finditer(body):
        token = match.group(0)
        if token in CONTROL_NAMES:
            pending.append(token)
        elif token == "{":
            if pending:
                path.extend(pending)
                pending.clear()
                depth += len(path) - depth
                if depth > max_depth:
                    max_depth = depth
                    max_path = list(path)
        elif token == "}" and path:
            path.pop()
            depth = len(path)
    return cyclomatic, parts, max_depth, ">".join(max_path)


def collect_file(path: str, source: str) -> list[MethodMetric]:
    masked = _mask_comments_and_strings(source)
    metrics: list[MethodMetric] = []
    for match in METHOD_RE.finditer(masked):
        name = match.group(1)
        if name in CONTROL_NAMES:
            continue
        opening = masked.find("{", match.start(), match.end())
        closing = _matching_brace(masked, opening)
        if closing is None:
            continue
        params = re.sub(r"\s+", " ", match.group(2).strip())
        signature = f"{_class_name(masked, match.start())}#{name}({params})"
        body = masked[opening + 1 : closing]
        cyclomatic, parts, nesting, nesting_path = _body_metrics(body)
        metrics.append(
            MethodMetric(
                path=path,
                symbol=signature,
                start_line=_line_of(source, match.start()),
                end_line=_line_of(source, closing),
                cyclomatic=cyclomatic,
                parts=parts,
                code_lines=_code_lines(source, match.start(), closing + 1),
                max_nesting=nesting,
                nesting_path=nesting_path,
            )
        )
    return metrics


def changed_line_numbers(diff: str, path: str) -> set[int]:
    """Return added line numbers for one path from a zero-context unified diff."""
    lines: set[int] = set()
    active = False
    current = 0
    for line in diff.splitlines():
        if line.startswith("diff --git "):
            active = line.endswith(f"b/{path}")
        elif active and line.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,(\d+))?", line)
            if match:
                current = int(match.group(1))
        elif active and line.startswith("+") and not line.startswith("+++"):
            lines.add(current)
            current += 1
        elif active and not line.startswith("-"):
            current += 1
    return lines


def _trigger(metric: MethodMetric, base: MethodMetric | None, thresholds: dict) -> list[str]:
    triggers: list[str] = []
    if metric.cyclomatic >= thresholds["cyclomatic"]:
        triggers.append("head.cyclomatic>=threshold")
    if metric.code_lines >= thresholds["codeLines"]:
        triggers.append("head.codeLines>=threshold")
    if metric.max_nesting >= thresholds["maxNesting"]:
        triggers.append("head.maxNesting>=threshold")
    if base is not None:
        if metric.cyclomatic - base.cyclomatic >= thresholds["deltaCyclomatic"]:
            triggers.append("delta.cyclomatic>=threshold")
        if metric.code_lines - base.code_lines >= thresholds["deltaCodeLines"]:
            triggers.append("delta.codeLines>=threshold")
        if metric.max_nesting - base.max_nesting >= thresholds["deltaMaxNesting"]:
            triggers.append("delta.maxNesting>=threshold")
    return triggers


def collect(repo: Path, base_files: dict[str, str], head_files: dict[str, str], diff: str, config: dict) -> dict:
    del repo
    complexity = config.get("complexity")
    if not isinstance(complexity, dict):
        raise ValueError("complexity configuration is missing")
    thresholds = complexity.get("thresholds")
    required_thresholds = {
        "cyclomatic",
        "codeLines",
        "maxNesting",
        "deltaCyclomatic",
        "deltaCodeLines",
        "deltaMaxNesting",
    }
    if not isinstance(thresholds, dict) or not required_thresholds.issubset(thresholds):
        raise ValueError("complexity thresholds are incomplete")
    if any(not isinstance(thresholds[name], int) or thresholds[name] < 0 for name in required_thresholds):
        raise ValueError("complexity thresholds must be non-negative integers")
    excluded = config["complexity"].get("excludeGlobs", [])
    base_metrics: dict[str, MethodMetric] = {}
    head_metrics: dict[str, MethodMetric] = {}
    for path, source in base_files.items():
        base_metrics.update({f"{m.path}:{m.symbol}": m for m in collect_file(path, source)})
    for path, source in head_files.items():
        if any(Path(path).match(glob) for glob in excluded):
            continue
        head_metrics.update({f"{m.path}:{m.symbol}": m for m in collect_file(path, source)})

    candidates = []
    touched_paths = {path for path in head_files}
    for key, metric in sorted(head_metrics.items(), key=lambda item: (item[1].path, item[1].start_line, item[1].symbol)):
        if metric.path not in touched_paths:
            continue
        changed = changed_line_numbers(diff, metric.path)
        if not changed or not any(metric.start_line <= line <= metric.end_line for line in changed):
            continue
        base = base_metrics.get(key)
        triggers = _trigger(metric, base, thresholds)
        if not triggers:
            continue
        entry = metric.as_dict()
        entry["status"] = "modified" if base else "added"
        entry["base"] = base.as_dict() if base else None
        entry["delta"] = {
            "cyclomatic": metric.cyclomatic - base.cyclomatic if base else None,
            "codeLines": metric.code_lines - base.code_lines if base else None,
            "maxNesting": metric.max_nesting - base.max_nesting if base else None,
        }
        entry["triggers"] = triggers
        candidates.append(entry)

    limit = config["complexity"].get("maxCandidatesPerAgent", 5)
    if not isinstance(limit, int) or limit < 1:
        raise ValueError("maxCandidatesPerAgent must be a positive integer")
    return {
        "version": "complexity-metrics/v1",
        "stage": "gray-evaluation",
        "purpose": "attention-routing evidence; not a quality score or CI gate",
        "base": config.get("_base"),
        "head": config.get("_head"),
        "scope": "core/src/main/java/io/github/monadrome/parallelinscope",
        "thresholds": thresholds,
        "summary": {"methods": len(head_metrics), "touchedCandidates": len(candidates), "injectedCandidates": min(len(candidates), limit)},
        "candidates": candidates,
        "omittedCandidates": max(0, len(candidates) - limit),
    }

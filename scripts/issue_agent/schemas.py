from __future__ import annotations

from typing import Optional

from . import policy


def _string(enum=None) -> dict:
    return {"type": "string", "enum": list(enum)} if enum else {"type": "string"}


def _object(properties: dict) -> dict:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": list(properties),
        "properties": properties,
    }


TRIAGE = _object({
    "type": _string(policy.TYPES),
    "priority": _string(policy.PRIORITIES),
    "route": _string(policy.ROUTES),
    "reason": _string(policy.REASONS),
    "size": _string(policy.SIZES),
    "summary": _string(),
    "plan": _string(),
    "areas": _string(),
    "duplicate_of": {"type": "integer"},
})

DISPOSITION = _object({
    "finding": _string(),
    "disposition": _string(("retained", "downgraded", "rejected")),
    "action": _string(),
})

REPORT = _object({
    "status": _string(("done", "needs-decision", "blocked")),
    "completes_issue": {"type": "boolean"},
    "breaking": {"type": "boolean"},
    "title": _string(),
    "summary": _string(),
    "contract_items": {"type": "array", "items": _string()},
    "verification": {"type": "array", "items": _string()},
    "pit": _string(),
    "dispositions": {"type": "array", "items": DISPOSITION},
    "open_question": _string(),
})

FINDING = _object({
    "severity": _string(("blocker", "major", "minor")),
    "file": _string(),
    "summary": _string(),
    "evidence": _string(),
    "suggestion": _string(),
})

REVIEW = _object({
    "verdict": _string(("clean", "changes-requested")),
    "findings": {"type": "array", "items": FINDING},
    "notes": _string(),
})

SEVERITIES = ("blocker", "major", "minor")
FINDING_FIELDS = ("file", "summary", "evidence", "suggestion")


def normalize_review(raw) -> Optional[dict]:
    if not isinstance(raw, dict) or raw.get("verdict") not in ("clean", "changes-requested"):
        return None
    if not isinstance(raw.get("findings"), list):
        return None
    findings = []
    for item in raw["findings"]:
        item = item if isinstance(item, dict) else {"summary": str(item)}
        severity = item.get("severity") if item.get("severity") in SEVERITIES else "major"
        findings.append({"severity": severity, **{k: str(item.get(k) or "") for k in FINDING_FIELDS}})
    return {"verdict": raw["verdict"], "findings": findings, "notes": str(raw.get("notes") or "")}


def blocking_findings(review: dict) -> list:
    return [f for f in review.get("findings") or [] if f.get("severity") != "minor"]

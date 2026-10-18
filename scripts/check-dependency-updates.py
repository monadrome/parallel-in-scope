#!/usr/bin/env python3
"""Audit Maven, GitHub Actions, PyPI, and toolchain components for newer releases.

The script is deterministic: it queries Maven Central, PyPI, and the GitHub API,
filters prereleases, and reports current versus latest. It never edits a file.

Exit codes:
    0  audit completed; updates may or may not exist
    1  fatal error (unreadable POM or workflow)
    2  --fail-on-unknown and at least one network query failed
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
GITHUB_API = "https://api.github.com"
PYPI_API = "https://pypi.org/pypi"
PROJECT_COORD = ("io.github.monadrome", "parallel-in-scope")
POM_FILES = (
    ("root", "pom.xml"),
    ("demo", "demo/pom.xml"),
    ("verification", "verification/maven-central-consumer/pom.xml"),
)
LATEST_LTS = 25
PRERELEASE_RE = re.compile(
    r"(?i)(?:^|[-.])(snapshot|alpha|beta|rc|m|ea|preview|dev)\d*(?:[-.]|$)"
)
USES_RE = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
REQUIREMENT_RE = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*==\s*([^\s;#]+)")
JAVA_VERSION_RE = re.compile(r"java-version:\s*(?:\[\s*)?['\"]?(\d+)")
MAVEN_VERSION_RE = re.compile(r"maven-version:\s*['\"]?([^'\"\s]+)")
PROPERTY_RE = re.compile(r"\$\{([^}]+)\}")


@dataclass
class Item:
    kind: str
    module: str
    component: str
    current: str
    latest: str
    delta: str
    status: str
    reason: str = ""
    source: str = ""


class FatalAuditError(RuntimeError):
    pass


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def fetch(url: str, timeout: float, headers: dict[str, str] | None = None) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "parallel-in-scope-dependency-audit",
            **(headers or {}),
        },
    )
    last_error: Exception | None = None
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except Exception as exc:  # noqa: BLE001 - reported as an unknown check
            last_error = exc
            if attempt == 0:
                time.sleep(0.5)
    raise RuntimeError(f"{url}: {last_error}") from last_error


def is_stable(version: str) -> bool:
    return bool(version) and not PRERELEASE_RE.search(version)


def version_key(version: str) -> tuple[int, int, int]:
    match = re.match(r"^[^\d]*(\d+(?:\.\d+)*)", version)
    if not match:
        return (0, 0, 0)
    parts = [int(part) for part in match.group(1).split(".")[:3]]
    return tuple(parts + [0] * (3 - len(parts)))


def version_suffix(version: str) -> str:
    match = re.search(r"-([A-Za-z][A-Za-z0-9]*)$", version)
    return match.group(1).lower() if match else ""


def classify_delta(current: str, latest: str) -> str:
    if not current or not latest:
        return "unknown"
    if current == latest:
        return "none"
    current_key = version_key(current)
    latest_key = version_key(latest)
    if latest_key > current_key:
        if latest_key[0] != current_key[0]:
            return "major"
        if latest_key[1] != current_key[1]:
            return "minor"
        if latest_key[2] != current_key[2]:
            return "patch"
        return "qualifier"
    return "unknown"


def resolve(value: str | None, properties: dict[str, str], depth: int = 0) -> str | None:
    if not value or depth > 10:
        return value

    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key in properties:
            return resolve(properties[key], properties, depth + 1) or match.group(0)
        return match.group(0)

    return PROPERTY_RE.sub(replace, value)


def parse_pom(path: Path, inherited: dict[str, str] | None = None) -> tuple[list[dict[str, str | None]], dict[str, str]]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise FatalAuditError(f"cannot parse {path}: {exc}") from exc

    properties = dict(inherited or {})
    for element in root.iter():
        if local_name(element.tag) != "properties":
            continue
        for property_element in element:
            properties[local_name(property_element.tag)] = (property_element.text or "").strip()

    for _ in range(5):
        for key in list(properties):
            properties[key] = resolve(properties[key], properties) or ""

    entries: list[dict[str, str | None]] = []
    for element in root.iter():
        kind = local_name(element.tag)
        if kind not in ("dependency", "plugin"):
            continue
        group = artifact = version = None
        for child in element:
            child_name = local_name(child.tag)
            text = (child.text or "").strip()
            if child_name == "groupId":
                group = text
            elif child_name == "artifactId":
                artifact = text
            elif child_name == "version":
                version = text
        if not artifact:
            continue
        if kind == "plugin" and not group:
            group = "org.apache.maven.plugins"
        if not group:
            continue
        entries.append(
            {
                "element": kind,
                "group": group,
                "artifact": artifact,
                "version": resolve(version, properties) if version else None,
            }
        )
    return entries, properties


def latest_stable(metadata: bytes, current: str) -> str | None:
    root = ET.fromstring(metadata)
    versions = [
        element.text.strip()
        for element in root.iter()
        if local_name(element.tag) == "version" and element.text and element.text.strip()
    ]
    stable = [version for version in versions if is_stable(version)]
    if not stable:
        return None
    suffix = version_suffix(current)
    if suffix:
        same_suffix = [version for version in stable if version_suffix(version) == suffix]
        if same_suffix:
            stable = same_suffix
    return max(stable, key=version_key)


def maven_metadata_url(group: str, artifact: str) -> str:
    return f"{MAVEN_CENTRAL}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"


def status_for_delta(delta: str) -> str:
    if delta == "none":
        return "current"
    if delta in ("major", "qualifier"):
        return "needs_review"
    if delta in ("minor", "patch"):
        return "outdated"
    return "needs_review"


def check_maven(
    kind: str,
    module: str,
    group: str,
    artifact: str,
    current: str,
    timeout: float,
    offline: bool,
) -> Item:
    component = f"{group}:{artifact}"
    source = maven_metadata_url(group, artifact)
    if offline:
        return Item(kind, module, component, current, "", "unknown", "unknown", "offline mode", source)
    try:
        metadata = fetch(source, timeout)
        latest = latest_stable(metadata, current)
    except Exception as exc:  # noqa: BLE001 - reported as an unknown check
        return Item(kind, module, component, current, "", "unknown", "unknown", f"metadata fetch failed: {exc}", source)
    if not latest:
        return Item(kind, module, component, current, "", "unknown", "unknown", "no stable release found", source)
    delta = classify_delta(current, latest)
    return Item(kind, module, component, current, latest, delta, status_for_delta(delta), source=source)


def collect_maven(root: Path, timeout: float, offline: bool) -> list[Item]:
    root_pom = root / "pom.xml"
    _, root_properties = parse_pom(root_pom)
    items: list[Item] = []
    seen: set[tuple[str, str, str, str]] = set()
    for module, relative in POM_FILES:
        path = root / relative
        if not path.exists():
            raise FatalAuditError(f"missing POM: {path}")
        entries, _ = parse_pom(path, inherited=root_properties)
        # One entry per (kind, group, artifact) per module; a versioned declaration
        # wins over an unversioned pluginManagement placeholder.
        by_coordinate: dict[tuple[str, str, str], dict[str, str | None]] = {}
        for entry in entries:
            key = (str(entry["element"]), str(entry["group"]), str(entry["artifact"]))
            existing = by_coordinate.get(key)
            if existing is None or (not existing["version"] and entry["version"]):
                by_coordinate[key] = entry
        for entry in by_coordinate.values():
            group = str(entry["group"])
            artifact = str(entry["artifact"])
            version = entry["version"]
            if (group, artifact) == PROJECT_COORD:
                continue
            key = (module, str(entry["element"]), group, artifact)
            if key in seen:
                continue
            seen.add(key)
            kind = "maven-plugin" if entry["element"] == "plugin" else "maven-dependency"
            component = f"{group}:{artifact}"
            if not version:
                items.append(
                    Item(kind, module, component, "", "", "unknown", "managed", "version is managed by the parent or a BOM")
                )
                continue
            if "${" in version:
                items.append(
                    Item(kind, module, component, version, "", "unknown", "managed", "unresolved property")
                )
                continue
            if "SNAPSHOT" in version.upper():
                items.append(
                    Item(kind, module, component, version, "", "none", "managed", "snapshot version")
                )
                continue
            items.append(check_maven(kind, module, group, artifact, version, timeout, offline))
    return items


def parse_uses(text: str) -> list[tuple[str, str]]:
    """Return (owner/repo, ref) pairs from workflow `uses:` lines."""
    found: list[tuple[str, str]] = []
    for line in text.splitlines():
        match = USES_RE.match(line)
        if not match:
            continue
        reference = match.group(1).strip("'\"")
        if reference.startswith("./") or reference.startswith("docker://") or "@" not in reference:
            continue
        action, tag = reference.rsplit("@", 1)
        parts = action.split("/")
        if len(parts) < 2:
            continue
        found.append(("/".join(parts[:2]), tag))
    return found


def collect_actions(root: Path, timeout: float, offline: bool, token: str | None) -> list[Item]:
    workflow_dir = root / ".github" / "workflows"
    uses: dict[tuple[str, str], set[str]] = {}
    for path in sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise FatalAuditError(f"cannot read {path}: {exc}") from exc
        for repo, tag in parse_uses(text):
            uses.setdefault((repo, tag), set()).add(path.name)

    items: list[Item] = []
    for (repo, current), files in sorted(uses.items()):
        module = ", ".join(sorted(files))
        source = f"https://github.com/{repo}/releases"
        if offline:
            items.append(Item("github-action", module, repo, current, "", "unknown", "unknown", "offline mode", source))
            continue
        try:
            latest = latest_action(repo, timeout, token)
        except Exception as exc:  # noqa: BLE001 - reported as an unknown check
            items.append(
                Item("github-action", module, repo, current, "", "unknown", "unknown", f"release lookup failed: {exc}", source)
            )
            continue
        if not latest:
            items.append(
                Item("github-action", module, repo, current, "", "unknown", "unknown", "no release or tag found", source)
            )
            continue
        delta, status = action_delta(current, latest)
        items.append(Item("github-action", module, repo, current, latest, delta, status, source=source))
    return items


def latest_action(repo: str, timeout: float, token: str | None) -> str | None:
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        data = json.loads(fetch(f"{GITHUB_API}/repos/{repo}/releases/latest", timeout, headers))
        tag = data.get("tag_name") or data.get("name")
        # Some repositories publish non-action releases (codeql-action publishes
        # codeql-bundle-vX.Y.Z). Only a leading version tag identifies the action.
        if tag and re.match(r"^v?\d", str(tag)):
            return str(tag)
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
    except Exception:  # noqa: BLE001 - fall back to tags
        pass
    data = json.loads(fetch(f"{GITHUB_API}/repos/{repo}/tags?per_page=100", timeout, headers))
    tags = [str(tag["name"]) for tag in data if tag.get("name")]
    stable = [tag for tag in tags if is_stable(tag)]
    if stable:
        return max(stable, key=version_key)
    return tags[0] if tags else None


def action_delta(current: str, latest: str) -> tuple[str, str]:
    current_major = re.fullmatch(r"v(\d+)", current)
    latest_major = re.fullmatch(r"v(\d+)", latest)
    if current_major:
        if latest_major:
            if current_major.group(1) == latest_major.group(1):
                return "none", "current"
            return "major", "needs_review"
        if version_key(latest)[0] == int(current_major.group(1)):
            return "none", "current"
    delta = classify_delta(current, latest)
    return delta, status_for_delta(delta)


def parse_requirements(text: str) -> list[tuple[str, str]]:
    """Return (name, version) pairs from a pinned requirements file."""
    found: list[tuple[str, str]] = []
    for line in text.splitlines():
        match = REQUIREMENT_RE.match(line)
        if match:
            found.append((match.group(1), match.group(2)))
    return found


def collect_pypi(root: Path, timeout: float, offline: bool) -> list[Item]:
    requirements = root / "mkdocs" / "requirements.txt"
    if not requirements.exists():
        return []
    items: list[Item] = []
    for name, current in parse_requirements(requirements.read_text(encoding="utf-8")):
        source = f"{PYPI_API}/{name}/json"
        if offline:
            items.append(Item("pypi", "mkdocs", name, current, "", "unknown", "unknown", "offline mode", source))
            continue
        try:
            payload = json.loads(fetch(source, timeout))
            latest = str(payload["info"]["version"])
        except Exception as exc:  # noqa: BLE001 - reported as an unknown check
            items.append(
                Item("pypi", "mkdocs", name, current, "", "unknown", "unknown", f"PyPI lookup failed: {exc}", source)
            )
            continue
        delta = classify_delta(current, latest)
        items.append(Item("pypi", "mkdocs", name, current, latest, delta, status_for_delta(delta), source=source))
    return items


def collect_toolchain(root: Path) -> list[Item]:
    workflow_dir = root / ".github" / "workflows"
    texts = []
    for path in sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml")):
        try:
            texts.append(path.read_text(encoding="utf-8"))
        except OSError as exc:
            raise FatalAuditError(f"cannot read {path}: {exc}") from exc
    combined = "\n".join(texts)

    items: list[Item] = []
    java_versions = sorted({int(match) for match in JAVA_VERSION_RE.findall(combined)})
    for version in java_versions:
        if version == 8:
            items.append(
                Item(
                    "toolchain",
                    "ci",
                    "runtime JDK",
                    "8",
                    "",
                    "none",
                    "current",
                    "runtime target; do not upgrade",
                )
            )
        elif version == LATEST_LTS:
            items.append(
                Item("toolchain", "ci", "build JDK", str(version), str(LATEST_LTS), "none", "current", "current LTS")
            )
        else:
            items.append(
                Item(
                    "toolchain",
                    "ci",
                    "build JDK",
                    str(version),
                    str(LATEST_LTS),
                    "unknown",
                    "needs_review",
                    f"latest LTS is {LATEST_LTS}",
                )
            )

    wrapper = root / ".mvn" / "wrapper" / "maven-wrapper.properties"
    if wrapper.exists():
        match = re.search(r"apache-maven-(\d+(?:\.\d+)+)", wrapper.read_text(encoding="utf-8"))
        items.append(
            Item(
                "toolchain",
                "repo",
                "Maven wrapper",
                match.group(1) if match else "unknown",
                "",
                "unknown",
                "needs_review" if match else "unknown",
                "checked manually; wrapper upgrades are not automated by this script",
            )
        )
    else:
        items.append(
            Item(
                "toolchain",
                "repo",
                "Maven wrapper",
                "not used",
                "",
                "none",
                "not_used",
                "CI uses the runner Maven; consider a wrapper or an explicit maven-version",
            )
        )

    pinned_maven = sorted(set(MAVEN_VERSION_RE.findall(combined)))
    if pinned_maven:
        for version in pinned_maven:
            items.append(Item("toolchain", "ci", "Maven", version, "", "unknown", "needs_review", "verify manually"))
    else:
        items.append(
            Item(
                "toolchain",
                "ci",
                "Maven",
                "unpinned",
                "",
                "unknown",
                "unpinned",
                "CI uses the image default; pin maven-version or add a wrapper",
            )
        )
    return items


def summarize(items: list[Item]) -> dict[str, int]:
    return dict(sorted(Counter(item.status for item in items).items()))


def generated_at() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def git_value(root: Path, *args: str) -> str:
    try:
        return subprocess.run(
            ["git", *args], cwd=root, check=True, capture_output=True, text=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def render_markdown(items: list[Item], meta: dict[str, str]) -> str:
    sections = (
        ("maven-dependency", "Maven dependencies"),
        ("maven-plugin", "Maven plugins"),
        ("github-action", "GitHub Actions"),
        ("pypi", "Python docs"),
        ("toolchain", "Toolchain"),
    )
    lines = [
        f"# Dependency audit — {meta['date']}",
        "",
        f"- Audited ref: `{meta['ref']}` @ `{meta['commit'] or 'unknown'}`",
        f"- Generated: `{meta['generated_at']}`",
        "- Policy: stable releases only. Major upgrades need a manual Java 8 review.",
        "- Triage: run the `dependency-audit` skill or review this issue; do not upgrade a major without a Java 8 check.",
        "",
        "## Summary",
        "",
        "| Status | Count |",
        "|---|---:|",
    ]
    for status, count in summarize(items).items():
        lines.append(f"| {status.replace('-', ' ')} | {count} |")

    for kind, title in sections:
        grouped = [item for item in items if item.kind == kind]
        lines.extend(["", f"## {title}", ""])
        if not grouped:
            lines.append("None.")
            continue
        lines.append("| Module | Component | Current | Latest | Delta | Status | Note |")
        lines.append("|---|---|---|---|---|---|---|")
        for item in sorted(grouped, key=lambda entry: (entry.module, entry.component, entry.current)):
            lines.append(
                "| "
                + " | ".join(
                    value.replace("|", "\\|")
                    for value in (
                        item.module,
                        item.component,
                        item.current or "—",
                        item.latest or "—",
                        item.delta,
                        item.status.replace("-", " "),
                        item.reason,
                    )
                )
                + " |"
            )

    unknown = [item for item in items if item.status == "unknown"]
    lines.extend(["", "## Unknown / failed checks", ""])
    if not unknown:
        lines.append("None.")
    else:
        for item in sorted(unknown, key=lambda entry: (entry.kind, entry.component)):
            lines.append(f"- `{item.component}` ({item.module}): {item.reason}")
    lines.append("")
    return "\n".join(lines)


def render_json(items: list[Item], meta: dict[str, str]) -> str:
    return json.dumps(
        {
            "audited_ref": meta["ref"],
            "audited_commit": meta["commit"],
            "generated_at": meta["generated_at"],
            "summary": summarize(items),
            "items": [asdict(item) for item in items],
        },
        indent=2,
    )


def render_text(items: list[Item], meta: dict[str, str]) -> str:
    lines = [f"Dependency audit {meta['generated_at']} ref={meta['ref']} commit={meta['commit'] or 'unknown'}"]
    for item in sorted(items, key=lambda entry: (entry.kind, entry.component, entry.current)):
        lines.append(
            f"{item.status:12s} {item.current:20s} -> {item.latest or '—':20s} "
            f"{item.delta:10s} {item.component} ({item.module}) {item.reason}"
        )
    return "\n".join(lines)


def audit(root: Path, timeout: float, offline: bool, token: str | None) -> list[Item]:
    items = collect_maven(root, timeout, offline)
    items.extend(collect_actions(root, timeout, offline, token))
    items.extend(collect_pypi(root, timeout, offline))
    items.extend(collect_toolchain(root))
    return items


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root")
    parser.add_argument("--format", choices=("markdown", "json", "text"), default="markdown")
    parser.add_argument("--ref", default=None, help="label for the audited ref")
    parser.add_argument("--timeout", type=float, default=15.0, help="per-request timeout in seconds")
    parser.add_argument("--fail-on-unknown", action="store_true", help="exit 2 when any check failed")
    parser.add_argument("--offline", action="store_true", help="skip all network checks")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    ref = args.ref or git_value(root, "rev-parse", "--abbrev-ref", "HEAD") or "unknown"
    commit = git_value(root, "rev-parse", "--short", "HEAD")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    try:
        items = audit(root, args.timeout, args.offline, token)
    except FatalAuditError as exc:
        print(f"dependency audit failed: {exc}", file=sys.stderr)
        return 1

    meta = {
        "ref": ref,
        "commit": commit,
        "generated_at": generated_at(),
        "date": datetime.now(timezone.utc).date().isoformat(),
    }
    if args.format == "json":
        print(render_json(items, meta))
    elif args.format == "text":
        print(render_text(items, meta))
    else:
        print(render_markdown(items, meta))

    if args.fail_on_unknown and any(item.status == "unknown" for item in items):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

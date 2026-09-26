from __future__ import annotations

import dataclasses
import json
import os
import string
import subprocess
from pathlib import Path
from typing import Mapping, Optional, Sequence

from . import config as config_module
from .proc import run_logged

PROMPTS = Path(__file__).resolve().parent / "prompts"
DENIED_TOOLS = (
    "Bash(git push:*)",
    "Bash(git remote:*)",
    "Bash(git config:*)",
    "Bash(gh:*)",
    "Bash(curl:*)",
    "Bash(wget:*)",
    "Bash(ssh:*)",
    "Bash(scp:*)",
    "WebFetch",
    "WebSearch",
)
READ_ONLY_TOOLS = "Read,Grep,Glob"


@dataclasses.dataclass
class AgentResult:
    ok: bool
    output: dict
    cost_usd: float = 0.0
    session_id: str = ""
    error: str = ""


def render(name: str, values: Mapping[str, object]) -> str:
    template = string.Template((PROMPTS / f"{name}.md").read_text(encoding="utf-8"))
    return template.safe_substitute({k: str(v) for k, v in values.items()})


def agent_env(config, java_home: str = "") -> dict:
    env = dict(config_module.claude_provider_env())
    env.update(os.environ)
    empty_gh_config = Path(config.state_dir) / "gh-empty"
    empty_gh_config.mkdir(parents=True, exist_ok=True)
    env.update({
        "GH_TOKEN": "issue-agent-no-github-access",
        "GITHUB_TOKEN": "issue-agent-no-github-access",
        "GH_CONFIG_DIR": str(empty_gh_config),
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": "credential.helper",
        "GIT_CONFIG_VALUE_0": "",
    })
    env.pop("CLAUDECODE", None)
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = f"{java_home}/bin{os.pathsep}{env.get('PATH', '')}"
    return env


def parse_claude(raw: str) -> AgentResult:
    try:
        data = json.loads(raw)
    except ValueError as error:
        return AgentResult(False, {}, error=f"unparseable claude output: {error}")
    messages = data if isinstance(data, list) else [data]
    result = next((m for m in reversed(messages) if isinstance(m, dict) and m.get("type") == "result"), None)
    if result is None:
        return AgentResult(False, {}, error="claude produced no result message")
    output = result.get("structured_output")
    cost = float(result.get("total_cost_usd") or 0.0)
    session = result.get("session_id") or ""
    if result.get("is_error") or not isinstance(output, dict):
        reason = result.get("subtype") or "error"
        return AgentResult(False, {}, cost, session, f"claude ended with {reason}: {str(result.get('result'))[:500]}")
    return AgentResult(True, output, cost, session)


def claude(
    config,
    prompt: str,
    schema: dict,
    cwd: Path,
    log_path: Path,
    budget_usd: float,
    timeout_min: int,
    read_only: bool,
    resume: Optional[str] = None,
    java_home: str = "",
) -> AgentResult:
    args = [
        config.claude_bin, "-p", "--bare",
        "--model", config_module.resolve_claude_model(config),
        "--output-format", "json",
        "--json-schema", json.dumps(schema),
        "--strict-mcp-config",
        "--max-budget-usd", str(budget_usd),
    ]
    if resume:
        args += ["--resume", resume]
    if read_only:
        args += ["--restricted", "--tools", READ_ONLY_TOOLS, "--no-session-persistence"]
    else:
        args += ["--permission-mode", "bypassPermissions", "--disallowedTools", *DENIED_TOOLS]
    prompt_path = log_path.with_suffix(".prompt.md")
    prompt_path.parent.mkdir(parents=True, exist_ok=True)
    prompt_path.write_text(prompt, encoding="utf-8")
    raw_path = log_path.with_suffix(".json")
    env = agent_env(config, java_home)
    with prompt_path.open("r", encoding="utf-8") as source, raw_path.open("w", encoding="utf-8") as sink, log_path.open("w", encoding="utf-8") as errors:
        completed = subprocess.run(args, cwd=cwd, env=env, stdin=source, stdout=sink, stderr=errors, timeout=timeout_min * 60)
    parsed = parse_claude(raw_path.read_text(encoding="utf-8"))
    if completed.returncode != 0 and parsed.ok:
        parsed.error = f"claude exited {completed.returncode}"
    return parsed


def codex_review(config, prompt: str, schema: dict, cwd: Path, log_path: Path, timeout_min: int) -> AgentResult:
    schema_path = log_path.with_suffix(".schema.json")
    output_path = log_path.with_suffix(".json")
    schema_path.parent.mkdir(parents=True, exist_ok=True)
    schema_path.write_text(json.dumps(schema), encoding="utf-8")
    args = [config.codex_bin, "exec", "-s", "read-only", "--ephemeral", "-C", str(cwd), "--output-schema", str(schema_path), "-o", str(output_path)]
    if config.codex_model:
        args += ["-m", config.codex_model]
    args.append(prompt)
    code = run_logged(args, log_path, cwd=cwd, env=agent_env(config), timeout=timeout_min * 60)
    try:
        output = parse_json_object(output_path.read_text(encoding="utf-8"))
    except OSError as error:
        return AgentResult(False, {}, error=f"codex exited {code} without a verdict: {error}")
    if output is None:
        return AgentResult(False, {}, error=f"codex exited {code} without a parseable verdict")
    return AgentResult(True, output)


def parse_json_object(text: str) -> Optional[dict]:
    candidates = [text]
    start, end = text.find("{"), text.rfind("}")
    if 0 <= start < end:
        candidates.append(text[start:end + 1])
    for candidate in candidates:
        try:
            value = json.loads(candidate)
        except ValueError:
            continue
        if isinstance(value, dict):
            return value
    return None


def tail(path: Path, limit: int = 3000) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")[-limit:]
    except OSError:
        return ""


def joined(items: Sequence[str]) -> str:
    return "\n".join(f"- {item}" for item in items) if items else "- (none)"

from __future__ import annotations

import dataclasses
import json
import os
import re
from pathlib import Path
from typing import Mapping

from . import policy
from .proc import git, run

ENV_PREFIX = "ISSUE_AGENT_"
CLAUDE_SETTINGS = Path.home() / ".claude" / "settings.json"
PROVIDER_ENV_PREFIXES = ("ANTHROPIC_", "CLAUDE_CODE_", "API_TIMEOUT")
REMOTE_RE = re.compile(r"github\.com[:/](?P<repo>[^/\s]+/[^/\s]+?)(?:\.git)?/?$")
DERIVED_FIELDS = frozenset({"repo_root", "home", "repo", "base_branch"})


@dataclasses.dataclass
class Config:
    repo_root: Path
    home: Path
    repo: str
    base_branch: str
    claude_bin: str = "claude"
    codex_bin: str = "codex"
    gh_bin: str = "gh"
    claude_model: str = ""
    codex_model: str = ""
    java_home: str = ""
    java_version: str = "25"
    auto_priorities: str = "p0,p1"
    max_pending_reviews: int = 2
    stale_claim_hours: int = 24
    triage_budget_usd: float = 10.0
    triage_tick_budget_usd: float = 30.0
    implement_budget_usd: float = 100.0
    fix_budget_usd: float = 40.0
    run_budget_usd: float = 300.0
    implement_timeout_min: int = 120
    fix_timeout_min: int = 60
    review_timeout_min: int = 30
    verify_timeout_min: int = 40
    ci_timeout_min: int = 60
    gate_rounds: int = 2
    review_rounds: int = 3
    ci_rounds: int = 2
    notify_cmd: str = ""

    @property
    def state_dir(self) -> Path:
        return self.home / self.repo.replace("/", "-")

    @property
    def base_clone(self) -> Path:
        return self.state_dir / "base"

    @property
    def runs_dir(self) -> Path:
        return self.state_dir / "runs"

    @property
    def lock_path(self) -> Path:
        return self.state_dir / "runner.lock"

    @property
    def remote_url(self) -> str:
        return f"https://github.com/{self.repo}.git"

    @property
    def auto_priority_set(self) -> frozenset:
        return frozenset(p.strip() for p in self.auto_priorities.split(",") if p.strip())


def read_env_file(path: Path) -> dict:
    values = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def apply_settings(config: Config, settings: Mapping[str, str]) -> Config:
    for field in dataclasses.fields(Config):
        key = ENV_PREFIX + field.name.upper()
        if field.name in DERIVED_FIELDS or key not in settings:
            continue
        current = getattr(config, field.name)
        setattr(config, field.name, type(current)(settings[key]))
    return config


def load(repo_root: Path | None = None, environ: Mapping[str, str] | None = None) -> Config:
    environ = os.environ if environ is None else environ
    home = Path(environ.get(ENV_PREFIX + "HOME") or Path.home() / ".issue-agent").expanduser()
    settings = dict(read_env_file(home / "config.env"))
    settings.update({k: v for k, v in environ.items() if k.startswith(ENV_PREFIX)})
    root = repo_root or Path(__file__).resolve().parents[2]
    repo = settings.get(ENV_PREFIX + "REPO") or detect_repo(root)
    base = settings.get(ENV_PREFIX + "BASE_BRANCH") or detect_base_branch(root)
    return apply_settings(Config(repo_root=root, home=home, repo=repo, base_branch=base), settings)


def detect_repo(root: Path) -> str:
    url = git(root, "remote", "get-url", "origin")
    match = REMOTE_RE.search(url)
    if not match:
        raise RuntimeError(f"origin {url} is not a github.com remote; set {ENV_PREFIX}REPO")
    return match.group("repo")


def detect_base_branch(root: Path) -> str:
    listing = git(root, "ls-remote", "--heads", "origin", "dev/v*")
    heads = [line.split("refs/heads/", 1)[1] for line in listing.splitlines() if "refs/heads/" in line]
    branch = policy.newest_dev_line(heads)
    if branch is None:
        raise RuntimeError(f"origin has no dev/vX.Y.Z branch; set {ENV_PREFIX}BASE_BRANCH")
    return branch


def claude_settings() -> dict:
    if CLAUDE_SETTINGS.is_file():
        return json.loads(CLAUDE_SETTINGS.read_text(encoding="utf-8"))
    return {}


def resolve_claude_model(config: Config) -> str:
    model = config.claude_model or claude_settings().get("model") or ""
    if not model:
        raise RuntimeError(f"set {ENV_PREFIX}CLAUDE_MODEL: bare sessions ignore the user's default model")
    return model


def claude_provider_env() -> dict:
    env = claude_settings().get("env") or {}
    return {k: str(v) for k, v in env.items() if k.startswith(PROVIDER_ENV_PREFIXES)}


def resolve_java_home(config: Config) -> str:
    if config.java_home:
        return config.java_home
    probe = Path("/usr/libexec/java_home")
    if probe.exists():
        result = run([str(probe), "-v", config.java_version], check=False)
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    return os.environ.get("JAVA_HOME", "")

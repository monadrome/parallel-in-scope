from __future__ import annotations

import os
import plistlib
import sys
import tempfile
from pathlib import Path

from .proc import run

LAUNCH_AGENTS = Path.home() / "Library" / "LaunchAgents"
TEMP_ROOT = os.path.realpath(tempfile.gettempdir())


def label(config) -> str:
    return "io.github.issue-agent." + config.repo.replace("/", ".")


def plist_path(config) -> Path:
    return LAUNCH_AGENTS / f"{label(config)}.plist"


def stable_path() -> str:
    entries = [p for p in os.environ.get("PATH", "").split(os.pathsep) if p]
    kept = [p for p in entries if not os.path.realpath(p).startswith(TEMP_ROOT)]
    return os.pathsep.join(dict.fromkeys(kept))


def install(config, interval_min: int) -> Path:
    launcher = config.base_clone / "scripts" / "issue-agent.py"
    if not launcher.exists():
        raise RuntimeError(f"{launcher} is missing: merge the runner into {config.base_branch} and run a tick first")
    environment = {"PATH": stable_path(), "HOME": str(Path.home())}
    if os.environ.get("ISSUE_AGENT_HOME"):
        environment["ISSUE_AGENT_HOME"] = os.environ["ISSUE_AGENT_HOME"]
    job = {
        "Label": label(config),
        "ProgramArguments": [sys.executable, str(launcher), "tick"],
        "WorkingDirectory": str(config.state_dir),
        "StartInterval": interval_min * 60,
        "RunAtLoad": False,
        "StandardOutPath": str(config.state_dir / "tick.log"),
        "StandardErrorPath": str(config.state_dir / "tick.log"),
        "EnvironmentVariables": environment,
    }
    target = plist_path(config)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as sink:
        plistlib.dump(job, sink)
    run(["launchctl", "unload", str(target)], check=False)
    run(["launchctl", "load", str(target)])
    return target


def uninstall(config) -> bool:
    target = plist_path(config)
    if not target.exists():
        return False
    run(["launchctl", "unload", str(target)], check=False)
    target.unlink()
    return True


def show(config) -> str:
    target = plist_path(config)
    if not target.exists():
        return f"not installed ({target})"
    loaded = run(["launchctl", "list", label(config)], check=False).returncode == 0
    return f"{target} ({'loaded' if loaded else 'not loaded'})\n" + target.read_text(encoding="utf-8")

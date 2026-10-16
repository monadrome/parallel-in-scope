from __future__ import annotations

from pathlib import Path
from typing import Optional

from .proc import git, run


def refresh_base(config) -> Path:
    target = config.base_clone
    if not (target / ".git").exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--quiet", "--reference-if-able", str(config.repo_root), "--dissociate",
             "--branch", config.base_branch, config.remote_url, str(target)])
    git(target, "fetch", "--quiet", "--prune", "origin")
    git(target, "checkout", "--quiet", "--force", "--detach", f"origin/{config.base_branch}")
    return target


def prepare_clone(config, clone: Path, branch: str) -> str:
    run(["git", "clone", "--quiet", "--reference-if-able", str(config.base_clone), "--dissociate",
         "--branch", config.base_branch, config.remote_url, str(clone)])
    git(clone, "checkout", "--quiet", "-b", branch)
    git(clone, "remote", "set-url", "--push", "origin", "DISABLED-push-through-the-runner")
    return git(clone, "rev-parse", "HEAD")


def export_patch(clone: Path, base_sha: str, target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    patch = run(["git", "format-patch", "--stdout", "--binary", f"{base_sha}..HEAD"], cwd=clone).stdout
    target.write_text(patch, encoding="utf-8")
    return target


def push(config, clone: Path, branch: str, expected_remote_sha: str) -> str:
    head = git(clone, "rev-parse", "HEAD")
    lease = f"--force-with-lease=refs/heads/{branch}:{expected_remote_sha}"
    git(clone, "push", "--quiet", lease, config.remote_url, f"HEAD:refs/heads/{branch}")
    return head


def rebase_onto_base(config, clone: Path, base_sha: str) -> Optional[str]:
    git(clone, "fetch", "--quiet", config.remote_url, f"refs/heads/{config.base_branch}")
    fresh = git(clone, "rev-parse", "FETCH_HEAD")
    if fresh == base_sha:
        return base_sha
    result = run(["git", "rebase", "--quiet", fresh], cwd=clone, check=False)
    if result.returncode != 0:
        run(["git", "rebase", "--abort"], cwd=clone, check=False)
        return None
    return fresh

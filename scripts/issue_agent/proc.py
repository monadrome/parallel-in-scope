from __future__ import annotations

import datetime
import subprocess
import sys
from pathlib import Path
from typing import Mapping, Sequence


class CommandError(RuntimeError):
    def __init__(self, args: Sequence[str], result: subprocess.CompletedProcess):
        self.result = result
        tail = (result.stderr or result.stdout or "").strip()[-2000:]
        super().__init__(f"{' '.join(args[:4])} exited {result.returncode}: {tail}")


def run(
    args: Sequence[str],
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    timeout: float | None = None,
    check: bool = True,
    stdin: str | None = None,
) -> subprocess.CompletedProcess:
    result = subprocess.run(
        list(args),
        cwd=cwd,
        env=dict(env) if env is not None else None,
        timeout=timeout,
        input=stdin if stdin is not None else "",
        capture_output=True,
        text=True,
    )
    if check and result.returncode != 0:
        raise CommandError(args, result)
    return result


def run_logged(
    args: Sequence[str],
    log_path: Path,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    timeout: float | None = None,
) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as sink:
        completed = subprocess.run(
            list(args),
            cwd=cwd,
            env=dict(env) if env is not None else None,
            timeout=timeout,
            stdin=subprocess.DEVNULL,
            stdout=sink,
            stderr=subprocess.STDOUT,
            text=True,
        )
    return completed.returncode


def git(cwd: Path, *args: str, check: bool = True) -> str:
    return run(["git", *args], cwd=cwd, check=check).stdout.strip()


def log(message: str) -> None:
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"{stamp} {message}", file=sys.stdout, flush=True)

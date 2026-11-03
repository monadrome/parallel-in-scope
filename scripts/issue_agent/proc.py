from __future__ import annotations

import datetime
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Mapping, Sequence


class CommandError(RuntimeError):
    def __init__(self, args: Sequence[str], result: subprocess.CompletedProcess):
        self.result = result
        tail = (result.stderr or result.stdout or "").strip()[-2000:]
        super().__init__(f"{' '.join(args[:4])} exited {result.returncode}: {tail}")


def _group_exists(pid: int) -> bool:
    try:
        os.killpg(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def terminate_process_group(process: subprocess.Popen, grace: float = 1.0) -> None:
    """Terminate the whole session, escalating while any descendant remains."""
    if not _group_exists(process.pid):
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        pass
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline and _group_exists(process.pid):
        time.sleep(0.05)
    if _group_exists(process.pid):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


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
        process = subprocess.Popen(
            list(args),
            cwd=cwd,
            env=dict(env) if env is not None else None,
            stdin=subprocess.DEVNULL,
            stdout=sink,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        try:
            code = process.wait(timeout=timeout)
            terminate_process_group(process)
            return code
        except subprocess.TimeoutExpired:
            terminate_process_group(process, grace=5)
            raise


def git(cwd: Path, *args: str, check: bool = True) -> str:
    return run(["git", *args], cwd=cwd, check=check).stdout.strip()


def log(message: str) -> None:
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"{stamp} {message}", file=sys.stdout, flush=True)

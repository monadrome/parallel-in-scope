import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import { ensureDir } from '../util/fsx.js';
import { monotonicMs } from '../util/time.js';

export interface ProcessSpec {
  command: string;
  args: string[];
  cwd: string;
  stdoutPath: string;
  stderrPath: string;
  timeoutMs: number;
  graceMs: number;
  env?: Record<string, string | null>;
}

export interface ProcessResult {
  status: 'exited' | 'timed_out' | 'spawn_error';
  exitCode: number | null;
  signal: NodeJS.Signals | null;
  timedOut: boolean;
  durationMs: number;
  pid: number | null;
  spawnError: string | null;
  stdoutPath: string;
  stderrPath: string;
}

function signalGroup(pid: number, signal: NodeJS.Signals): void {
  try {
    process.kill(-pid, signal);
    return;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === 'ESRCH') {
      return;
    }
  }
  try {
    process.kill(pid, signal);
  } catch {
    return;
  }
}

export async function runLoggedProcess(spec: ProcessSpec): Promise<ProcessResult> {
  await ensureDir(path.dirname(spec.stdoutPath));
  await ensureDir(path.dirname(spec.stderrPath));
  const stdoutHandle = await fs.open(spec.stdoutPath, 'wx', 0o644);
  const stderrHandle = await fs.open(spec.stderrPath, 'wx', 0o644);
  const started = monotonicMs();
  const environment: NodeJS.ProcessEnv = { ...process.env };
  for (const [key, value] of Object.entries(spec.env ?? {})) {
    if (value === null) {
      delete environment[key];
    } else {
      environment[key] = value;
    }
  }

  return new Promise<ProcessResult>((resolve) => {
    let settled = false;
    let timedOut = false;
    let spawnError: string | null = null;
    let timeoutTimer: NodeJS.Timeout | null = null;
    let graceTimer: NodeJS.Timeout | null = null;
    let settleTimer: NodeJS.Timeout | null = null;
    let childPid: number | null = null;

    const clearTimers = (): void => {
      if (timeoutTimer !== null) {
        clearTimeout(timeoutTimer);
        timeoutTimer = null;
      }
      if (graceTimer !== null) {
        clearTimeout(graceTimer);
        graceTimer = null;
      }
      if (settleTimer !== null) {
        clearTimeout(settleTimer);
        settleTimer = null;
      }
    };

    const finish = (status: ProcessResult['status'], exitCode: number | null, signal: NodeJS.Signals | null): void => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimers();
      const durationMs = monotonicMs() - started;
      void Promise.allSettled([stdoutHandle.sync(), stderrHandle.sync()]).then(() =>
        Promise.allSettled([stdoutHandle.close(), stderrHandle.close()])).then(() => {
        resolve({
          status,
          exitCode,
          signal,
          timedOut,
          durationMs,
          pid: childPid,
          spawnError,
          stdoutPath: spec.stdoutPath,
          stderrPath: spec.stderrPath,
        });
      });
    };

    let child;
    try {
      child = spawn(spec.command, spec.args, {
        cwd: spec.cwd,
        detached: true,
        stdio: ['ignore', stdoutHandle.fd, stderrHandle.fd],
        env: environment,
      });
    } catch (error) {
      spawnError = error instanceof Error ? error.message : String(error);
      void Promise.allSettled([stdoutHandle.close(), stderrHandle.close()]).then(() =>
        finish('spawn_error', null, null));
      return;
    }
    childPid = child.pid ?? null;

    timeoutTimer = setTimeout(() => {
      timedOut = true;
      if (childPid !== null) {
        signalGroup(childPid, 'SIGTERM');
      }
      graceTimer = setTimeout(() => {
        if (childPid !== null) {
          signalGroup(childPid, 'SIGKILL');
        }
        settleTimer = setTimeout(() => {
          finish('timed_out', null, 'SIGKILL');
        }, 5000);
        settleTimer.unref();
      }, spec.graceMs);
      graceTimer.unref();
    }, spec.timeoutMs);
    timeoutTimer.unref();

    child.on('error', (error) => {
      spawnError = error.message;
      finish('spawn_error', null, null);
    });
    child.on('close', (code, signal) => {
      finish(timedOut ? 'timed_out' : 'exited', code, signal);
    });
  });
}

export async function probeCommand(
  command: string,
  args: string[],
  timeoutMs = 15_000,
): Promise<{ found: boolean; version: string }> {
  const { execFile } = await import('node:child_process');
  const { promisify } = await import('node:util');
  try {
    const result = await promisify(execFile)(command, args, {
      timeout: timeoutMs,
      maxBuffer: 2 * 1024 * 1024,
      encoding: 'utf8',
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
    });
    const firstLine = result.stdout.split('\n').map((line) => line.trim()).find((line) => line.length > 0);
    return { found: true, version: firstLine ?? 'unknown' };
  } catch (error) {
    const failure = error as NodeJS.ErrnoException & { stdout?: string; killed?: boolean };
    if (failure.code === 'ENOENT') {
      return { found: false, version: 'missing' };
    }
    if (failure.killed === true) {
      return { found: true, version: 'unknown' };
    }
    const firstLine = (failure.stdout ?? '').split('\n').map((line) => line.trim()).find((line) => line.length > 0);
    return { found: true, version: firstLine ?? 'unknown' };
  }
}

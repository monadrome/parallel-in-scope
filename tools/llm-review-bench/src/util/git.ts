import { execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';
import { BenchError, ValidationError } from './errors.js';
import { assertGitRev } from './path-safety.js';

const execFileAsync = promisify(execFile);
const MAX_BUFFER = 128 * 1024 * 1024;

export interface GitOutput {
  stdout: string;
  stderr: string;
  code: number;
}

export interface GitOptions {
  timeoutMs?: number;
  env?: Record<string, string | null>;
}

function gitEnv(): NodeJS.ProcessEnv {
  return {
    ...process.env,
    GIT_TERMINAL_PROMPT: '0',
    GIT_OPTIONAL_LOCKS: '0',
    LC_ALL: 'C',
  };
}

export async function gitRaw(cwd: string, args: string[], options: GitOptions = {}): Promise<GitOutput> {
  const env = gitEnv();
  for (const [key, value] of Object.entries(options.env ?? {})) {
    if (value === null) {
      delete env[key];
    } else {
      env[key] = value;
    }
  }
  try {
    const result = await execFileAsync('git', args, {
      cwd,
      env,
      encoding: 'utf8',
      maxBuffer: MAX_BUFFER,
      timeout: options.timeoutMs ?? 30_000,
    });
    return { stdout: result.stdout, stderr: result.stderr, code: 0 };
  } catch (error) {
    const failure = error as {
      stdout?: string;
      stderr?: string;
      code?: number | string;
      killed?: boolean;
      signal?: string;
      message?: string;
    };
    if (failure.killed === true || (failure.signal !== undefined && failure.signal !== null)) {
      throw new BenchError(`git ${args.join(' ')} timed out after ${options.timeoutMs ?? 30_000}ms in ${cwd}`);
    }
    if (typeof failure.code === 'number') {
      return { stdout: failure.stdout ?? '', stderr: failure.stderr ?? '', code: failure.code };
    }
    throw new BenchError(`could not execute git in ${cwd}: ${failure.message ?? String(error)}`, { cause: error });
  }
}

export async function git(cwd: string, args: string[], options: GitOptions = {}): Promise<string> {
  const result = await gitRaw(cwd, args, options);
  if (result.code !== 0) {
    const detail = result.stderr.trim() || result.stdout.trim() || `exit code ${result.code}`;
    throw new BenchError(`git ${args.join(' ')} failed in ${cwd}: ${detail}`);
  }
  return result.stdout;
}

export async function resolveRepo(input: string): Promise<string> {
  const result = await gitRaw(input, ['rev-parse', '--show-toplevel'], { timeoutMs: 15_000 });
  if (result.code !== 0) {
    throw new ValidationError(`${input} is not a git work tree: ${result.stderr.trim() || 'git rev-parse failed'}`);
  }
  const root = result.stdout.trim();
  if (root.length === 0) {
    throw new ValidationError(`${input} did not resolve to a git work tree root`);
  }
  const bare = await gitRaw(root, ['rev-parse', '--is-bare-repository']);
  if (bare.stdout.trim() === 'true') {
    throw new ValidationError(`${root} is a bare repository; a non-bare work tree is required`);
  }
  return path.resolve(root);
}

export async function resolveCommit(repo: string, revision: string): Promise<string> {
  const rev = assertGitRev(revision);
  const result = await gitRaw(repo, ['rev-parse', '--verify', '--quiet', `${rev}^{commit}`]);
  if (result.code !== 0 || result.stdout.trim().length === 0) {
    throw new ValidationError(`unknown commit ${JSON.stringify(revision)} in ${repo}`);
  }
  return result.stdout.trim();
}

export interface CommitInfo {
  sha: string;
  parents: string[];
  committedAt: string;
  subject: string;
  body: string;
}

export async function commitInfo(repo: string, sha: string): Promise<CommitInfo> {
  const resolved = await resolveCommit(repo, sha);
  const output = await git(repo, ['show', '-s', '--format=%H%x00%P%x00%cI%x00%s%x00%b', resolved]);
  const [full, parents = '', date = '', subject = '', body = ''] = output.split('\0');
  if (full === undefined) {
    throw new BenchError(`git show returned no commit metadata for ${sha}`);
  }
  return {
    sha: full.trim(),
    parents: parents.trim().length === 0 ? [] : parents.trim().split(/\s+/),
    committedAt: date.trim(),
    subject: subject.trim(),
    body: body.trim(),
  };
}

export async function isAncestor(repo: string, ancestor: string, descendant: string): Promise<boolean> {
  const result = await gitRaw(repo, ['merge-base', '--is-ancestor', ancestor, descendant], { timeoutMs: 30_000 });
  if (result.code === 0) {
    return true;
  }
  if (result.code === 1) {
    return false;
  }
  throw new BenchError(`git merge-base failed: ${result.stderr.trim()}`);
}

export async function diffText(repo: string, base: string, head: string): Promise<string> {
  return git(repo, [
    'diff',
    '--no-color',
    '--no-ext-diff',
    '--find-renames',
    '--src-prefix=a/',
    '--dst-prefix=b/',
    base,
    head,
  ], { timeoutMs: 120_000 });
}

export interface DiffFile {
  path: string;
  status: string;
  additions: number;
  deletions: number;
}

export async function diffFiles(repo: string, base: string, head: string): Promise<DiffFile[]> {
  const numstat = await git(repo, ['diff', '--numstat', '--no-renames', base, head], { timeoutMs: 120_000 });
  const nameStatus = await git(repo, ['diff', '--name-status', '--no-renames', base, head], { timeoutMs: 120_000 });
  const statuses = new Map<string, string>();
  for (const line of nameStatus.split('\n')) {
    if (line.trim().length === 0) {
      continue;
    }
    const [status, ...rest] = line.split('\t');
    if (status !== undefined && rest.length > 0) {
      statuses.set(rest.join('\t'), status);
    }
  }
  return numstat
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => {
      const [added, deleted, ...rest] = line.split('\t');
      const file = rest.join('\t');
      return {
        path: file,
        status: statuses.get(file) ?? 'M',
        additions: added === '-' ? 0 : Number(added),
        deletions: deleted === '-' ? 0 : Number(deleted),
      };
    });
}

export async function showCommitDiff(repo: string, sha: string): Promise<string> {
  return git(repo, [
    'show',
    '--format=',
    '--no-color',
    '--no-ext-diff',
    '--no-renames',
    '--unified=0',
    sha,
  ], { timeoutMs: 120_000 });
}

export async function changedFilesInCommit(repo: string, sha: string): Promise<string[]> {
  const output = await git(repo, ['show', '--format=', '--name-only', '--no-renames', sha]);
  return output.split('\n').map((line) => line.trim()).filter((line) => line.length > 0);
}

export async function worktreeAdd(repo: string, directory: string, sha: string, timeoutMs = 120_000): Promise<void> {
  await git(repo, ['worktree', 'add', '--detach', directory, sha], { timeoutMs });
}

export async function worktreeRemove(repo: string, directory: string, timeoutMs = 60_000): Promise<void> {
  const result = await gitRaw(repo, ['worktree', 'remove', '--force', directory], { timeoutMs });
  if (result.code !== 0) {
    const prune = await gitRaw(repo, ['worktree', 'prune'], { timeoutMs });
    if (prune.code !== 0) {
      throw new BenchError(`git worktree remove failed for ${directory}: ${result.stderr.trim() || `exit ${result.code}`}`);
    }
  }
}

export async function worktreePrune(repo: string, timeoutMs = 60_000): Promise<void> {
  await git(repo, ['worktree', 'prune'], { timeoutMs });
}

export async function statusPorcelain(directory: string): Promise<string> {
  return git(directory, ['status', '--porcelain', '--untracked-files=normal']);
}

export async function remoteUrl(repo: string): Promise<string | null> {
  const result = await gitRaw(repo, ['config', '--get', 'remote.origin.url']);
  const value = result.stdout.trim();
  return result.code === 0 && value.length > 0 ? value : null;
}

export interface LogEntry {
  sha: string;
  committedAt: string;
  subject: string;
}

export async function recentCommits(repo: string, limit: number): Promise<LogEntry[]> {
  const output = await git(repo, ['log', '--no-merges', '-n', String(limit), '--format=%H%x1f%cI%x1f%s']);
  return output
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => {
      const [sha = '', committedAt = '', subject = ''] = line.split('\x1f');
      return { sha, committedAt, subject };
    });
}

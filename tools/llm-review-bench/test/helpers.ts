import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { TestContext } from 'node:test';

export const CLI = fileURLToPath(new URL('../src/cli.js', import.meta.url));

export async function makeTempDir(t: TestContext, prefix = 'lrb-test-'): Promise<string> {
  const dir = await mkdtemp(path.join(os.tmpdir(), prefix));
  t.after(async () => {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      try {
        await rm(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
        return;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    }
    await rm(dir, { recursive: true, force: true }).catch(() => undefined);
  });
  return dir;
}

export function runGit(cwd: string, args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

export async function initRepo(dir: string): Promise<void> {
  await mkdir(dir, { recursive: true });
  runGit(dir, ['init', '-q', '-b', 'main']);
  runGit(dir, ['config', 'user.email', 'test@example.com']);
  runGit(dir, ['config', 'user.name', 'Test User']);
  runGit(dir, ['config', 'commit.gpgsign', 'false']);
  const hooks = path.join(dir, '.git', 'test-no-hooks');
  mkdirSync(hooks, { recursive: true });
  runGit(dir, ['config', 'core.hooksPath', hooks]);
}

export async function writeRepoFile(repo: string, file: string, content: string): Promise<void> {
  const full = path.join(repo, file);
  await mkdir(path.dirname(full), { recursive: true });
  await writeFile(full, content, 'utf8');
}

export function commitAll(repo: string, message: string): string {
  runGit(repo, ['add', '-A']);
  runGit(repo, ['commit', '-q', '-m', message]);
  return runGit(repo, ['rev-parse', 'HEAD']);
}

export interface BaseRepo {
  repo: string;
  initial: string;
  buggy: string;
}

export async function createBaseRepo(t: TestContext): Promise<BaseRepo> {
  const root = await makeTempDir(t, 'lrb-repo-');
  const repo = path.join(root, 'repo');
  await initRepo(repo);
  await writeRepoFile(repo, 'README.md', '# demo\n\nA tiny repository for benchmark tests.\n');
  await writeRepoFile(repo, 'src/math.ts', 'export function add(a: number, b: number): number { return a + b; }\n');
  await writeRepoFile(repo, 'docs/guide.md', '# Guide\n\nUse add for sums.\n');
  const initial = commitAll(repo, 'initial commit');
  await writeRepoFile(repo, 'src/math.ts', 'export function add(a: number, b: number): number { return a - b; }\n');
  const buggy = commitAll(repo, 'refactor: simplify add');
  return { repo, initial, buggy };
}

export function addFix(repo: string): string {
  writeRepoFileSync(repo, 'src/math.ts', 'export function add(a: number, b: number): number { return a + b; }\n');
  writeRepoFileSync(repo, 'src/math.test.ts', 'import { add } from "./math";\n\ntest("add", () => { expect(add(2, 3)).toBe(5); });\n');
  runGit(repo, ['add', '-A']);
  runGit(repo, ['commit', '-q', '-m', 'fix: restore addition and add regression test']);
  return runGit(repo, ['rev-parse', 'HEAD']);
}

function writeRepoFileSync(repo: string, file: string, content: string): void {
  const full = path.join(repo, file);
  mkdirSync(path.dirname(full), { recursive: true });
  writeFileSync(full, content, 'utf8');
}

export interface CliResult {
  code: number;
  stdout: string;
  stderr: string;
}

export function runCli(
  args: string[],
  options: { cwd?: string; env?: NodeJS.ProcessEnv; timeoutMs?: number } = {},
): Promise<CliResult> {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [CLI, ...args], {
      cwd: options.cwd ?? process.cwd(),
      env: { ...process.env, ...options.env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk: string) => {
      stderr += chunk;
    });
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
    }, options.timeoutMs ?? 60_000);
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code: code ?? -1, stdout, stderr });
    });
  });
}

export async function runCliJson<T = Record<string, unknown>>(
  args: string[],
  options: { cwd?: string; env?: NodeJS.ProcessEnv; timeoutMs?: number } = {},
): Promise<{ result: CliResult; json: T }> {
  const result = await runCli(args, options);
  if (result.code !== 0) {
    throw new Error(`CLI failed (${result.code}): ${result.stderr}\n${result.stdout}`);
  }
  return { result, json: JSON.parse(result.stdout) as T };
}

export async function writeJson(file: string, value: unknown): Promise<void> {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

export async function readJson<T>(file: string): Promise<T> {
  return JSON.parse(await readFile(file, 'utf8')) as T;
}

export async function writeFixture(dir: string, name: string, spec: unknown): Promise<string> {
  const file = path.join(dir, name);
  await writeJson(file, spec);
  return file;
}

export async function writeSystems(
  dir: string,
  fixture: string,
  extra: Record<string, unknown> = {},
): Promise<string> {
  const file = path.join(dir, 'systems.json');
  await writeJson(file, {
    systems: [
      {
        agent: 'fixture',
        provider: 'fixture',
        model: 'fixture-model',
        effort: 'none',
        fixture,
      },
    ],
    budget: 10,
    concurrency: 1,
    ...extra,
  });
  return file;
}

export function finding(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    file: 'src/math.ts',
    line: 1,
    title: 'add subtracts instead of adding',
    trigger: 'add(2, 3)',
    consequence: 'returns -1 instead of 5',
    evidence: 'src/math.ts:1 uses a - b',
    severity: 'P1',
    confidence: 0.9,
    suggestedFix: 'restore a + b',
    ...overrides,
  };
}

export async function readText(file: string): Promise<string> {
  return readFile(file, 'utf8');
}

export function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

export async function waitFor(predicate: () => boolean | Promise<boolean>, timeoutMs = 5000): Promise<boolean> {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (await predicate()) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return false;
}

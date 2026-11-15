import fs from 'node:fs/promises';
import path from 'node:path';
import { NotFoundError, ValidationError } from '../util/errors.js';
import {
  ensureDir,
  pathExists,
  readJsonFile,
  readTextFile,
  sha256File,
  writeImmutable,
  writeJsonImmutable,
} from '../util/fsx.js';
import { resolveWithin } from '../util/path-safety.js';
import { nowIso } from '../util/time.js';
import {
  commitInfo,
  diffFiles,
  diffText,
  gitRaw,
  remoteUrl,
  resolveRepo,
  statusPorcelain,
  worktreeAdd,
  worktreePrune,
} from '../util/git.js';
import type { RunManifest, CaseManifest, CaseMode, CaseSource, WorkingSnapshot } from '../protocol/types.js';
import { CONTEXT_SCHEMA } from '../protocol/types.js';
import { CASE_SCHEMA } from '../protocol/types.js';
import type { RunStore } from '../protocol/store.js';
import { detectRulesFiles, generateContextMd } from './context.js';

export const MAX_CONTEXT_BYTES = 200_000;

export interface PrepareCaseOptions {
  store: RunStore;
  runId: string;
  mode: CaseMode;
  source: CaseSource;
  rerunOf: string | null;
  working: WorkingSnapshot | null;
  objectiveCommit: string | null;
  repoRoot: string;
  base: string;
  head: string;
  fixCommit: string | null;
  contextFile: string | null;
  rangeLabel: string;
}

export interface PreparedCase {
  caseManifest: CaseManifest;
  context: string;
  diff: string;
}

export function manifestPaths(): RunManifest['paths'] {
  return {
    inputsDir: 'inputs',
    snapshotDir: 'snapshot',
    reviewsDir: 'reviews',
    logsDir: 'logs',
    derivedDir: 'derived',
    reportsDir: 'reports',
  };
}

export async function prepareCase(options: PrepareCaseOptions): Promise<PreparedCase> {
  const { store, runId, mode, source, repoRoot, base, head, fixCommit } = options;
  const repo = await resolveRepo(repoRoot);
  if (repo !== path.resolve(repoRoot)) {
    throw new ValidationError(`repository root resolved to ${repo}, expected ${path.resolve(repoRoot)}`);
  }
  const providedContext = options.contextFile === null
    ? null
    : await readTextFile(options.contextFile, 'context file');
  if (providedContext !== null) {
    if (providedContext.trim().length === 0) {
      throw new ValidationError(`context file ${options.contextFile} is empty`);
    }
    const providedBytes = Buffer.byteLength(providedContext, 'utf8');
    if (providedBytes > MAX_CONTEXT_BYTES) {
      throw new ValidationError(
        `context is ${providedBytes} bytes, exceeding the ${MAX_CONTEXT_BYTES} byte prompt safety limit; pass a shorter --context`,
      );
    }
  }
  await store.createDir();
  const snapshotPath = store.resolve('snapshot');
  if (await pathExists(snapshotPath)) {
    throw new ValidationError(`snapshot already exists at ${snapshotPath}; choose a new run or clean the old one`);
  }
  await worktreeAdd(repo, snapshotPath, head);

  const commit = await commitInfo(repo, options.objectiveCommit ?? (source === 'working' ? base : head));
  const diff = await diffText(repo, base, head);
  const files = await diffFiles(repo, base, head);
  const diffPath = store.resolve('inputs', 'diff.patch');
  await writeImmutable(diffPath, diff);

  const contextPath = store.resolve('inputs', 'context.md');
  const rulesFiles = await detectRulesFiles(repo);
  let context: string;
  let contextSource: 'generated' | 'provided';
  if (providedContext !== null) {
    context = providedContext;
    contextSource = 'provided';
  } else {
    contextSource = 'generated';
    context = generateContextMd({
      mode,
      source,
      repoRoot: repo,
      base,
      head,
      fixCommit,
      subject: commit.subject,
      body: commit.body,
      files,
      diffPath,
      contextPath,
      rulesFiles,
    });
  }
  const contextBytes = Buffer.byteLength(context, 'utf8');
  if (contextBytes > MAX_CONTEXT_BYTES) {
    throw new ValidationError(
      `context is ${contextBytes} bytes, exceeding the ${MAX_CONTEXT_BYTES} byte prompt safety limit; pass a shorter --context`,
    );
  }
  await writeImmutable(contextPath, context);

  const stat = await fs.stat(diffPath);
  const remote = await remoteUrl(repo);
  const contextSha256 = await sha256File(contextPath);
  const contextJsonPath = store.resolve('inputs', 'context.json');
  const contextJson = {
    schemaVersion: CONTEXT_SCHEMA,
    runId,
    mode,
    source,
    rerunOf: options.rerunOf,
    working: options.working,
    createdAt: nowIso(),
    repository: { root: repo, name: path.basename(repo), remote },
    commits: { base, head, fixCommit },
    range: { from: base, to: head, label: options.rangeLabel },
    objective: { subject: commit.subject, body: commit.body },
    diff: {
      path: 'inputs/diff.patch',
      sha256: await sha256File(diffPath),
      bytes: stat.size,
      files,
    },
    context: {
      path: 'inputs/context.md',
      sha256: contextSha256,
      bytes: contextBytes,
      source: contextSource,
    },
    snapshot: { path: 'snapshot', head, policy: 'read-only-by-policy' as const },
    rulesFiles,
  };
  await writeJsonImmutable(contextJsonPath, contextJson);
  const contextJsonStat = await fs.stat(contextJsonPath);
  const caseManifest: CaseManifest = {
    schemaVersion: CASE_SCHEMA,
    runId,
    mode,
    source,
    rerunOf: options.rerunOf,
    working: options.working,
    createdAt: nowIso(),
    repository: { root: repo, name: path.basename(repo), remote },
    commits: { base, head, fixCommit },
    range: { from: base, to: head, label: options.rangeLabel },
    diff: contextJson.diff,
    context: contextJson.context,
    contextJson: {
      path: 'inputs/context.json',
      sha256: await sha256File(contextJsonPath),
      bytes: contextJsonStat.size,
    },
    snapshot: contextJson.snapshot,
    rulesFiles,
  };
  await writeJsonImmutable(store.resolve('inputs', 'manifest.json'), caseManifest);

  return { caseManifest, context, diff };
}

export async function readCaseManifest(store: RunStore): Promise<CaseManifest> {
  const manifest = await store.readManifest();
  const file = store.resolve(manifest.paths.inputsDir, 'manifest.json');
  if (!(await pathExists(file))) {
    throw new NotFoundError(`case manifest is missing at ${file}`);
  }
  const parsed = await readJsonFile<CaseManifest>(file, 'case manifest');
  if (parsed.schemaVersion !== CASE_SCHEMA) {
    throw new ValidationError(`case manifest: unsupported schema ${parsed.schemaVersion}`);
  }
  return parsed;
}

export async function verifyInputs(store: RunStore): Promise<CaseManifest> {
  const manifest = await store.readManifest();
  const caseManifest = await readCaseManifest(store);
  const checks: [string, string, string][] = [
    ['context', store.resolve(manifest.paths.inputsDir, 'context.md'), manifest.hashes.contextSha256],
    ['context json', store.resolve(manifest.paths.inputsDir, 'context.json'), manifest.hashes.contextJsonSha256],
    ['diff', store.resolve(manifest.paths.inputsDir, 'diff.patch'), manifest.hashes.diffSha256],
    ['case manifest', store.resolve(manifest.paths.inputsDir, 'manifest.json'), manifest.hashes.caseManifestSha256],
    ['prompt', store.resolve(manifest.promptPath), manifest.hashes.promptSha256],
  ];
  for (const [label, file, expected] of checks) {
    if (!(await pathExists(file))) {
      throw new NotFoundError(`immutable ${label} is missing at ${file}`);
    }
    const actual = await sha256File(file);
    if (actual !== expected) {
      throw new ValidationError(`immutable ${label} ${file} changed (expected sha256 ${expected}, got ${actual})`);
    }
  }
  return caseManifest;
}

export async function ensureSnapshot(store: RunStore): Promise<void> {
  const manifest = await store.readManifest();
  const repo = manifest.repository.root;
  const snapshot = store.resolve(manifest.paths.snapshotDir);
  if (!(await pathExists(snapshot))) {
    await worktreePrune(repo);
    await ensureDir(path.dirname(snapshot));
    await worktreeAdd(repo, snapshot, manifest.commits.head);
    return;
  }
  const headResult = await gitRaw(snapshot, ['rev-parse', 'HEAD']);
  if (headResult.code !== 0 || headResult.stdout.trim() !== manifest.commits.head) {
    throw new ValidationError(
      `snapshot ${snapshot} is not at ${manifest.commits.head}; run cleanup and resume again`,
    );
  }
  const status = await statusPorcelain(snapshot);
  if (status.trim().length > 0) {
    throw new ValidationError(
      `snapshot ${snapshot} has local modifications; run cleanup to recreate it, then resume`,
    );
  }
}

export function resolveRunPath(store: RunStore, relative: string): string {
  return resolveWithin(store.runDir, relative);
}

import path from 'node:path';
import { UsageError, ValidationError } from '../util/errors.js';
import { writeImmutable, sha256File } from '../util/fsx.js';
import { nowIso } from '../util/time.js';
import { createRunId, repoNameFromRoot, shortSha } from '../util/ids.js';
import { assertAgentName } from '../util/path-safety.js';
import {
  commitInfo,
  isAncestor,
  resolveCommit,
} from '../util/git.js';
import {
  DEFAULT_BUDGET,
  DEFAULT_CONCURRENCY,
  DEFAULT_MAX_OUTPUT_BYTES,
  DEFAULT_TIMEOUTS,
  REAL_AGENTS,
  applySystemOverrides,
  buildConfig,
  loadSystemsFile,
} from '../protocol/config.js';
import type {
  BenchmarkConfig,
  CaseManifest,
  CaseMode,
  CaseSource,
  RunManifest,
  RunState,
  SystemConfig,
  TimeoutConfig,
  WorkingSnapshot,
} from '../protocol/types.js';
import { RUN_SCHEMA } from '../protocol/types.js';
import { RunStore, createInitialState, ensureBenchRoot } from '../protocol/store.js';
import { createAdapter } from '../adapters/registry.js';
import { assertFixtureFile } from '../adapters/fixture.js';
import { manifestPaths, prepareCase, readCaseManifest } from '../case/prepare.js';
import { renderPrompt } from '../case/context.js';
import { captureWorkingTree } from '../case/working.js';
import { TOOL_NAME, TOOL_VERSION } from '../version.js';
import {
  finalizeArtifacts,
  type FinalizeOptions,
  type FinalizeResult,
} from '../evaluation/pipeline.js';

export interface RunCommandOptions {
  output?: string;
  repo?: string;
  fix?: string;
  commit?: string;
  base?: string;
  context?: string;
  working?: boolean;
  agents?: string;
  systems?: string;
  model?: string;
  effort?: string;
  budget?: number;
  concurrency?: number;
  timeout?: number;
  reviewTimeout?: number;
  adjudicationTimeout?: number;
  cleanupTimeout?: number;
  killGrace?: number;
}

export interface CaseSpec {
  mode: CaseMode;
  source: CaseSource;
  base: string;
  head: string;
  fixCommit: string | null;
  workingBase: string | null;
  rangeLabel: string;
}

export function resolveBenchRoot(output?: string): string {
  return path.resolve(output ?? path.join(process.cwd(), '.review-benchmark'));
}

export function resolveRootForRepo(output: string | undefined, repo: string): string {
  return path.resolve(output ?? path.join(repo, '.review-benchmark'));
}

export async function openRun(root: string, runId: string): Promise<{ store: RunStore; manifest: RunManifest; state: RunState }> {
  const store = new RunStore(root, runId);
  if (!(await store.exists())) {
    throw new ValidationError(`run ${runId} not found under ${root}/runs; pass --output if the benchmark root differs`);
  }
  const manifest = await store.readManifest();
  const state = await store.readState();
  return { store, manifest, state };
}

export async function resolveFixCase(repo: string, fixRevision: string): Promise<CaseSpec> {
  const fix = await resolveCommit(repo, fixRevision);
  const fixInfo = await commitInfo(repo, fix);
  if (fixInfo.parents.length !== 1) {
    throw new ValidationError(
      `fix commit ${fix} has ${fixInfo.parents.length} parents; --fix requires a non-merge commit whose parent is the buggy commit`,
    );
  }
  const buggy = fixInfo.parents[0] as string;
  const buggyInfo = await commitInfo(repo, buggy);
  const base = buggyInfo.parents[0];
  if (base === undefined) {
    throw new ValidationError(`buggy commit ${buggy} has no parent; cannot review buggy^..buggy`);
  }
  if (!(await isAncestor(repo, buggy, fix))) {
    throw new ValidationError(`buggy commit ${buggy} is not an ancestor of fix commit ${fix}`);
  }
  return {
    mode: 'historical',
    source: 'fix',
    base,
    head: buggy,
    fixCommit: fix,
    workingBase: null,
    rangeLabel: `${shortSha(base)}..${shortSha(buggy)} (buggy^..buggy)`,
  };
}

export async function resolveLiveCase(
  repo: string,
  revision?: string,
  baseRevision?: string,
): Promise<CaseSpec> {
  const head = await resolveCommit(repo, revision ?? 'HEAD');
  const headInfo = await commitInfo(repo, head);
  let base: string;
  if (baseRevision !== undefined) {
    base = await resolveCommit(repo, baseRevision);
  } else {
    const parent = headInfo.parents[0];
    if (parent === undefined) {
      throw new ValidationError(`commit ${head} has no parent; review a non-root commit or pass --base`);
    }
    base = parent;
  }
  if (!(await isAncestor(repo, base, head))) {
    throw new ValidationError(`base commit ${base} is not an ancestor of head commit ${head}`);
  }
  return {
    mode: 'live',
    source: 'commit',
    base,
    head,
    fixCommit: null,
    workingBase: null,
    rangeLabel: `${shortSha(base)}..${shortSha(head)}`,
  };
}

export async function resolveWorkingCase(repo: string, baseRevision?: string): Promise<CaseSpec> {
  const head = await resolveCommit(repo, 'HEAD');
  const base = baseRevision === undefined ? head : await resolveCommit(repo, baseRevision);
  if (!(await isAncestor(repo, base, head))) {
    throw new ValidationError(`base commit ${base} is not an ancestor of HEAD ${head}`);
  }
  return {
    mode: 'live',
    source: 'working',
    base,
    head: '',
    fixCommit: null,
    workingBase: head,
    rangeLabel: '',
  };
}

function parseAgentList(value: string): string[] {
  const names = value.split(',').map((name) => name.trim()).filter((name) => name.length > 0);
  if (names.length === 0) {
    throw new UsageError('--agents requires at least one comma-separated agent name');
  }
  const seen = new Set<string>();
  for (const name of names) {
    assertAgentName(name);
    if (seen.has(name)) {
      throw new UsageError(`--agents contains duplicate agent ${name}`);
    }
    seen.add(name);
  }
  return names;
}

function defaultSystem(agent: string): SystemConfig {
  return {
    agent,
    version: 'unknown',
    provider: null,
    model: null,
    revision: null,
    effort: null,
    command: null,
    extraArgs: [],
    fixture: null,
  };
}

function timeoutOverrides(options: RunCommandOptions, base: TimeoutConfig): TimeoutConfig {
  return {
    totalSeconds: options.timeout ?? base.totalSeconds,
    reviewSeconds: options.reviewTimeout ?? base.reviewSeconds,
    adjudicationSeconds: options.adjudicationTimeout ?? base.adjudicationSeconds,
    cleanupSeconds: options.cleanupTimeout ?? base.cleanupSeconds,
    killGraceSeconds: options.killGrace ?? base.killGraceSeconds,
  };
}

export async function resolveSystemsConfig(
  options: RunCommandOptions,
  cwd = process.cwd(),
): Promise<BenchmarkConfig> {
  const file = options.systems === undefined ? null : await loadSystemsFile(path.resolve(cwd, options.systems));
  const requested = options.agents === undefined ? null : parseAgentList(options.agents);
  const autoSelect = file === null && requested === null;
  let systems: SystemConfig[];
  if (file !== null) {
    systems = file.systems;
    if (requested !== null) {
      const byAgent = new Map(systems.map((system) => [system.agent, system]));
      systems = requested.map((agent) => {
        const found = byAgent.get(agent);
        if (found === undefined) {
          throw new ValidationError(`--agents selects ${agent}, but the systems file does not define it`);
        }
        return found;
      });
    }
  } else {
    const names = requested ?? [...REAL_AGENTS];
    systems = names.map(defaultSystem);
  }
  if (systems.some((system) => system.agent === 'fixture') && file === null) {
    throw new ValidationError('the fixture adapter requires a --systems file with a fixture path');
  }

  const resolved: SystemConfig[] = [];
  for (const system of systems) {
    if (system.fixture !== null) {
      system.fixture = path.resolve(cwd, system.fixture);
    }
    if (system.command !== null && (system.command.includes('/') || system.command.startsWith('.'))) {
      system.command = path.resolve(cwd, system.command);
    }
    const adapter = createAdapter(system);
    try {
      if (system.agent === 'fixture') {
        await assertFixtureFile(system);
      }
      const identity = await adapter.identity(system);
      resolved.push({ ...system, ...identity });
    } catch (error) {
      if (autoSelect) {
        continue;
      }
      throw error;
    }
  }
  if (resolved.length === 0) {
    throw new ValidationError(
      `no reviewer CLIs were found on PATH (checked ${REAL_AGENTS.join(', ')}); install one or pass --systems`,
    );
  }
  const withOverrides = applySystemOverrides(resolved, { model: options.model, effort: options.effort });
  const baseTimeouts = file?.timeouts ?? { ...DEFAULT_TIMEOUTS };
  return buildConfig(withOverrides, {
    budget: options.budget ?? file?.budget ?? DEFAULT_BUDGET,
    concurrency: options.concurrency ?? file?.concurrency ?? DEFAULT_CONCURRENCY,
    timeouts: timeoutOverrides(options, baseTimeouts),
    maxOutputBytes: file?.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES,
  });
}

export interface StartedRun {
  root: string;
  store: RunStore;
  manifest: RunManifest;
  caseManifest: CaseManifest;
  state: RunState;
}

export async function startRun(options: {
  root: string;
  repo: string;
  spec: CaseSpec;
  config: BenchmarkConfig;
  contextFile: string | null;
}): Promise<StartedRun> {
  const { root, repo, spec, config, contextFile } = options;
  await ensureBenchRoot(root);
  const repoName = repoNameFromRoot(repo);
  const runId = createRunId(repoName, spec.source === 'working' ? spec.base : spec.head);
  let head = spec.head;
  let working: WorkingSnapshot | null = null;
  let rangeLabel = spec.rangeLabel;
  if (spec.source === 'working') {
    const captured = await captureWorkingTree(repo, runId, spec.workingBase ?? spec.base);
    head = captured.snapshotCommit;
    working = {
      baseCommit: captured.baseCommit,
      snapshotCommit: captured.snapshotCommit,
      ref: captured.ref,
    };
    rangeLabel = `${shortSha(spec.base)}..${shortSha(head)} (working tree)`;
  }
  const store = new RunStore(root, runId);
  const prepared = await prepareCase({
    store,
    runId,
    mode: spec.mode,
    source: spec.source,
    rerunOf: null,
    working,
    objectiveCommit: spec.source === 'working' ? (working?.baseCommit ?? spec.base) : null,
    repoRoot: repo,
    base: spec.base,
    head,
    fixCommit: spec.fixCommit,
    contextFile,
    rangeLabel,
  });
  const promptPath = store.resolve('inputs', 'prompt.md');
  const prompt = renderPrompt({
    context: prepared.context,
    contextPath: store.resolve('inputs', 'context.md'),
    diffPath: store.resolve('inputs', 'diff.patch'),
    base: spec.base,
    head,
    budget: config.budget,
  });
  await writeImmutable(promptPath, prompt);
  const caseManifestPath = store.resolve('inputs', 'manifest.json');
  const manifest: RunManifest = {
    schemaVersion: RUN_SCHEMA,
    runId,
    mode: spec.mode,
    source: spec.source,
    rerunOf: null,
    working,
    createdAt: nowIso(),
    tool: { name: TOOL_NAME, version: TOOL_VERSION },
    repository: { root: repo, name: prepared.caseManifest.repository.name },
    commits: { base: spec.base, head, fixCommit: spec.fixCommit },
    config,
    paths: manifestPaths(),
    hashes: {
      contextSha256: prepared.caseManifest.context.sha256,
      contextJsonSha256: prepared.caseManifest.contextJson.sha256,
      diffSha256: prepared.caseManifest.diff.sha256,
      caseManifestSha256: await sha256File(caseManifestPath),
      promptSha256: await sha256File(promptPath),
    },
    promptPath: 'inputs/prompt.md',
  };
  await store.writeManifest(manifest);
  const state = createInitialState(runId, config.systems);
  await store.writeInitialState(state);
  await store.event('run.created', {
    mode: spec.mode,
    source: spec.source,
    rerunOf: null,
    base: spec.base,
    head,
    fixCommit: spec.fixCommit,
    systems: config.systems.map((system) => system.agent),
    budget: config.budget,
    concurrency: config.concurrency,
  });
  await store.event('case.prepared', {
    snapshot: store.resolve('snapshot'),
    source: spec.source,
    working,
    contextSha256: manifest.hashes.contextSha256,
    diffSha256: manifest.hashes.diffSha256,
    files: prepared.caseManifest.diff.files.length,
  });
  return { root, store, manifest, caseManifest: prepared.caseManifest, state };
}

export async function finalizeRun(
  store: RunStore,
  manifest: RunManifest,
  state: RunState,
  options: FinalizeOptions,
): Promise<FinalizeResult> {
  const caseManifest = await readCaseManifest(store);
  return finalizeArtifacts(store, manifest, caseManifest, state, options);
}

export function runSummary(
  store: RunStore,
  manifest: RunManifest,
  result: FinalizeResult,
  state: RunState,
): Record<string, unknown> {
  return {
    runId: manifest.runId,
    runDir: store.runDir,
    mode: manifest.mode,
    source: manifest.source,
    rerunOf: manifest.rerunOf,
    working: manifest.working,
    status: state.status,
    head: manifest.commits.head,
    base: manifest.commits.base,
    fixCommit: manifest.commits.fixCommit,
    systems: Object.fromEntries(
      manifest.config.systems.map((system) => {
        const metrics = result.evaluation.systems[system.agent];
        return [system.agent, { status: metrics?.status ?? 'not_run', findings: metrics?.findings.total ?? 0 }];
      }),
    ),
    dedupVersion: result.dedup.version,
    adjudicationVersion: result.adjudication?.version ?? null,
    gold: result.gold === null
      ? null
      : {
          version: result.gold.version,
          status: result.gold.status,
          labels: result.gold.labels.length,
          eligibleLabels: result.evaluation.case.goldEligibleLabels,
        },
    evaluationVersion: result.evaluation.version,
    usage: result.evaluation.usage,
    reports: result.reports,
    notes: result.evaluation.notes,
  };
}

export function printJson(value: unknown): void {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

import path from 'node:path';
import { NotFoundError, ValidationError } from '../util/errors.js';
import {
  ensureDir,
  listVersions,
  pathExists,
  readJsonFile,
  readLines,
  readTextFile,
  writeImmutable,
  writeJsonImmutable,
  atomicWriteJson,
  appendLine,
} from '../util/fsx.js';
import { parseStrictJson } from '../util/json.js';
import { assertSafeSegment, resolveWithin } from '../util/path-safety.js';
import { nowIso } from '../util/time.js';
import type {
  BenchEvent,
  RunManifest,
  RunState,
  RunStatus,
  StepStatus,
  SystemConfig,
} from './types.js';
import { EVENT_SCHEMA, STATE_SCHEMA } from './types.js';

const ALLOWED_STEP_TRANSITIONS: Record<StepStatus, StepStatus[]> = {
  pending: ['running', 'completed', 'failed'],
  running: ['completed', 'failed', 'timed_out'],
  completed: [],
  failed: ['running'],
  timed_out: ['running'],
};

export function assertStepTransition(from: StepStatus, to: StepStatus, label: string): void {
  if (!ALLOWED_STEP_TRANSITIONS[from].includes(to)) {
    throw new ValidationError(`${label}: illegal step transition ${from} -> ${to}`);
  }
}

export interface RootLayout {
  root: string;
  runsDir: string;
  goldDir: string;
}

export function rootLayout(root: string): RootLayout {
  return {
    root,
    runsDir: path.join(root, 'runs'),
    goldDir: path.join(root, 'gold'),
  };
}

export async function ensureBenchRoot(root: string): Promise<RootLayout> {
  const layout = rootLayout(root);
  await ensureDir(layout.runsDir);
  await ensureDir(layout.goldDir);
  const ignore = path.join(root, '.gitignore');
  if (!(await pathExists(ignore))) {
    await writeImmutable(ignore, '*\n');
  }
  return layout;
}

export function createInitialState(runId: string, systems: SystemConfig[]): RunState {
  const now = nowIso();
  const pending = (): StepStatus => 'pending';
  const reviews: RunState['steps']['reviews'] = {};
  for (const system of systems) {
    reviews[system.agent] = { agent: system.agent, status: pending(), attempts: [], updatedAt: now };
  }
  return {
    schemaVersion: STATE_SCHEMA,
    runId,
    status: 'created',
    createdAt: now,
    updatedAt: now,
    steps: {
      case: { status: 'pending', updatedAt: now, error: null },
      reviews,
      derivation: { status: 'pending', updatedAt: now, error: null },
      evaluation: { status: 'pending', updatedAt: now, error: null },
      report: { status: 'pending', updatedAt: now, error: null },
      cleanup: { status: 'pending', updatedAt: now, error: null },
    },
    eventSeq: 0,
  };
}

export function computeRunStatus(state: RunState): RunStatus {
  const statuses = Object.values(state.steps.reviews).map((review) => review.status);
  if (statuses.length === 0) {
    return state.steps.case.status === 'failed' ? 'failed' : 'created';
  }
  if (state.steps.case.status === 'failed') {
    return 'failed';
  }
  const completed = statuses.filter((status) => status === 'completed').length;
  const terminal = statuses.filter((status) => status === 'failed' || status === 'timed_out').length;
  if (completed === statuses.length) {
    return 'completed';
  }
  if (statuses.some((status) => status === 'running')) {
    return 'running';
  }
  if (completed > 0) {
    return terminal + completed === statuses.length ? 'partial' : 'running';
  }
  if (terminal === statuses.length) {
    return 'failed';
  }
  return state.steps.case.status === 'completed' ? 'running' : 'created';
}

export class RunStore {
  readonly runDir: string;
  readonly stateFile: string;
  readonly manifestFile: string;
  readonly eventsFile: string;
  readonly goldDir: string;
  private lockChain: Promise<void> = Promise.resolve();

  constructor(readonly root: string, readonly runId: string) {
    assertSafeSegment(runId, 'run id');
    const layout = rootLayout(root);
    this.runDir = resolveWithin(layout.runsDir, runId);
    this.stateFile = path.join(this.runDir, 'state.json');
    this.manifestFile = path.join(this.runDir, 'run.json');
    this.eventsFile = path.join(this.runDir, 'events.jsonl');
    this.goldDir = resolveWithin(layout.goldDir, runId);
  }

  async exists(): Promise<boolean> {
    return pathExists(this.manifestFile);
  }

  async createDir(): Promise<void> {
    for (const sub of ['inputs', 'reviews', 'logs', 'derived', 'reports', 'agent-output']) {
      await ensureDir(path.join(this.runDir, sub));
    }
    await ensureDir(this.goldDir);
  }

  private async withLock<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.lockChain;
    let release: () => void = () => undefined;
    this.lockChain = new Promise<void>((resolve) => {
      release = resolve;
    });
    await previous;
    try {
      return await operation();
    } finally {
      release();
    }
  }

  async readManifest(): Promise<RunManifest> {
    if (!(await pathExists(this.manifestFile))) {
      throw new NotFoundError(`run ${this.runId} has no manifest at ${this.manifestFile}`);
    }
    const manifest = await readJsonFile<RunManifest>(this.manifestFile, `run manifest ${this.runId}`);
    if (manifest.schemaVersion !== 'llm-review-bench/run/v1') {
      throw new ValidationError(`run manifest ${this.runId}: unsupported schema ${manifest.schemaVersion}`);
    }
    if (manifest.runId !== this.runId) {
      throw new ValidationError(`run manifest ${this.runId}: runId mismatch ${manifest.runId}`);
    }
    return manifest;
  }

  async writeManifest(manifest: RunManifest): Promise<void> {
    await writeJsonImmutable(this.manifestFile, manifest);
  }

  async readState(): Promise<RunState> {
    if (!(await pathExists(this.stateFile))) {
      throw new NotFoundError(`run ${this.runId} has no state at ${this.stateFile}`);
    }
    const state = await readJsonFile<RunState>(this.stateFile, `run state ${this.runId}`);
    if (state.schemaVersion !== 'llm-review-bench/state/v1') {
      throw new ValidationError(`run state ${this.runId}: unsupported schema ${state.schemaVersion}`);
    }
    if (state.runId !== this.runId) {
      throw new ValidationError(`run state ${this.runId}: runId mismatch ${state.runId}`);
    }
    return state;
  }

  async writeInitialState(state: RunState): Promise<void> {
    await writeJsonImmutable(this.stateFile, state);
  }

  async updateState(mutate: (state: RunState) => void): Promise<RunState> {
    return this.withLock(async () => {
      const state = await this.readState();
      mutate(state);
      state.updatedAt = nowIso();
      state.status = computeRunStatus(state);
      await atomicWriteJson(this.stateFile, state);
      return state;
    });
  }

  async event(
    type: string,
    data: Record<string, unknown> = {},
    options: { agent?: string | null; step?: string | null } = {},
  ): Promise<void> {
    await this.withLock(async () => {
      const lines = await readLines(this.eventsFile);
      const event: BenchEvent = {
        schemaVersion: EVENT_SCHEMA,
        seq: lines.length + 1,
        at: nowIso(),
        runId: this.runId,
        type,
        agent: options.agent ?? null,
        step: options.step ?? null,
        data,
      };
      await appendLine(this.eventsFile, JSON.stringify(event));
      const state = await this.readState().catch(() => null);
      if (state !== null && state.eventSeq !== event.seq) {
        state.eventSeq = event.seq;
        state.updatedAt = nowIso();
        await atomicWriteJson(this.stateFile, state);
      }
    });
  }

  async readEvents(): Promise<BenchEvent[]> {
    const lines = await readLines(this.eventsFile);
    return lines.map((line, index) => {
      const event = parseStrictJson(line, `event line ${index + 1}`) as BenchEvent;
      if (event.schemaVersion !== EVENT_SCHEMA) {
        throw new ValidationError(`event line ${index + 1}: unsupported schema ${String(event.schemaVersion)}`);
      }
      return event;
    });
  }

  resolve(...segments: string[]): string {
    return resolveWithin(this.runDir, ...segments);
  }

  async latestDerived(kind: 'dedup' | 'adjudication' | 'evaluation'): Promise<{ version: number; path: string } | null> {
    const versions = await listVersions(this.resolve('derived', kind));
    const latest = versions[versions.length - 1];
    if (latest === undefined) {
      return null;
    }
    await readJsonFile(latest.path, `${kind} v${latest.version}`);
    return latest;
  }

  async latestGold(): Promise<{ version: number; path: string } | null> {
    const versions = await listVersions(this.goldDir);
    const latest = versions[versions.length - 1];
    if (latest === undefined) {
      return null;
    }
    await readJsonFile(latest.path, `gold v${latest.version}`);
    return { version: latest.version, path: latest.path };
  }

  async nextDerivedVersion(kind: 'dedup' | 'adjudication' | 'evaluation'): Promise<number> {
    const versions = await listVersions(this.resolve('derived', kind));
    return (versions[versions.length - 1]?.version ?? 0) + 1;
  }

  async nextGoldVersion(): Promise<number> {
    const versions = await listVersions(this.goldDir);
    return (versions[versions.length - 1]?.version ?? 0) + 1;
  }

  async writeDerivedVersion(kind: 'dedup' | 'adjudication' | 'evaluation', version: number, value: unknown): Promise<string> {
    const file = this.resolve('derived', kind, `v${version}.json`);
    await ensureDir(path.dirname(file));
    await writeJsonImmutable(file, value);
    return file;
  }

  async writeGoldVersion(version: number, value: unknown): Promise<string> {
    await ensureDir(this.goldDir);
    const file = resolveWithin(this.goldDir, `v${version}.json`);
    await writeJsonImmutable(file, value);
    return file;
  }

  async readDerived(kind: 'dedup' | 'adjudication' | 'evaluation', version: number): Promise<unknown> {
    const file = resolveWithin(this.runDir, 'derived', kind, `v${version}.json`);
    return readJsonFile(file, `${kind} v${version}`);
  }

  async readGold(version: number): Promise<unknown> {
    const file = resolveWithin(this.goldDir, `v${version}.json`);
    return readJsonFile(file, `gold v${version}`);
  }

  async readCaseManifest(): Promise<unknown> {
    const manifest = await this.readManifest();
    const file = resolveWithin(this.runDir, manifest.paths.inputsDir, 'manifest.json');
    return readJsonFile(file, `case manifest ${this.runId}`);
  }

  async readContext(): Promise<string> {
    const manifest = await this.readManifest();
    const file = resolveWithin(this.runDir, manifest.paths.inputsDir, 'context.md');
    return readTextFile(file, `context for ${this.runId}`);
  }
}

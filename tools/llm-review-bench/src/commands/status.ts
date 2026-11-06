import fs from 'node:fs/promises';
import { openRun, printJson, resolveBenchRoot } from './shared.js';
import type { RunState } from '../protocol/types.js';

export interface StatusOptions {
  output?: string;
  json?: boolean;
  watch?: boolean;
  intervalSeconds?: number;
}

export interface RunStatusSnapshot {
  runId: string;
  status: RunState['status'];
  updatedAt: string;
  eventSeq: number;
  reviewers: Array<{
    agent: string;
    status: string;
    attempt: number | null;
    startedAt: string | null;
    updatedAt: string;
    lastActivityAt: string | null;
    stale: boolean;
    stdoutBytes: number | null;
    stderrBytes: number | null;
    lastEvent: string | null;
  }>;
}

async function fileSize(file: string): Promise<number | null> {
  try {
    return (await fs.stat(file)).size;
  } catch {
    return null;
  }
}

async function snapshot(root: string, runId: string): Promise<RunStatusSnapshot> {
  const { store, state } = await openRun(root, runId);
  const events = await store.readEvents();
  const now = Date.now();
  const reviewers = await Promise.all(Object.values(state.steps.reviews).map(async (review) => {
    const attempt = review.attempts[review.attempts.length - 1] ?? null;
    const progress = [...events].reverse().find((event) =>
      event.type === 'review.progress' && event.agent === review.agent && event.data['attempt'] === (attempt?.attempt ?? null));
    const lastActivityAt = progress?.at ?? attempt?.startedAt ?? null;
    const ageMs = lastActivityAt === null ? null : Math.max(0, now - Date.parse(lastActivityAt));
    const stale = review.status === 'running' && ageMs !== null && ageMs > 60_000;
    return {
      agent: review.agent,
      status: review.status,
      attempt: attempt?.attempt ?? null,
      startedAt: attempt?.startedAt ?? null,
      updatedAt: review.updatedAt,
      lastActivityAt,
      stale,
      stdoutBytes: attempt === null ? null : await fileSize(store.resolve(attempt.logBase + '.stdout.log')),
      stderrBytes: attempt === null ? null : await fileSize(store.resolve(attempt.logBase + '.stderr.log')),
      lastEvent: progress?.type ?? (attempt === null ? null : 'review.started'),
    };
  }));
  return { runId, status: state.status, updatedAt: state.updatedAt, eventSeq: state.eventSeq, reviewers };
}

function formatStatus(value: RunStatusSnapshot): string {
  const lines = [`run ${value.runId} ${value.status} updated=${value.updatedAt} events=${value.eventSeq}`];
  for (const reviewer of value.reviewers) {
    const stale = reviewer.stale ? ' stale' : '';
    const bytes = reviewer.stdoutBytes === null ? 'n/a' : `${reviewer.stdoutBytes}/${reviewer.stderrBytes ?? 0}B`;
    lines.push(`  ${reviewer.agent.padEnd(8)} ${reviewer.status.padEnd(10)} attempt=${reviewer.attempt ?? '-'} logs=${bytes}${stale}`);
  }
  return `${lines.join('\n')}\n`;
}

export async function statusCommand(runId: string, options: StatusOptions): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const intervalMs = Math.max(1, (options.intervalSeconds ?? 5) * 1000);
  do {
    const value = await snapshot(root, runId);
    if (options.json === true) {
      printJson(value);
    } else {
      process.stdout.write(formatStatus(value));
    }
    if (options.watch !== true || value.status === 'completed' || value.status === 'failed' || value.status === 'partial') {
      return 0;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  } while (true);
}

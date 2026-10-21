import path from 'node:path';
import { ValidationError, describeError } from '../util/errors.js';
import { ensureDir, pathExists, readJsonFile, readTextFile, writeJsonImmutable } from '../util/fsx.js';
import { sha256File } from '../util/fsx.js';
import { nowIso } from '../util/time.js';
import { statusPorcelain } from '../util/git.js';
import { materializeFindings } from '../protocol/findings.js';
import type {
  AttemptState,
  ReviewRecord,
  RunManifest,
  RunState,
  StepStatus,
  SystemConfig,
} from '../protocol/types.js';
import { REVIEW_SCHEMA } from '../protocol/types.js';
import type { RunStore } from '../protocol/store.js';
import { assertStepTransition } from '../protocol/store.js';
import { createAdapter } from '../adapters/registry.js';
import { assertFixtureFile } from '../adapters/fixture.js';
import type { AdapterContext, ExecutionView } from '../adapters/types.js';
import { runLoggedProcess } from '../adapters/process.js';

export interface ReviewPhaseOptions {
  store: RunStore;
  manifest: RunManifest;
  state: RunState;
}

function relativeToRun(store: RunStore, target: string): string {
  return path.relative(store.runDir, target).split(path.sep).join('/');
}

function attemptRecordPath(agent: string, attempt: number): string {
  return `reviews/${agent}/attempt-${attempt}.json`;
}

function attemptLogBase(agent: string, attempt: number): string {
  return `logs/${agent}-attempt-${attempt}`;
}

export function recoverInterruptedReviews(state: RunState): void {
  for (const review of Object.values(state.steps.reviews)) {
    if (review.status !== 'running') {
      continue;
    }
    const active = review.attempts[review.attempts.length - 1];
    if (active !== undefined && active.status === 'running') {
      active.status = 'failed';
      active.endedAt = nowIso();
      active.error = 'runner stopped before the attempt completed';
    }
    review.status = 'failed';
    review.updatedAt = nowIso();
  }
}

function statusFor(record: ReviewRecord): StepStatus {
  if (record.status === 'completed') {
    return 'completed';
  }
  return record.status === 'timed_out' ? 'timed_out' : 'failed';
}

async function writeReviewRecord(
  store: RunStore,
  record: ReviewRecord,
  attempt: number,
): Promise<AttemptState> {
  const relative = attemptRecordPath(record.agent, attempt);
  const file = store.resolve(relative);
  await ensureDir(path.dirname(file));
  await writeJsonImmutable(file, record);
  return {
    attempt,
    status: statusFor(record),
    startedAt: record.startedAt,
    endedAt: record.endedAt,
    durationMs: record.durationMs,
    exitCode: record.exitCode,
    timedOut: record.timedOut,
    error: record.error,
    recordPath: relative,
    logBase: attemptLogBase(record.agent, attempt),
  };
}

async function updateAttempt(
  store: RunStore,
  agent: string,
  attempt: AttemptState,
  status: StepStatus,
): Promise<void> {
  await store.updateState((state) => {
    const review = state.steps.reviews[agent];
    if (review === undefined) {
      throw new ValidationError(`state has no review step for agent ${agent}`);
    }
    review.status = status;
    review.updatedAt = nowIso();
    const index = review.attempts.findIndex((item) => item.attempt === attempt.attempt);
    if (index >= 0) {
      review.attempts[index] = attempt;
    } else {
      review.attempts.push(attempt);
    }
  });
}

interface OneReviewResult {
  agent: string;
  status: StepStatus;
  error: string | null;
}

async function runOneReview(
  store: RunStore,
  manifest: RunManifest,
  system: SystemConfig,
  phaseDeadline: number,
  attemptNumber: number,
): Promise<OneReviewResult> {
  const agent = system.agent;
  const startedAt = nowIso();
  const agentOutputDir = store.resolve('agent-output', agent, `attempt-${attemptNumber}`);
  const recordRelative = attemptRecordPath(agent, attemptNumber);
  const logBase = attemptLogBase(agent, attemptNumber);
  const stdoutPath = store.resolve(`${logBase}.stdout.log`);
  const stderrPath = store.resolve(`${logBase}.stderr.log`);
  await ensureDir(agentOutputDir);
  await ensureDir(path.dirname(store.resolve(recordRelative)));
  await ensureDir(path.dirname(stdoutPath));

  const pendingAttempt: AttemptState = {
    attempt: attemptNumber,
    status: 'running',
    startedAt,
    endedAt: null,
    durationMs: null,
    exitCode: null,
    timedOut: false,
    error: null,
    recordPath: recordRelative,
    logBase,
  };
  await updateAttempt(store, agent, pendingAttempt, 'running');
  await store.event('review.started', { attempt: attemptNumber }, { agent, step: 'review' });

  const promptPath = store.resolve(manifest.promptPath);
  let promptText = '';
  let plan = null;
  let context: AdapterContext | null = null;
  let worktreeModified = false;
  let processResult: Awaited<ReturnType<typeof runLoggedProcess>> | null = null;
  let record: ReviewRecord | null = null;

  try {
    promptText = await readTextFile(promptPath, `prompt for ${agent}`);
    const adapter = createAdapter(system);
    if (system.agent === 'fixture') {
      await assertFixtureFile(system);
    }
    context = {
      runId: manifest.runId,
      system,
      attempt: attemptNumber,
      snapshotDir: store.resolve(manifest.paths.snapshotDir),
      runDir: store.runDir,
      agentOutputDir,
      contextPath: store.resolve(manifest.paths.inputsDir, 'context.md'),
      diffPath: store.resolve(manifest.paths.inputsDir, 'diff.patch'),
      promptPath,
      promptText,
      base: manifest.commits.base,
      head: manifest.commits.head,
    };
    const dirtyBefore = await statusPorcelain(context.snapshotDir);
    if (dirtyBefore.trim().length > 0) {
      throw new ValidationError(
        `snapshot is dirty before ${agent} starts; run cleanup and recreate the run`,
      );
    }
    plan = adapter.buildPlan(context);
    const remaining = phaseDeadline - Date.now();
    if (remaining <= 0) {
      record = {
        schemaVersion: REVIEW_SCHEMA,
        runId: manifest.runId,
        agent,
        attempt: attemptNumber,
        status: 'timed_out',
        system: {
          agent,
          version: system.version,
          provider: system.provider,
          model: system.model,
          revision: system.revision,
          effort: system.effort,
        },
        startedAt,
        endedAt: nowIso(),
        durationMs: 0,
        exitCode: null,
        timedOut: true,
        command: { file: plan.command, args: plan.args, cwd: plan.cwd },
        summary: null,
        findings: null,
        rawFindings: null,
        usage: null,
        budgetDropped: null,
        parseWarnings: [],
        error: 'review phase deadline exceeded before the process started',
        stdoutPath: relativeToRun(store, stdoutPath),
        stderrPath: relativeToRun(store, stderrPath),
        messagePath: plan.resultFile === null ? null : relativeToRun(store, plan.resultFile),
        worktreeModified: false,
      };
    } else {
      const timeoutMs = Math.max(1, remaining);
      processResult = await runLoggedProcess({
        command: plan.command,
        args: plan.args,
        cwd: plan.cwd,
        stdoutPath,
        stderrPath,
        timeoutMs,
        graceMs: manifest.config.timeouts.killGraceSeconds * 1000,
        env: plan.env,
      });
      const dirtyAfter = await statusPorcelain(context.snapshotDir);
      worktreeModified = dirtyAfter.trim().length > 0;
      const baseRecord = {
        schemaVersion: REVIEW_SCHEMA as typeof REVIEW_SCHEMA,
        runId: manifest.runId,
        agent,
        attempt: attemptNumber,
        startedAt,
        endedAt: nowIso(),
        durationMs: processResult.durationMs,
        exitCode: processResult.exitCode,
        timedOut: processResult.timedOut,
        command: { file: plan.command, args: plan.args, cwd: plan.cwd },
        stdoutPath: relativeToRun(store, stdoutPath),
        stderrPath: relativeToRun(store, stderrPath),
        messagePath: plan.resultFile === null ? null : relativeToRun(store, plan.resultFile),
        worktreeModified,
        system: {
          agent,
          version: system.version,
          provider: system.provider,
          model: system.model,
          revision: system.revision,
          effort: system.effort,
        },
        budgetDropped: null,
        usage: null,
        parseWarnings: [] as string[],
      };
      if (processResult.timedOut) {
        record = {
          ...baseRecord,
          status: 'timed_out',
          summary: null,
          findings: null,
          rawFindings: null,
          error: `review exceeded the hard timeout of ${Math.round(timeoutMs / 1000)}s; process group was terminated`,
        };
      } else if (processResult.status === 'spawn_error') {
        record = {
          ...baseRecord,
          status: 'failed',
          summary: null,
          findings: null,
          rawFindings: null,
          error: `could not start reviewer: ${processResult.spawnError ?? 'unknown spawn error'}`,
        };
      } else if (processResult.exitCode !== 0) {
        record = {
          ...baseRecord,
          status: 'failed',
          summary: null,
          findings: null,
          rawFindings: null,
          error: `reviewer exited with code ${String(processResult.exitCode)}${processResult.signal === null ? '' : ` (signal ${processResult.signal})`}`,
        };
      } else if (worktreeModified) {
        record = {
          ...baseRecord,
          status: 'failed',
          summary: null,
          findings: null,
          rawFindings: null,
          error: 'reviewer modified the read-only snapshot; refusing the result',
        };
      } else {
        try {
          const parsed = await adapter.parse(context, plan, processResult as ExecutionView, manifest.config.maxOutputBytes);
          const materialized = materializeFindings(agent, attemptNumber, parsed.findings, manifest.config.budget);
          record = {
            ...baseRecord,
            status: 'completed',
            summary: parsed.summary,
            findings: materialized.findings,
            rawFindings: parsed.findings,
            usage: parsed.usage,
            budgetDropped: materialized.dropped,
            parseWarnings: parsed.warnings,
            error: null,
          };
        } catch (error) {
          record = {
            ...baseRecord,
            status: 'failed',
            summary: null,
            findings: null,
            rawFindings: null,
            error: `invalid review output: ${describeError(error)}`,
          };
        }
      }
    }
  } catch (error) {
    record = {
      schemaVersion: REVIEW_SCHEMA,
      runId: manifest.runId,
      agent,
      attempt: attemptNumber,
      status: 'failed',
      system: {
        agent,
        version: system.version,
        provider: system.provider,
        model: system.model,
        revision: system.revision,
        effort: system.effort,
      },
      startedAt,
      endedAt: nowIso(),
      durationMs: processResult?.durationMs ?? 0,
      exitCode: processResult?.exitCode ?? null,
      timedOut: processResult?.timedOut ?? false,
      command: plan === null ? null : { file: plan.command, args: plan.args, cwd: plan.cwd },
      summary: null,
      findings: null,
      rawFindings: null,
      usage: null,
      budgetDropped: null,
      parseWarnings: [],
      error: describeError(error),
      stdoutPath: relativeToRun(store, stdoutPath),
      stderrPath: relativeToRun(store, stderrPath),
      messagePath: plan?.resultFile == null ? null : relativeToRun(store, plan.resultFile),
      worktreeModified,
    };
  }

  if (record === null) {
    throw new Error(`internal error: no review record for ${agent} attempt ${attemptNumber}`);
  }
  const attempt = await writeReviewRecord(store, record, attemptNumber);
  const status = statusFor(record);
  await updateAttempt(store, agent, attempt, status);
  await store.event(
    status === 'completed' ? 'review.completed' : status === 'timed_out' ? 'review.timed_out' : 'review.failed',
    {
      attempt: attemptNumber,
      durationMs: record.durationMs,
      exitCode: record.exitCode,
      error: record.error,
      findings: record.findings?.length ?? null,
    },
    { agent, step: 'review' },
  );
  return { agent, status, error: record.error };
}

async function runWithConcurrency<T>(
  items: T[],
  limit: number,
  worker: (item: T) => Promise<void>,
): Promise<void> {
  let nextIndex = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (true) {
      const index = nextIndex;
      nextIndex += 1;
      if (index >= items.length) {
        return;
      }
      await worker(items[index] as T);
    }
  });
  await Promise.all(workers);
}

export function reviewPhaseDeadline(manifest: RunManifest, now = Date.now()): number {
  const totalMs = manifest.config.timeouts.totalSeconds * 1000;
  const reserveMs =
    (manifest.config.timeouts.adjudicationSeconds + manifest.config.timeouts.cleanupSeconds) * 1000;
  const reviewBudget = Math.min(manifest.config.timeouts.reviewSeconds * 1000, totalMs - reserveMs);
  return now + Math.max(1, reviewBudget);
}

export async function executeReviewPhase(options: ReviewPhaseOptions): Promise<RunState> {
  const { store, manifest } = options;
  let state = options.state;
  if (Object.values(state.steps.reviews).some((review) => review.status === 'running')) {
    state = await store.updateState((current) => recoverInterruptedReviews(current));
    await store.event('reviews.recovered', {});
  }
  const deadline = reviewPhaseDeadline(manifest);
  const systems = manifest.config.systems;
  const pending = systems.filter((system) => state.steps.reviews[system.agent]?.status !== 'completed');
  await store.updateState((current) => {
    if (current.steps.case.status === 'failed') {
      throw new ValidationError('case preparation failed; review phase cannot run');
    }
    if (current.steps.case.status !== 'completed') {
      assertStepTransition(current.steps.case.status, 'completed', 'case');
      current.steps.case.status = 'completed';
      current.steps.case.updatedAt = nowIso();
    }
  });
  await runWithConcurrency(pending, manifest.config.concurrency, async (system) => {
    const current = await store.readState();
    const review = current.steps.reviews[system.agent];
    if (review === undefined || review.status === 'completed') {
      return;
    }
    try {
      await runOneReview(store, manifest, system, deadline, review.attempts.length + 1);
    } catch (error) {
      await updateAttempt(
        store,
        system.agent,
        {
          attempt: review.attempts.length + 1,
          status: 'failed',
          startedAt: nowIso(),
          endedAt: nowIso(),
          durationMs: 0,
          exitCode: null,
          timedOut: false,
          error: describeError(error),
          recordPath: attemptRecordPath(system.agent, review.attempts.length + 1),
          logBase: attemptLogBase(system.agent, review.attempts.length + 1),
        },
        'failed',
      );
      await store.event('review.failed', { error: describeError(error) }, { agent: system.agent, step: 'review' });
    }
  });
  return store.readState();
}

export async function readCompletedReviews(
  store: RunStore,
  state: RunState,
): Promise<{ agent: string; attempt: number; record: ReviewRecord; sha256: string }[]> {
  const results: { agent: string; attempt: number; record: ReviewRecord; sha256: string }[] = [];
  for (const [agent, review] of Object.entries(state.steps.reviews)) {
    const completed = [...review.attempts].reverse().find((attempt) => attempt.status === 'completed');
    if (completed === undefined) {
      continue;
    }
    const file = store.resolve(completed.recordPath);
    if (!(await pathExists(file))) {
      continue;
    }
    const record = await readJsonFile<ReviewRecord>(file, `review record ${agent} attempt ${completed.attempt}`);
    if (record.schemaVersion !== REVIEW_SCHEMA || record.status !== 'completed' || record.findings === null) {
      continue;
    }
    if (record.runId !== store.runId || record.agent !== agent || record.attempt !== completed.attempt) {
      throw new ValidationError(
        `review record ${file} does not match run ${store.runId}, agent ${agent}, attempt ${completed.attempt}`,
      );
    }
    results.push({ agent, attempt: completed.attempt, record, sha256: await sha256File(file) });
  }
  return results;
}

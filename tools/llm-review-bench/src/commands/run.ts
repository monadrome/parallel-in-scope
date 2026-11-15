import { UsageError } from '../util/errors.js';
import { pathExists } from '../util/fsx.js';
import { resolveRootForRepo } from './shared.js';
import { resolveRepo } from '../util/git.js';
import type { CaseMode } from '../protocol/types.js';
import { openRun, printJson, resolveBenchRoot, resolveFixCase, resolveLiveCase, resolveSystemsConfig, resolveWorkingCase, runSummary, startRun } from './shared.js';
import { executeReviewPhase } from '../runner/reviews.js';
import { finalizeRun } from './shared.js';
import { latestEvaluation, loadFinalizeResult } from '../evaluation/pipeline.js';
import type { RunCommandOptions } from './shared.js';

export interface CaseRunOptions extends RunCommandOptions {
  json?: boolean;
}

export async function runCase(mode: CaseMode, options: CaseRunOptions): Promise<number> {
  const repo = await resolveRepo(options.repo ?? process.cwd());
  if (mode === 'historical') {
    if (options.fix === undefined) {
      throw new UsageError('run requires --fix <sha>; use review for a live commit');
    }
    if (options.commit !== undefined || options.base !== undefined || options.working === true) {
      throw new UsageError('run --fix does not accept --commit, --base, or --working; the fix parent defines the range');
    }
  } else if (options.fix !== undefined) {
    throw new UsageError('review is the live mode; use run --fix <sha> for a historical fix case');
  } else if (options.working === true && options.commit !== undefined) {
    throw new UsageError('review --working cannot be combined with --commit; the snapshot is created from HEAD and the working tree');
  }
  const spec = mode === 'historical'
    ? await resolveFixCase(repo, options.fix as string)
    : options.working === true
      ? await resolveWorkingCase(repo, options.base)
      : await resolveLiveCase(repo, options.commit, options.base);
  const config = await resolveSystemsConfig(options);
  const root = resolveRootForRepo(options.output, repo);
  const started = await startRun({
    root,
    repo,
    spec,
    config,
    contextFile: options.context === undefined ? null : options.context,
  });
  const reviewsState = await executeReviewPhase({
    store: started.store,
    manifest: started.manifest,
    state: started.state,
  });
  const result = await finalizeRun(started.store, started.manifest, reviewsState, {
    createGold: true,
    render: true,
  });
  const state = await started.store.readState();
  await started.store.event('run.completed', { status: state.status });
  const summary = runSummary(started.store, started.manifest, result, state);
  if (options.json === true) {
    printJson(summary);
  } else {
    process.stdout.write(`run ${started.manifest.runId} (${mode}) ${state.status}\n`);
    process.stdout.write(`root: ${started.store.runDir}\n`);
    for (const [agent, system] of Object.entries(result.evaluation.systems)) {
      process.stdout.write(`  ${agent.padEnd(8)} ${system.status.padEnd(10)} findings=${system.findings.total} duration=${system.durationMs === null ? 'n/a' : `${(system.durationMs / 1000).toFixed(1)}s`}\n`);
    }
    if (result.gold !== null) {
      process.stdout.write(`gold: v${result.gold.version} ${result.gold.status}${result.gold.status === 'candidate' ? ' (recall withheld until explicit confirmation)' : ''}\n`);
    }
    if (result.reports !== null) {
      process.stdout.write(`report: ${result.reports.html}\n`);
    }
    process.stdout.write(`next: llm-review-bench apply-judgment ${started.manifest.runId} --clusters <file> --verdicts <file> [--gold <file>] --output ${started.root}\n`);
  }
  return 0;
}

export async function resumeCommand(runId: string, options: { output?: string; json?: boolean }): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const { store, manifest, state: loaded } = await openRun(root, runId);
  const { verifyInputs } = await import('../case/prepare.js');
  await verifyInputs(store);
  let state = loaded;
  const pending = Object.values(state.steps.reviews).filter((review) => review.status !== 'completed');
  if (pending.length > 0) {
    const { ensureSnapshot } = await import('../case/prepare.js');
    await ensureSnapshot(store);
    state = await executeReviewPhase({ store, manifest, state });
  }
  const completedDerivation = state.steps.derivation.status === 'completed';
  const completedEvaluation = state.steps.evaluation.status === 'completed';
  const completedReport = state.steps.report.status === 'completed';
  const latest = await latestEvaluation(store, runId);
  const reportFilesPresent = latest !== null
    && await pathExists(store.resolve('reports', `report-v${latest.version}.html`))
    && await pathExists(store.resolve('reports', `metrics-v${latest.version}.csv`))
    && await pathExists(store.resolve('reports', `metrics-v${latest.version}.json`));
  const needsFinalize = pending.length > 0 || !completedDerivation || !completedEvaluation || !completedReport || !reportFilesPresent;
  const result = needsFinalize
    ? await finalizeRun(store, manifest, state, { createGold: true, render: true })
    : await loadFinalizeResult(store, manifest, state);
  const finalState = await store.readState();
  await store.event('run.resumed', {
    pending: pending.map((review) => review.agent),
    status: finalState.status,
  });
  const summary = runSummary(store, manifest, result, finalState);
  if (options.json === true) {
    printJson(summary);
  } else {
    process.stdout.write(`resumed ${runId}: ${finalState.status}\n`);
    for (const [agent, system] of Object.entries(result.evaluation.systems)) {
      process.stdout.write(`  ${agent.padEnd(8)} ${system.status.padEnd(10)} attempts=${system.attempts} findings=${system.findings.total}\n`);
    }
    if (result.reports !== null) {
      process.stdout.write(`report: ${result.reports.html}\n`);
    }
  }
  return 0;
}

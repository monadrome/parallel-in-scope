import { printJson, resolveBenchRoot, runSummary } from './shared.js';
import { startRerun } from '../case/rerun.js';
import { executeReviewPhase } from '../runner/reviews.js';
import { finalizeRun } from './shared.js';

export async function rerunCommand(runId: string, options: { output?: string; json?: boolean }): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const started = await startRerun(root, runId);
  const state = await executeReviewPhase({
    store: started.store,
    manifest: started.manifest,
    state: started.state,
  });
  const result = await finalizeRun(started.store, started.manifest, state, {
    createGold: true,
    render: true,
  });
  const finalState = await started.store.readState();
  await started.store.event('run.rerun.completed', {
    rerunOf: runId,
    status: finalState.status,
  });
  const summary = {
    ...runSummary(started.store, started.manifest, result, finalState),
    rerunOf: runId,
  };
  if (options.json === true) {
    printJson(summary);
  } else {
    process.stdout.write(`rerun ${runId} -> ${started.manifest.runId} (${finalState.status})\n`);
    process.stdout.write(`root: ${started.store.runDir}\n`);
    for (const [agent, system] of Object.entries(result.evaluation.systems)) {
      process.stdout.write(`  ${agent.padEnd(8)} ${system.status.padEnd(10)} findings=${system.findings.total}\n`);
    }
    if (result.gold !== null) {
      process.stdout.write(`gold: v${result.gold.version} ${result.gold.status} (fresh candidate/open-world; old verdicts are not reused)\n`);
    }
    if (result.reports !== null) {
      process.stdout.write(`report: ${result.reports.html}\n`);
    }
  }
  return 0;
}

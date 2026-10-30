import { openRun, printJson, resolveBenchRoot, runSummary } from './shared.js';
import { verifyInputs } from '../case/prepare.js';
import { finalizeRun } from './shared.js';

export async function rescoreCommand(runId: string, options: { output?: string; json?: boolean }): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const { store, manifest } = await openRun(root, runId);
  await verifyInputs(store);
  const result = await finalizeRun(store, manifest, await store.readState(), {
    createGold: false,
    render: false,
  });
  const state = await store.readState();
  await store.event('rescore.completed', { evaluationVersion: result.evaluation.version });
  const summary = runSummary(store, manifest, result, state);
  if (options.json === true) {
    printJson(summary);
  } else {
    process.stdout.write(`rescored ${runId}: evaluation v${result.evaluation.version}\n`);
    for (const [agent, system] of Object.entries(result.evaluation.systems)) {
      const rates = system.rates;
      process.stdout.write(
        `  ${agent.padEnd(8)} ${system.status.padEnd(10)} confirmed=${rates.confirmed === null ? 'n/a' : `${(rates.confirmed * 100).toFixed(1)}%`} rejected=${rates.rejected === null ? 'n/a' : `${(rates.rejected * 100).toFixed(1)}%`}\n`,
      );
    }
    process.stdout.write(`union known-fix recall: ${result.evaluation.union.recall.knownFix === null ? `n/a (${result.evaluation.union.recall.reason})` : `${(result.evaluation.union.recall.knownFix * 100).toFixed(1)}%`}\n`);
  }
  return 0;
}

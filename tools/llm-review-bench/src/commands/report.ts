import { NotFoundError } from '../util/errors.js';
import { openRun, printJson, resolveBenchRoot } from './shared.js';
import { verifyInputs, readCaseManifest } from '../case/prepare.js';
import { latestEvaluation, loadCompletedReviews, markStepCompleted, writeReports } from '../evaluation/pipeline.js';
import { nowIso } from '../util/time.js';

export async function reportCommand(runId: string, options: { output?: string; json?: boolean }): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const { store, manifest } = await openRun(root, runId);
  await verifyInputs(store);
  const evaluation = await latestEvaluation(store, runId);
  if (evaluation === null) {
    throw new NotFoundError(`run ${runId} has no evaluation yet; run rescore first`);
  }
  const state = await store.readState();
  const caseManifest = await readCaseManifest(store);
  const reviews = await loadCompletedReviews(store, manifest, state);
  const { paths } = await writeReports(store, manifest, caseManifest, evaluation, reviews);
  await markStepCompleted(store, 'report');
  const output = {
    runId,
    evaluationVersion: evaluation.version,
    reports: paths,
    generatedAt: nowIso(),
  };
  if (options.json === true) {
    printJson(output);
  } else {
    process.stdout.write(`report ${runId} evaluation v${evaluation.version}\n`);
    process.stdout.write(`  html: ${paths.html}\n  csv:  ${paths.csv}\n  json: ${paths.json}\n`);
  }
  return 0;
}

import { UsageError, ValidationError } from '../util/errors.js';
import { readTextFile } from '../util/fsx.js';
import { nowIso } from '../util/time.js';
import { collectFindingIds, buildImportedDedup, parseClustersInput } from '../evaluation/dedup.js';
import { buildAdjudication, parseVerdictsInput } from '../evaluation/adjudication.js';
import { finalizeGold, parseGoldInput } from '../evaluation/gold.js';
import type { JudgeIdentity } from '../protocol/types.js';
import { openRun, printJson, resolveBenchRoot, runSummary } from './shared.js';
import { readCaseManifest, verifyInputs } from '../case/prepare.js';
import { loadCompletedReviews, ensureDedup, loadDedup, loadGold, finalizeArtifacts } from '../evaluation/pipeline.js';

export interface JudgmentOptions {
  output?: string;
  clusters?: string;
  verdicts?: string;
  gold?: string;
  rescore?: boolean;
  judgeName?: string;
  judgeKind?: 'human' | 'agent';
  judgeModel?: string;
  confirmedBy?: string;
  json?: boolean;
}

export async function judgmentCommand(runId: string, options: JudgmentOptions): Promise<number> {
  if (options.clusters === undefined && options.verdicts === undefined && options.gold === undefined) {
    throw new UsageError('apply-judgment requires at least one of --clusters, --verdicts, or --gold');
  }
  const root = resolveBenchRoot(options.output);
  const { store, manifest, state } = await openRun(root, runId);
  await verifyInputs(store);
  const caseManifest = await readCaseManifest(store);
  const reviews = await loadCompletedReviews(store, manifest, state);
  const imported: { gold?: number; dedup?: number; adjudication?: number } = {};
  const existingGold = await loadGold(store, runId);
  const effectiveFix = existingGold?.case.fixCommit ?? manifest.commits.fixCommit ?? null;
  const effectiveCase = {
    ...caseManifest,
    commits: { ...caseManifest.commits, fixCommit: effectiveFix },
  };

  if (options.gold !== undefined) {
    if (effectiveFix === null) {
      throw new ValidationError('gold in a live run requires link --run <id> --fix <sha> first');
    }
    const version = await store.nextGoldVersion();
    const parsed = parseGoldInput(await readTextFile(options.gold, 'gold input'), options.gold, {
      caseManifest: effectiveCase,
      confirmedBy: options.confirmedBy ?? null,
    });
    const gold = finalizeGold(parsed, runId, version, nowIso(), effectiveCase);
    await store.writeGoldVersion(version, gold);
    await store.event('judgment.gold', {
      version,
      status: gold.status,
      labels: gold.labels.length,
      confirmedBy: gold.provenance.confirmedBy,
    });
    imported.gold = version;
  }

  if (options.clusters !== undefined) {
    const known = collectFindingIds(reviews);
    const parsed = parseClustersInput(await readTextFile(options.clusters, 'clusters input'), options.clusters, new Set(known.keys()));
    const version = await store.nextDerivedVersion('dedup');
    const dedup = buildImportedDedup(runId, version, nowIso(), parsed);
    await store.writeDerivedVersion('dedup', version, dedup);
    await store.event('judgment.clusters', { version, clusters: dedup.clusters.length, method: dedup.method });
    imported.dedup = version;
  }

  if (options.verdicts !== undefined) {
    let dedup = await loadDedup(store, runId);
    if (dedup === null) {
      dedup = await ensureDedup(store, runId, reviews);
    }
    const gold = await loadGold(store, runId);
    const goldIds = gold === null ? null : new Set(gold.labels.map((label) => label.goldId));
    const fallbackJudge: JudgeIdentity = {
      kind: options.judgeKind ?? 'human',
      name: options.judgeName ?? 'unspecified',
      model: options.judgeModel ?? null,
      note: null,
    };
    const parsed = parseVerdictsInput(await readTextFile(options.verdicts, 'verdicts input'), options.verdicts, {
      clusterIds: new Set(dedup.clusters.map((cluster) => cluster.clusterId)),
      goldIds,
      fallbackJudge,
    });
    const version = await store.nextDerivedVersion('adjudication');
    const adjudication = buildAdjudication(runId, version, dedup.version, nowIso(), parsed);
    await store.writeDerivedVersion('adjudication', version, adjudication);
    await store.event('judgment.verdicts', {
      version,
      dedupVersion: dedup.version,
      verdicts: adjudication.verdicts.length,
      judge: adjudication.judge,
    });
    imported.adjudication = version;
  }

  const shouldRescore = options.rescore !== false;
  if (!shouldRescore) {
    const output = { runId, imported, rescored: false };
    if (options.json === true) {
      printJson(output);
    } else {
      process.stdout.write(`imported judgment for ${runId}: ${JSON.stringify(imported)}\n`);
    }
    return 0;
  }
  const result = await finalizeArtifacts(store, manifest, effectiveCase, await store.readState(), {
    createGold: false,
    render: true,
  });
  const finalState = await store.readState();
  const summary = { ...runSummary(store, manifest, result, finalState), imported };
  if (options.json === true) {
    printJson(summary);
  } else {
    process.stdout.write(`imported judgment for ${runId}: ${JSON.stringify(imported)}\n`);
    process.stdout.write(`evaluation: v${result.evaluation.version}\n`);
    if (result.reports !== null) {
      process.stdout.write(`report: ${result.reports.html}\n`);
    }
  }
  return 0;
}

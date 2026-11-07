import { ValidationError } from '../util/errors.js';
import { pathExists, writeImmutable } from '../util/fsx.js';
import { nowIso } from '../util/time.js';
import { assertStepTransition } from '../protocol/store.js';
import type { RunStore } from '../protocol/store.js';
import type {
  AdjudicationRecord,
  CaseManifest,
  DedupRecord,
  EvaluationRecord,
  GoldRecord,
  RunManifest,
  RunState,
  StepStatus,
} from '../protocol/types.js';
import {
  ADJUDICATION_SCHEMA,
  DEDUP_SCHEMA,
  EVALUATION_SCHEMA,
  GOLD_SCHEMA,
} from '../protocol/types.js';
import type { CompletedReview } from './types.js';
import { buildIdentityDedup, collectFindingIds } from './dedup.js';
import { computeEvaluation } from './metrics.js';
import { candidateGoldFromFix, openWorldGold } from './gold.js';
import { renderReport, type RenderedReport } from './report.js';
import { readCompletedReviews } from '../runner/reviews.js';
import { commitInfo, showCommitDiff } from '../util/git.js';

export async function loadCompletedReviews(
  store: RunStore,
  manifest: RunManifest,
  state: RunState,
): Promise<CompletedReview[]> {
  const reviews = await readCompletedReviews(store, state);
  const order = new Map(manifest.config.systems.map((system, index) => [system.agent, index]));
  return reviews.sort((left, right) => (order.get(left.agent) ?? 999) - (order.get(right.agent) ?? 999));
}

function checkRecord<T extends { schemaVersion: string; runId: string }>(
  value: unknown,
  schema: string,
  runId: string,
  label: string,
): T {
  const record = value as Partial<T> & { schemaVersion?: string; runId?: string };
  if (record.schemaVersion !== schema) {
    throw new ValidationError(`${label}: unsupported schema ${String(record.schemaVersion)}`);
  }
  if (record.runId !== runId) {
    throw new ValidationError(`${label}: runId mismatch ${String(record.runId)}`);
  }
  return record as T;
}

export async function readDedupVersion(store: RunStore, runId: string, version: number): Promise<DedupRecord> {
  return checkRecord<DedupRecord>(
    await store.readDerived('dedup', version),
    DEDUP_SCHEMA,
    runId,
    `dedup v${version}`,
  );
}

export async function readAdjudicationVersion(
  store: RunStore,
  runId: string,
  version: number,
): Promise<AdjudicationRecord> {
  const checked = checkRecord<AdjudicationRecord>(
    await store.readDerived('adjudication', version),
    ADJUDICATION_SCHEMA,
    runId,
    `adjudication v${version}`,
  );
  return Number.isInteger(checked.dedupVersion) ? checked : { ...checked, dedupVersion: 0 };
}

export async function readGoldVersion(store: RunStore, runId: string, version: number): Promise<GoldRecord> {
  return checkRecord<GoldRecord>(
    await store.readGold(version),
    GOLD_SCHEMA,
    runId,
    `gold v${version}`,
  );
}

export async function loadDedup(store: RunStore, runId: string): Promise<DedupRecord | null> {
  const latest = await store.latestDerived('dedup');
  return latest === null ? null : readDedupVersion(store, runId, latest.version);
}

export async function loadAdjudication(store: RunStore, runId: string): Promise<AdjudicationRecord | null> {
  const latest = await store.latestDerived('adjudication');
  return latest === null ? null : readAdjudicationVersion(store, runId, latest.version);
}

export async function loadGold(store: RunStore, runId: string): Promise<GoldRecord | null> {
  const latest = await store.latestGold();
  return latest === null ? null : readGoldVersion(store, runId, latest.version);
}

function exactCoverage(dedup: DedupRecord, findingIds: Set<string>): boolean {
  const covered = dedup.clusters.flatMap((cluster) => cluster.findingIds);
  if (covered.length !== findingIds.size) {
    return false;
  }
  return covered.every((id) => findingIds.has(id));
}

export async function ensureDedup(
  store: RunStore,
  runId: string,
  reviews: CompletedReview[],
): Promise<DedupRecord> {
  const latest = await loadDedup(store, runId);
  const findingIds = new Set(collectFindingIds(reviews).keys());
  if (latest !== null) {
    if (exactCoverage(latest, findingIds)) {
      return latest;
    }
    const adjudication = await loadAdjudication(store, runId);
    if (adjudication !== null && adjudication.dedupVersion === latest.version) {
      return latest;
    }
  }
  const version = await store.nextDerivedVersion('dedup');
  const record = buildIdentityDedup(runId, version, nowIso(), reviews);
  await store.writeDerivedVersion('dedup', version, record);
  await store.event('dedup.written', { version, method: 'identity', clusters: record.clusters.length });
  return record;
}

export async function ensureGold(
  store: RunStore,
  manifest: RunManifest,
  caseManifest: CaseManifest,
  options: { create: boolean },
): Promise<GoldRecord | null> {
  const latest = await loadGold(store, manifest.runId);
  if (latest !== null) {
    return latest;
  }
  if (!options.create) {
    return null;
  }
  const version = await store.nextGoldVersion();
  let record: GoldRecord;
  if (manifest.commits.fixCommit !== null) {
    const [diff, info] = await Promise.all([
      showCommitDiff(manifest.repository.root, manifest.commits.fixCommit),
      commitInfo(manifest.repository.root, manifest.commits.fixCommit),
    ]);
    record = candidateGoldFromFix(
      manifest.runId,
      version,
      nowIso(),
      caseManifest,
      manifest.commits.fixCommit,
      info.subject,
      diff,
    );
  } else {
    record = openWorldGold(manifest.runId, version, nowIso(), caseManifest);
  }
  await store.writeGoldVersion(version, record);
  await store.event('gold.written', {
    version,
    status: record.status,
    labels: record.labels.length,
    source: record.provenance.source,
  });
  return record;
}

export async function writeEvaluation(
  store: RunStore,
  manifest: RunManifest,
  caseManifest: CaseManifest,
  state: RunState,
  reviews: CompletedReview[],
  dedup: DedupRecord,
  adjudication: AdjudicationRecord | null,
  gold: GoldRecord | null,
): Promise<EvaluationRecord> {
  const version = await store.nextDerivedVersion('evaluation');
  const evaluation = computeEvaluation({
    runId: manifest.runId,
    version,
    createdAt: nowIso(),
    manifest,
    caseManifest,
    state,
    reviews,
    dedup,
    adjudication,
    gold,
  });
  await store.writeDerivedVersion('evaluation', version, evaluation);
  await store.event('evaluation.written', {
    version,
    dedupVersion: dedup.version,
    adjudicationVersion: adjudication?.version ?? null,
    goldVersion: gold?.version ?? null,
  });
  return evaluation;
}

export async function latestEvaluation(store: RunStore, runId: string): Promise<EvaluationRecord | null> {
  const latest = await store.latestDerived('evaluation');
  if (latest === null) {
    return null;
  }
  return checkRecord<EvaluationRecord>(
    await store.readDerived('evaluation', latest.version),
    EVALUATION_SCHEMA,
    runId,
    `evaluation v${latest.version}`,
  );
}

async function writeReportFile(file: string, content: string): Promise<{ path: string; created: boolean }> {
  if (await pathExists(file)) {
    return { path: file, created: false };
  }
  await writeImmutable(file, content);
  return { path: file, created: true };
}

export interface ReportPaths {
  html: string;
  csv: string;
  json: string;
}

export async function writeReports(
  store: RunStore,
  manifest: RunManifest,
  caseManifest: CaseManifest,
  evaluation: EvaluationRecord,
  reviews: CompletedReview[],
): Promise<{ paths: ReportPaths; rendered: RenderedReport }> {
  const dedup = await readDedupVersion(store, manifest.runId, evaluation.inputs.dedupVersion);
  const adjudication = evaluation.inputs.adjudicationVersion === null
    ? null
    : await readAdjudicationVersion(store, manifest.runId, evaluation.inputs.adjudicationVersion);
  const gold = evaluation.inputs.goldVersion === null
    ? null
    : await readGoldVersion(store, manifest.runId, evaluation.inputs.goldVersion);
  const rendered = renderReport(evaluation, manifest, caseManifest, dedup, adjudication, reviews, gold);
  const base = `reports/report-v${evaluation.version}`;
  const [html, csv, json] = await Promise.all([
    writeReportFile(store.resolve(`${base}.html`), rendered.html),
    writeReportFile(store.resolve(`reports/metrics-v${evaluation.version}.csv`), rendered.csv),
    writeReportFile(store.resolve(`reports/metrics-v${evaluation.version}.json`), rendered.json),
  ]);
  if (html.created || csv.created || json.created) {
    await store.event('report.written', {
      evaluationVersion: evaluation.version,
      html: html.path,
      csv: csv.path,
      json: json.path,
    });
  }
  return { paths: { html: html.path, csv: csv.path, json: json.path }, rendered };
}

type NamedStep = 'case' | 'derivation' | 'evaluation' | 'report' | 'cleanup';

export async function setStepStatus(
  store: RunStore,
  step: NamedStep,
  status: StepStatus,
  error: string | null = null,
): Promise<void> {
  await store.updateState((state) => {
    const current = state.steps[step];
    if (current.status === status) {
      current.error = error;
      current.updatedAt = nowIso();
      return;
    }
    assertStepTransition(current.status, status, `step ${step}`);
    current.status = status;
    current.error = error;
    current.updatedAt = nowIso();
  });
}

async function beginStep(store: RunStore, step: NamedStep): Promise<void> {
  const state = await store.readState();
  if (state.steps[step].status === 'completed') {
    return;
  }
  await setStepStatus(store, step, 'running');
  await store.event('step.started', {}, { step });
}

export async function markStepCompleted(store: RunStore, step: NamedStep): Promise<void> {
  const state = await store.readState();
  if (state.steps[step].status === 'completed') {
    return;
  }
  if (state.steps[step].status !== 'running') {
    await setStepStatus(store, step, 'running');
  }
  await setStepStatus(store, step, 'completed');
  await store.event('step.completed', {}, { step });
}

export async function markStepFailed(store: RunStore, step: NamedStep, error: string): Promise<void> {
  const state = await store.readState();
  if (state.steps[step].status === 'completed') {
    return;
  }
  if (state.steps[step].status !== 'running') {
    await setStepStatus(store, step, 'running');
  }
  await setStepStatus(store, step, 'failed', error);
  await store.event('step.failed', { error }, { step });
}

async function finishStep(store: RunStore, step: NamedStep): Promise<void> {
  await markStepCompleted(store, step);
}

export async function loadFinalizeResult(
  store: RunStore,
  manifest: RunManifest,
  state: RunState,
): Promise<FinalizeResult> {
  const evaluation = await latestEvaluation(store, manifest.runId);
  if (evaluation === null) {
    throw new ValidationError(`run ${manifest.runId} has no evaluation; rerun resume after inspecting the run`);
  }
  const dedup = await readDedupVersion(store, manifest.runId, evaluation.inputs.dedupVersion);
  const adjudication = evaluation.inputs.adjudicationVersion === null
    ? null
    : await readAdjudicationVersion(store, manifest.runId, evaluation.inputs.adjudicationVersion);
  const gold = evaluation.inputs.goldVersion === null
    ? null
    : await readGoldVersion(store, manifest.runId, evaluation.inputs.goldVersion);
  const reviews = await loadCompletedReviews(store, manifest, state);
  return {
    reviews,
    dedup,
    adjudication,
    gold,
    evaluation,
    reports: {
      html: store.resolve('reports', `report-v${evaluation.version}.html`),
      csv: store.resolve('reports', `metrics-v${evaluation.version}.csv`),
      json: store.resolve('reports', `metrics-v${evaluation.version}.json`),
    },
  };
}

export interface FinalizeOptions {
  createGold: boolean;
  render: boolean;
}

export interface FinalizeResult {
  reviews: CompletedReview[];
  dedup: DedupRecord;
  adjudication: AdjudicationRecord | null;
  gold: GoldRecord | null;
  evaluation: EvaluationRecord;
  reports: ReportPaths | null;
}

export async function finalizeArtifacts(
  store: RunStore,
  manifest: RunManifest,
  caseManifest: CaseManifest,
  state: RunState,
  options: FinalizeOptions,
): Promise<FinalizeResult> {
  const step: NamedStep = 'derivation';
  try {
    await beginStep(store, step);
    const reviews = await loadCompletedReviews(store, manifest, state);
    const dedup = await ensureDedup(store, manifest.runId, reviews);
    const gold = await ensureGold(store, manifest, caseManifest, { create: options.createGold });
    await finishStep(store, step);
    await beginStep(store, 'evaluation');
    const adjudication = await loadAdjudication(store, manifest.runId);
    const evaluation = await writeEvaluation(store, manifest, caseManifest, state, reviews, dedup, adjudication, gold);
    await finishStep(store, 'evaluation');
    let reports: ReportPaths | null = null;
    if (options.render) {
      await beginStep(store, 'report');
      reports = (await writeReports(store, manifest, caseManifest, evaluation, reviews)).paths;
      await finishStep(store, 'report');
    }
    return { reviews, dedup, adjudication, gold, evaluation, reports };
  } catch (error) {
    const current = await store.readState();
    for (const name of ['derivation', 'evaluation', 'report'] as NamedStep[]) {
      if (current.steps[name].status === 'running') {
        await setStepStatus(store, name, 'failed', error instanceof Error ? error.message : String(error));
        await store.event('step.failed', { error: error instanceof Error ? error.message : String(error) }, { step: name });
      }
    }
    throw error;
  }
}

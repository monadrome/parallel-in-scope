import type {
  AdjudicationRecord,
  CaseManifest,
  CountSet,
  DedupRecord,
  EvaluationRecord,
  Finding,
  GoldRecord,
  RateSet,
  RecallSet,
  RunManifest,
  RunState,
  SystemMetrics,
} from '../protocol/types.js';
import { EVALUATION_SCHEMA } from '../protocol/types.js';
import type { CompletedReview } from './types.js';
import { findingReviewer } from './dedup.js';

export interface MetricsInput {
  runId: string;
  version: number;
  createdAt: string;
  manifest: RunManifest;
  caseManifest: CaseManifest;
  state: RunState;
  reviews: CompletedReview[];
  dedup: DedupRecord;
  adjudication: AdjudicationRecord | null;
  gold: GoldRecord | null;
}

function emptyCounts(): CountSet {
  return {
    total: 0,
    adjudicated: 0,
    confirmed: 0,
    rejected: 0,
    neutral: 0,
    uncertain: 0,
    pending: 0,
  };
}

function rateSet(counts: CountSet): RateSet {
  if (counts.adjudicated === 0) {
    return { confirmed: null, rejected: null, neutral: null, uncertain: null };
  }
  return {
    confirmed: counts.confirmed / counts.adjudicated,
    rejected: counts.rejected / counts.adjudicated,
    neutral: counts.neutral / counts.adjudicated,
    uncertain: counts.uncertain / counts.adjudicated,
  };
}

function addCount(counts: CountSet, verdict: 'confirmed' | 'rejected' | 'neutral' | 'uncertain'): void {
  counts.adjudicated += 1;
  counts[verdict] += 1;
}

function buildFindingIndex(reviews: CompletedReview[]): Map<string, { finding: Finding; agent: string }> {
  const index = new Map<string, { finding: Finding; agent: string }>();
  for (const { agent, record } of reviews) {
    for (const finding of record.findings ?? []) {
      index.set(finding.id, { finding, agent });
    }
  }
  return index;
}

function eligibleLabels(gold: GoldRecord | null): GoldRecord['labels'] {
  if (gold === null || gold.status !== 'confirmed') {
    return [];
  }
  return gold.labels.filter((label) => label.introducedByBuggyCommit === true);
}

function recallReason(
  gold: GoldRecord | null,
  kind: 'knownFix' | 'regression',
  eligibleCount: number,
  completed: boolean,
  hasAdjudication: boolean,
): string | null {
  if (gold === null || gold.status === 'open-world') {
    return 'open-world case: no gold labels';
  }
  if (gold.status === 'candidate') {
    return 'gold is a candidate; recall requires explicit confirmation of introduced-by relationships';
  }
  if (!completed) {
    return 'reviewer did not complete';
  }
  if (!hasAdjudication) {
    return 'no clusters have been adjudicated yet';
  }
  if (eligibleCount === 0) {
    return `gold has no eligible ${kind === 'knownFix' ? 'known-fix' : 'regression'} labels`;
  }
  return null;
}

function computeRecall(
  gold: GoldRecord | null,
  kind: 'knownFix' | 'regression',
  hits: Set<string>,
  completed: boolean,
  hasAdjudication: boolean,
): { value: number | null; reason: string | null; hits: number | null; eligible: number | null } {
  const eligible = eligibleLabels(gold).filter((label) =>
    kind === 'knownFix' ? label.kind === 'known-fix' : label.kind === 'regression');
  const reason = recallReason(gold, kind, eligible.length, completed, hasAdjudication);
  if (reason !== null) {
    return { value: null, reason, hits: null, eligible: null };
  }
  const matched = eligible.filter((label) => hits.has(label.goldId)).length;
  return { value: matched / eligible.length, reason: null, hits: matched, eligible: eligible.length };
}

function recallSet(knownFix: ReturnType<typeof computeRecall>, regression: ReturnType<typeof computeRecall>): RecallSet {
  const reason = knownFix.reason ?? regression.reason;
  return {
    knownFix: knownFix.value,
    knownFixHits: knownFix.hits,
    knownFixEligible: knownFix.eligible,
    regression: regression.value,
    regressionHits: regression.hits,
    regressionEligible: regression.eligible,
    reason,
  };
}

function averageRates(systems: Record<string, SystemMetrics>): RateSet | null {
  const values = Object.values(systems);
  if (values.length === 0) {
    return null;
  }
  const average = (key: keyof RateSet): number | null => {
    const defined = values.map((system) => system.rates[key]).filter((value): value is number => value !== null);
    if (defined.length === 0) {
      return null;
    }
    return defined.reduce((total, value) => total + value, 0) / defined.length;
  };
  return {
    confirmed: average('confirmed'),
    rejected: average('rejected'),
    neutral: average('neutral'),
    uncertain: average('uncertain'),
  };
}

export function computeEvaluation(input: MetricsInput): EvaluationRecord {
  const { manifest, caseManifest, state, reviews, dedup, gold } = input;
  const notes: string[] = [];
  const findingIndex = buildFindingIndex(reviews);
  const reviewByAgent = new Map(reviews.map((review) => [review.agent, review]));
  const clusterFindingIds = new Set(dedup.clusters.flatMap((cluster) => cluster.findingIds));
  const unclustered = [...findingIndex.keys()].filter((id) => !clusterFindingIds.has(id));
  if (unclustered.length > 0) {
    notes.push(`${unclustered.length} findings are not present in dedup v${dedup.version}; they are counted as pending`);
  }
  for (const cluster of dedup.clusters) {
    for (const findingId of cluster.findingIds) {
      if (!findingIndex.has(findingId)) {
        notes.push(`cluster ${cluster.clusterId} references missing finding ${findingId}`);
      }
    }
  }

  const staleVerdicts = (input.adjudication?.verdicts ?? []).filter(
    (verdict) => !dedup.clusters.some((cluster) => cluster.clusterId === verdict.clusterId),
  );
  if (staleVerdicts.length > 0) {
    notes.push(`${staleVerdicts.length} adjudication entries reference unknown clusters`);
  }
  let effectiveAdjudication: AdjudicationRecord | null = input.adjudication;
  if (effectiveAdjudication !== null && effectiveAdjudication.dedupVersion !== dedup.version) {
    notes.push(
      `adjudication v${effectiveAdjudication.version} targets dedup v${effectiveAdjudication.dedupVersion}, not v${dedup.version}; verdicts are treated as pending`,
    );
    effectiveAdjudication = null;
  }
  const verdictByCluster = new Map(
    (effectiveAdjudication?.verdicts ?? []).map((verdict) => [verdict.clusterId, verdict]),
  );

  const eligible = eligibleLabels(gold);
  const eligibleIds = new Set(eligible.map((label) => label.goldId));
  const hasAdjudication = dedup.clusters.some((cluster) => verdictByCluster.has(cluster.clusterId));
  const systems: Record<string, SystemMetrics> = {};
  for (const system of manifest.config.systems) {
    const agent = system.agent;
    const review = reviewByAgent.get(agent);
    const step = state.steps.reviews[agent];
    const counts = emptyCounts();
    const systemFindingIds = review === undefined ? [] : (review.record.findings ?? []).map((finding) => finding.id);
    counts.total = systemFindingIds.length;
    const systemHits = new Set<string>();
    for (const cluster of dedup.clusters) {
      const verdict = verdictByCluster.get(cluster.clusterId);
      if (verdict === undefined) {
        continue;
      }
      const findingsFromSystem = cluster.findingIds.filter((findingId) => findingReviewer(findingId) === agent);
      for (let index = 0; index < findingsFromSystem.length; index += 1) {
        addCount(counts, verdict.verdict);
      }
      if (verdict.verdict === 'confirmed' && findingsFromSystem.length > 0) {
        for (const labelId of verdict.goldLabelIds) {
          systemHits.add(labelId);
        }
      }
    }
    counts.pending = Math.max(0, counts.total - counts.adjudicated);
    const completed = review !== undefined;
    const status: SystemMetrics['status'] = review !== undefined
      ? 'completed'
      : step?.status === 'timed_out'
        ? 'timed_out'
        : step?.status === 'failed'
          ? 'failed'
          : 'not_run';
    const lastAttempt = step?.attempts[step.attempts.length - 1];
    const knownFix = computeRecall(gold, 'knownFix', systemHits, completed, hasAdjudication);
    const regression = computeRecall(gold, 'regression', systemHits, completed, hasAdjudication);
    systems[agent] = {
      identity: {
        agent,
        version: system.version,
        provider: system.provider,
        model: system.model,
        revision: system.revision,
        effort: system.effort,
      },
      status,
      durationMs: lastAttempt?.durationMs ?? null,
      attempts: step?.attempts.length ?? 0,
      timeout: step?.status === 'timed_out' || lastAttempt?.timedOut === true,
      findings: counts,
      rates: rateSet(counts),
      recall: recallSet(knownFix, regression),
      goldHits: gold?.status === 'confirmed' ? [...systemHits].filter((id) => eligibleIds.has(id)).sort() : [],
      usage: review?.record.usage ?? null,
      error: lastAttempt?.error ?? null,
    };
  }

  const unionFindings = emptyCounts();
  const unionClusters = emptyCounts();
  unionFindings.total = [...findingIndex.keys()].length;
  unionClusters.total = dedup.clusters.length;
  const unionHits = new Set<string>();
  for (const cluster of dedup.clusters) {
    const verdict = verdictByCluster.get(cluster.clusterId);
    if (verdict === undefined) {
      continue;
    }
    addCount(unionClusters, verdict.verdict);
    const present = cluster.findingIds.filter((findingId) => findingIndex.has(findingId));
    for (let index = 0; index < present.length; index += 1) {
      addCount(unionFindings, verdict.verdict);
    }
    if (verdict.verdict === 'confirmed' && present.length > 0) {
      for (const labelId of verdict.goldLabelIds) {
        unionHits.add(labelId);
      }
    }
  }
  unionClusters.pending = unionClusters.total - unionClusters.adjudicated;
  unionFindings.pending = unionFindings.total - unionFindings.adjudicated;
  const anyCompleted = reviews.length > 0;
  const unionKnownFix = computeRecall(gold, 'knownFix', unionHits, anyCompleted, unionClusters.adjudicated > 0);
  const unionRegression = computeRecall(gold, 'regression', unionHits, anyCompleted, unionClusters.adjudicated > 0);
  const usages = Object.values(systems)
    .map((system) => system.usage)
    .filter((usage): usage is NonNullable<SystemMetrics['usage']> => usage !== null);
  const tokenSum = (key: 'inputTokens' | 'outputTokens' | 'totalTokens'): number | null => {
    const values = usages.map((usage) => usage[key]).filter((value): value is number => value !== null);
    return values.length === 0 ? null : values.reduce((total, value) => total + value, 0);
  };
  const costs = new Map<string, { currency: string | null; cost: number }>();
  for (const usage of usages) {
    if (usage.cost === null) {
      continue;
    }
    const key = usage.currency ?? '';
    const existing = costs.get(key);
    if (existing === undefined) {
      costs.set(key, { currency: usage.currency, cost: usage.cost });
    } else {
      existing.cost += usage.cost;
    }
  }
  const costsByCurrency = [...costs.values()].sort((left, right) =>
    (left.currency ?? '').localeCompare(right.currency ?? ''));

  const microCounts = emptyCounts();
  for (const system of Object.values(systems)) {
    microCounts.total += system.findings.total;
    microCounts.adjudicated += system.findings.adjudicated;
    microCounts.confirmed += system.findings.confirmed;
    microCounts.rejected += system.findings.rejected;
    microCounts.neutral += system.findings.neutral;
    microCounts.uncertain += system.findings.uncertain;
    microCounts.pending += system.findings.pending;
  }
  const macro = averageRates(systems);

  if (gold === null || gold.status === 'open-world') {
    notes.push('open-world case: no gold labels and no recall values');
  } else if (gold.status === 'candidate') {
    notes.push(`gold v${gold.version} is a candidate; recall is withheld until explicit confirmation`);
  } else {
    notes.push(`gold v${gold.version} is confirmed with ${eligible.length} eligible introduced-by labels`);
  }
  if (dedup.method === 'identity') {
    notes.push('dedup uses identity clusters; semantic deduplication requires explicit clusters input');
  }
  if (unionClusters.pending > 0) {
    notes.push(`${unionClusters.pending} clusters are pending adjudication and excluded from rate denominators`);
  }
  if (effectiveAdjudication === null && input.adjudication === null) {
    notes.push('no adjudication version exists; all clusters are pending');
  }
  const incomplete = Object.values(systems).filter((system) => system.status !== 'completed').map((system) => system.identity.agent);
  if (incomplete.length > 0) {
    notes.push(`reviewers without a completed review: ${incomplete.join(', ')}; failures and timeouts are not treated as empty results`);
  }
  if (Object.values(systems).some((system) => system.identity.agent === 'fixture')) {
    notes.push('fixture systems are for tests and local end-to-end runs, not model comparisons');
  }

  return {
    schemaVersion: EVALUATION_SCHEMA,
    runId: input.runId,
    version: input.version,
    createdAt: input.createdAt,
    inputs: {
      dedupVersion: dedup.version,
      adjudicationVersion: input.adjudication?.version ?? null,
      goldVersion: gold?.version ?? null,
      reviews: input.reviews.map((review) => ({
        agent: review.agent,
        attempt: review.attempt,
        sha256: review.sha256,
      })),
    },
    case: {
      mode: caseManifest.mode,
      source: caseManifest.source,
      rerunOf: caseManifest.rerunOf,
      openWorld: gold === null || gold.status === 'open-world',
      base: caseManifest.commits.base,
      head: caseManifest.commits.head,
      fixCommit: gold?.case.fixCommit ?? caseManifest.commits.fixCommit,
      goldStatus: gold?.status ?? 'open-world',
      goldEligibleLabels: eligible.length,
    },
    usage: {
      inputTokens: tokenSum('inputTokens'),
      outputTokens: tokenSum('outputTokens'),
      totalTokens: tokenSum('totalTokens'),
      systemsWithUsage: usages.length,
      systemsTotal: Object.keys(systems).length,
      costsByCurrency,
    },
    systems,
    union: {
      clusters: unionClusters,
      findings: unionFindings,
      rates: rateSet(unionClusters),
      findingRates: rateSet(unionFindings),
      recall: recallSet(unionKnownFix, unionRegression),
      goldHits: gold?.status === 'confirmed' ? [...unionHits].filter((id) => eligibleIds.has(id)).sort() : [],
    },
    aggregate: {
      micro: rateSet(microCounts),
      macro,
      systemsAdjudicated: Object.values(systems).filter((system) => system.findings.adjudicated > 0).length,
    },
    notes,
  };
}

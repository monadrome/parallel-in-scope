import type {
  AdjudicationRecord,
  CaseManifest,
  DedupRecord,
  EvaluationRecord,
  Finding,
  GoldRecord,
  RateSet,
  RecallSet,
  SystemMetrics,
} from '../protocol/types.js';
import type { CompletedReview } from './types.js';

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function csvCell(value: string | number | boolean | null | undefined): string {
  let text = value === null || value === undefined ? '' : String(value);
  if (/^[\s]*[=+\-@]/.test(text)) {
    text = `'${text}`;
  }
  if (/[",\r\n]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

function percent(value: number | null): string {
  return value === null ? 'n/a' : `${(value * 100).toFixed(1)}%`;
}

function recallCell(recall: RecallSet, kind: 'knownFix' | 'regression'): string {
  const value = recall[kind];
  if (value === null) {
    return `n/a (${recall.reason ?? 'not applicable'})`;
  }
  return percent(value);
}

function htmlCell(value: string | number | boolean | null | undefined): string {
  if (value === null || value === undefined) {
    return '<td>n/a</td>';
  }
  return `<td>${escapeHtml(String(value))}</td>`;
}

function rateCells(rates: RateSet): string {
  return [rates.confirmed, rates.rejected, rates.neutral, rates.uncertain]
    .map((rate) => `<td>${escapeHtml(percent(rate))}</td>`)
    .join('');
}

function countCells(counts: { confirmed: number; rejected: number; neutral: number; uncertain: number; pending: number }): string {
  return [counts.confirmed, counts.rejected, counts.neutral, counts.uncertain, counts.pending]
    .map((count) => `<td>${count}</td>`)
    .join('');
}

function usageCells(usage: SystemMetrics['usage']): string {
  const tokens = usage === null
    ? 'n/a'
    : [usage.inputTokens, usage.outputTokens, usage.totalTokens].map((value) => value === null ? '?' : String(value)).join(' / ');
  const cost = usage === null || usage.cost === null
    ? 'n/a'
    : `${usage.cost}${usage.currency === null ? '' : ` ${usage.currency}`}`;
  return `<td>${escapeHtml(tokens)}</td><td>${escapeHtml(cost)}</td>`;
}

function systemRow(system: SystemMetrics): string {
  const identity = system.identity;
  return `<tr>
    <td><strong>${escapeHtml(identity.agent)}</strong></td>
    <td>${escapeHtml(identity.version)}</td>
    <td>${escapeHtml([identity.provider, identity.model, identity.effort].filter((part) => part !== null).join(' / ') || 'n/a')}</td>
    <td>${escapeHtml(system.status)}${system.timeout ? ' (timeout)' : ''}</td>
    ${htmlCell(system.durationMs === null ? null : `${(system.durationMs / 1000).toFixed(1)}s`)}
    <td>${system.attempts}</td>
    ${usageCells(system.usage)}
    <td>${system.findings.total}</td>
    <td>${system.findings.adjudicated}</td>
    ${countCells(system.findings)}
    ${rateCells(system.rates)}
    <td>${escapeHtml(recallCell(system.recall, 'knownFix'))}</td>
    <td>${escapeHtml(recallCell(system.recall, 'regression'))}</td>
    <td>${escapeHtml(system.goldHits.join(', ') || 'none')}</td>
    <td>${escapeHtml(system.error ?? '')}</td>
  </tr>`;
}

function clusterRows(
  evaluation: EvaluationRecord,
  dedup: DedupRecord,
  adjudication: AdjudicationRecord | null,
  reviews: CompletedReview[],
): string {
  const findings = new Map<string, Finding>();
  for (const review of reviews) {
    for (const finding of review.record.findings ?? []) {
      findings.set(finding.id, finding);
    }
  }
  const verdicts = new Map(
    (adjudication !== null && adjudication.dedupVersion === evaluation.inputs.dedupVersion
      ? adjudication.verdicts
      : []).map((verdict) => [verdict.clusterId, verdict]),
  );
  return dedup.clusters.map((cluster) => {
    const verdict = verdicts.get(cluster.clusterId);
    const items = cluster.findingIds.map((id) => findings.get(id)).filter((item): item is Finding => item !== undefined);
    const titles = items.map((item) => `${item.reviewer}: ${item.title}`).join(' | ');
    return `<tr>
      <td>${escapeHtml(cluster.clusterId)}</td>
      <td>${escapeHtml(verdict?.verdict ?? 'pending')}</td>
      <td>${escapeHtml(cluster.findingIds.join(', '))}</td>
      <td>${escapeHtml(verdict?.goldLabelIds.join(', ') ?? '')}</td>
      <td>${escapeHtml(titles)}</td>
    </tr>`;
  }).join('\n');
}

export function usageSummary(evaluation: EvaluationRecord): string {
  const usage = evaluation.usage;
  const tokens = usage.inputTokens === null && usage.outputTokens === null && usage.totalTokens === null
    ? 'tokens n/a'
    : `tokens ${usage.inputTokens ?? '?'} in / ${usage.outputTokens ?? '?'} out / ${usage.totalTokens ?? '?'} total`;
  const costs = usage.costsByCurrency.length === 0
    ? 'cost n/a'
    : usage.costsByCurrency.map((entry) => `${entry.cost} ${entry.currency ?? 'unknown'}`).join(' + ');
  return `${tokens}; ${costs} (${usage.systemsWithUsage}/${usage.systemsTotal} systems reported usage)`;
}

function renderHtml(
  evaluation: EvaluationRecord,
  manifest: { runId: string; repository: { root: string }; config: { timeouts: { totalSeconds: number; reviewSeconds: number; adjudicationSeconds: number; cleanupSeconds: number } } },
  caseManifest: CaseManifest,
  dedup: DedupRecord,
  adjudication: AdjudicationRecord | null,
  reviews: CompletedReview[],
  gold: GoldRecord | null,
): string {
  const systems = Object.values(evaluation.systems);
  const timeout = manifest.config.timeouts;
  const goldSummary = gold === null
    ? 'open-world (no gold record)'
    : `${gold.status} (${evaluation.case.goldEligibleLabels} eligible introduced-by labels)`;
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>llm-review-bench ${escapeHtml(evaluation.runId)} v${evaluation.version}</title>
<style>
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;margin:2rem auto;max-width:1400px;padding:0 1rem;color:#1f2328;background:#fff}
h1,h2{font-weight:600} h1{font-size:1.5rem} h2{font-size:1.15rem;margin-top:2rem}
table{border-collapse:collapse;width:100%;margin:.75rem 0 1.5rem;font-size:.82rem}
th,td{border:1px solid #d0d7de;padding:.35rem .45rem;text-align:left;vertical-align:top}
th{background:#f6f8fa} tr:nth-child(even) td{background:#fbfcfd}
code{background:#f6f8fa;padding:.1rem .25rem;border-radius:3px;font-size:.85em}
.note{color:#57606a;font-size:.9rem}.warn{color:#9a6700}.bad{color:#cf222e}
.limits{background:#fff8c5;border:1px solid #d4a72c;padding:.75rem;border-radius:6px}
</style>
</head>
<body>
<h1>llm-review-bench report</h1>
<p class="note">Run <code>${escapeHtml(evaluation.runId)}</code>, evaluation v${evaluation.version}, generated ${escapeHtml(evaluation.createdAt)}. This page is static and contains no executable content.</p>

<h2>Case layer</h2>
<table>
<tr><th>Repository</th>${htmlCell(manifest.repository.root)}</tr>
<tr><th>Mode</th>${htmlCell(caseManifest.mode)}</tr>
<tr><th>Review range</th>${htmlCell(`${caseManifest.range.from}..${caseManifest.range.to}`)}</tr>
<tr><th>Base commit</th>${htmlCell(caseManifest.commits.base)}</tr>
<tr><th>Head commit</th>${htmlCell(caseManifest.commits.head)}</tr>
<tr><th>Fix commit</th>${htmlCell(gold?.case.fixCommit ?? caseManifest.commits.fixCommit ?? 'none (live case)')}</tr>
<tr><th>Gold</th>${htmlCell(goldSummary)}</tr>
<tr><th>Gold version</th>${htmlCell(evaluation.inputs.goldVersion === null ? 'none' : `v${evaluation.inputs.goldVersion}`)}</tr>
<tr><th>Dedup / adjudication</th>${htmlCell(`dedup v${evaluation.inputs.dedupVersion}; adjudication ${evaluation.inputs.adjudicationVersion === null ? 'none' : `v${evaluation.inputs.adjudicationVersion}`}`)}</tr>
<tr><th>Context sha256</th>${htmlCell(caseManifest.context.sha256)}</tr>
<tr><th>Diff sha256</th>${htmlCell(caseManifest.diff.sha256)}</tr>
<tr><th>Timeout budget</th>${htmlCell(`${timeout.totalSeconds}s total / ${timeout.reviewSeconds}s review / ${timeout.adjudicationSeconds}s adjudication / ${timeout.cleanupSeconds}s cleanup`)}</tr>
<tr><th>Source / rerun</th>${htmlCell(`${evaluation.case.source}${evaluation.case.rerunOf === null ? '' : ` (rerun of ${evaluation.case.rerunOf})`}`)}</tr>
<tr><th>Usage</th><td>${escapeHtml(usageSummary(evaluation))}</td></tr>
</table>

<h2>System layer</h2>
<table>
<thead><tr>
<th>System</th><th>Version</th><th>Provider / model / effort</th><th>Status</th><th>Duration</th><th>Attempts</th><th>Tokens in / out / total</th><th>Cost</th><th>Findings</th><th>Adjudicated</th>
<th>Confirmed</th><th>Rejected</th><th>Neutral</th><th>Uncertain</th><th>Pending</th>
<th>Confirmed rate</th><th>Rejected rate</th><th>Neutral rate</th><th>Uncertain rate</th>
<th>Known-fix recall</th><th>Regression recall</th><th>Gold hits</th><th>Error</th>
</tr></thead>
<tbody>
${systems.map(systemRow).join('\n')}
</tbody>
</table>
<p class="note">Rates use each reviewer's adjudicated findings as the denominator. A zero denominator yields n/a. Timeout and failed runs are not counted as empty reviews.</p>

<h2>Union and aggregate</h2>
<table>
<thead><tr><th>Layer</th><th>Total</th><th>Adjudicated</th><th>Confirmed</th><th>Rejected</th><th>Neutral</th><th>Uncertain</th><th>Pending</th><th>Confirmed rate</th><th>Rejected rate</th><th>Neutral rate</th><th>Uncertain rate</th><th>Known-fix recall</th><th>Regression recall</th></tr></thead>
<tbody>
<tr><td>Union (clusters)</td><td>${evaluation.union.clusters.total}</td><td>${evaluation.union.clusters.adjudicated}</td>${countCells(evaluation.union.clusters)}${rateCells(evaluation.union.rates)}<td>${escapeHtml(recallCell(evaluation.union.recall, 'knownFix'))}</td><td>${escapeHtml(recallCell(evaluation.union.recall, 'regression'))}</td></tr>
<tr><td>Union (findings)</td><td>${evaluation.union.findings.total}</td><td>${evaluation.union.findings.adjudicated}</td>${countCells(evaluation.union.findings)}${rateCells(evaluation.union.findingRates)}<td>n/a</td><td>n/a</td></tr>
<tr><td>Micro (pooled findings)</td><td>${systemTotal(evaluation, 'total')}</td><td>${systemTotal(evaluation, 'adjudicated')}</td>${countCells({
    confirmed: systemTotal(evaluation, 'confirmed'), rejected: systemTotal(evaluation, 'rejected'),
    neutral: systemTotal(evaluation, 'neutral'), uncertain: systemTotal(evaluation, 'uncertain'),
    pending: systemTotal(evaluation, 'pending'),
  })}${rateCells(evaluation.aggregate.micro)}<td>n/a</td><td>n/a</td></tr>
<tr><td>Macro (mean of reviewer rates)</td><td>n/a</td><td>${evaluation.aggregate.systemsAdjudicated} systems</td><td>n/a</td><td>n/a</td><td>n/a</td><td>n/a</td><td>n/a</td>${rateCells(evaluation.aggregate.macro ?? { confirmed: null, rejected: null, neutral: null, uncertain: null })}<td>n/a</td><td>n/a</td></tr>
</tbody>
</table>

<h2>Clusters</h2>
<table>
<thead><tr><th>Cluster</th><th>Verdict</th><th>Findings</th><th>Gold labels</th><th>Titles</th></tr></thead>
<tbody>
${clusterRows(evaluation, dedup, adjudication, reviews)}
</tbody>
</table>

<h2>Notes and limits</h2>
<ul>${evaluation.notes.map((note) => `<li>${escapeHtml(note)}</li>`).join('')}</ul>
<div class="limits">
<strong>Interpretation limits.</strong> A single case does not rank models. Agent, CLI version, model, and effort form one measured system. Identity clustering is not semantic deduplication; verdicts and gold labels are explicit human or agent inputs. Recall is withheld for open-world and candidate gold. No subjective quality score is computed.
</div>
</body>
</html>
`;
}

function systemTotal(evaluation: EvaluationRecord, key: 'total' | 'adjudicated' | 'confirmed' | 'rejected' | 'neutral' | 'uncertain' | 'pending'): number {
  return Object.values(evaluation.systems).reduce((total, system) => total + system.findings[key], 0);
}

export function renderCsv(
  evaluation: EvaluationRecord,
  caseManifest: CaseManifest,
  dedup: DedupRecord,
  adjudication: AdjudicationRecord | null,
  reviews: CompletedReview[],
  gold: GoldRecord | null,
): string {
  const columns = [
    'run_id', 'evaluation_version', 'layer', 'scope', 'key', 'mode', 'gold_status', 'open_world', 'system', 'agent', 'version',
    'provider', 'model', 'effort', 'revision', 'status', 'duration_seconds', 'attempts',
    'input_tokens', 'output_tokens', 'total_tokens', 'cost', 'currency', 'findings',
    'adjudicated', 'confirmed', 'rejected', 'neutral', 'uncertain', 'pending',
    'confirmed_rate', 'rejected_rate', 'neutral_rate', 'uncertain_rate',
    'known_fix_recall', 'regression_recall', 'timeout', 'error', 'recall_note', 'cluster_id', 'verdict',
    'cluster_findings', 'gold_labels', 'summary',
  ] as const;
  type Column = (typeof columns)[number];
  type Row = Partial<Record<Column, string | number | boolean | null>>;
  const common: Row = {
    run_id: evaluation.runId,
    evaluation_version: evaluation.version,
    mode: caseManifest.mode,
    gold_status: gold?.status ?? 'open-world',
    open_world: gold === null || gold.status === 'open-world',
  };
  const rows: Row[] = [];
  for (const system of Object.values(evaluation.systems)) {
    rows.push({
      ...common,
      layer: 'system',
      scope: system.identity.agent,
      key: system.identity.agent,
      system: system.identity.agent,
      agent: system.identity.agent,
      version: system.identity.version,
      provider: system.identity.provider,
      model: system.identity.model,
      effort: system.identity.effort,
      revision: system.identity.revision,
      status: system.status,
      duration_seconds: system.durationMs === null ? null : system.durationMs / 1000,
      attempts: system.attempts,
      input_tokens: system.usage?.inputTokens ?? null,
      output_tokens: system.usage?.outputTokens ?? null,
      total_tokens: system.usage?.totalTokens ?? null,
      cost: system.usage?.cost ?? null,
      currency: system.usage?.currency ?? null,
      findings: system.findings.total,
      adjudicated: system.findings.adjudicated,
      confirmed: system.findings.confirmed,
      rejected: system.findings.rejected,
      neutral: system.findings.neutral,
      uncertain: system.findings.uncertain,
      pending: system.findings.pending,
      confirmed_rate: system.rates.confirmed,
      rejected_rate: system.rates.rejected,
      neutral_rate: system.rates.neutral,
      uncertain_rate: system.rates.uncertain,
      known_fix_recall: system.recall.knownFix,
      regression_recall: system.recall.regression,
      timeout: system.timeout,
      error: system.error,
      recall_note: system.recall.reason,
    });
  }
  rows.push({
    ...common,
    layer: 'union',
    scope: 'clusters',
    key: 'union',
    status: null,
    findings: evaluation.union.clusters.total,
    adjudicated: evaluation.union.clusters.adjudicated,
    confirmed: evaluation.union.clusters.confirmed,
    rejected: evaluation.union.clusters.rejected,
    neutral: evaluation.union.clusters.neutral,
    uncertain: evaluation.union.clusters.uncertain,
    pending: evaluation.union.clusters.pending,
    confirmed_rate: evaluation.union.rates.confirmed,
    rejected_rate: evaluation.union.rates.rejected,
    neutral_rate: evaluation.union.rates.neutral,
    uncertain_rate: evaluation.union.rates.uncertain,
    known_fix_recall: evaluation.union.recall.knownFix,
    regression_recall: evaluation.union.recall.regression,
    timeout: false,
    recall_note: evaluation.union.recall.reason,
  });
  rows.push({
    ...common,
    layer: 'aggregate',
    scope: 'micro',
    key: 'micro',
    confirmed_rate: evaluation.aggregate.micro.confirmed,
    rejected_rate: evaluation.aggregate.micro.rejected,
    neutral_rate: evaluation.aggregate.micro.neutral,
    uncertain_rate: evaluation.aggregate.micro.uncertain,
    timeout: false,
  });
  if (evaluation.aggregate.macro !== null) {
    rows.push({
      ...common,
      layer: 'aggregate',
      scope: 'macro',
      key: 'macro',
      confirmed_rate: evaluation.aggregate.macro.confirmed,
      rejected_rate: evaluation.aggregate.macro.rejected,
      neutral_rate: evaluation.aggregate.macro.neutral,
      uncertain_rate: evaluation.aggregate.macro.uncertain,
      timeout: false,
    });
  }
  const findingMap = new Map<string, Finding>();
  for (const review of reviews) {
    for (const finding of review.record.findings ?? []) {
      findingMap.set(finding.id, finding);
    }
  }
  const verdicts = new Map(
    (adjudication !== null && adjudication.dedupVersion === evaluation.inputs.dedupVersion
      ? adjudication.verdicts
      : []).map((verdict) => [verdict.clusterId, verdict]),
  );
  for (const cluster of dedup.clusters) {
    const verdict = verdicts.get(cluster.clusterId);
    const titles = cluster.findingIds
      .map((id) => findingMap.get(id))
      .filter((finding): finding is Finding => finding !== undefined)
      .map((finding) => `${finding.reviewer}: ${finding.title}`)
      .join(' | ');
    rows.push({
      ...common,
      layer: 'cluster',
      scope: cluster.clusterId,
      key: cluster.clusterId,
      status: verdict?.verdict ?? 'pending',
      findings: null,
      cluster_id: cluster.clusterId,
      verdict: verdict?.verdict ?? 'pending',
      cluster_findings: cluster.findingIds.length,
      gold_labels: verdict?.goldLabelIds.join(';') ?? null,
      summary: titles,
      timeout: false,
    });
  }
  const lines = [columns.map((column) => csvCell(column)).join(',')];
  for (const row of rows) {
    lines.push(columns.map((column) => csvCell(row[column] ?? null)).join(','));
  }
  return `${lines.join('\n')}\n`;
}

export interface RenderedReport {
  html: string;
  csv: string;
  json: string;
}

export function renderReport(
  evaluation: EvaluationRecord,
  manifest: { runId: string; repository: { root: string }; config: { timeouts: { totalSeconds: number; reviewSeconds: number; adjudicationSeconds: number; cleanupSeconds: number } } },
  caseManifest: CaseManifest,
  dedup: DedupRecord,
  adjudication: AdjudicationRecord | null,
  reviews: CompletedReview[],
  gold: GoldRecord | null,
): RenderedReport {
  return {
    html: renderHtml(evaluation, manifest, caseManifest, dedup, adjudication, reviews, gold),
    csv: renderCsv(evaluation, caseManifest, dedup, adjudication, reviews, gold),
    json: `${JSON.stringify(evaluation, null, 2)}\n`,
  };
}

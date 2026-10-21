import fs from 'node:fs/promises';
import path from 'node:path';
import { RunStore } from '../protocol/store.js';
import type { CountSet, EvaluationRecord, RateSet, SystemMetrics, Usage } from '../protocol/types.js';
import { latestEvaluation } from './pipeline.js';

export const LEADERBOARD_SCHEMA = 'llm-review-bench/leaderboard/v1';

interface UsageAggregate {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  casesWithUsage: number;
  costsByCurrency: { currency: string | null; cost: number }[];
}

export interface LeaderboardGroup {
  agent: string;
  model: string | null;
  revision: string | null;
  effort: string | null;
  providers: string[];
  versions: string[];
  cases: number;
  completedCases: number;
  timeoutCases: number;
  failedCases: number;
  notRunCases: number;
  openWorldCases: number;
  confirmedGoldCases: number;
  rerunCases: number;
  findings: CountSet;
  micro: RateSet;
  macro: RateSet | null;
  knownFixRecall: { rate: number | null; hits: number | null; eligible: number | null; cases: number; reason: string | null };
  regressionRecall: { rate: number | null; hits: number | null; eligible: number | null; cases: number; reason: string | null };
  timeoutRate: number | null;
  durationMs: { total: number; mean: number | null; count: number };
  usage: UsageAggregate;
}

export interface LeaderboardResult {
  schemaVersion: typeof LEADERBOARD_SCHEMA;
  generatedAt: string;
  root: string;
  includeFixture: boolean;
  runsScanned: number;
  evaluationsUsed: number;
  skipped: { runId: string; reason: string }[];
  groups: LeaderboardGroup[];
  notes: string[];
}

interface MutableGroup {
  agent: string;
  model: string | null;
  revision: string | null;
  effort: string | null;
  providers: Set<string>;
  versions: Set<string>;
  cases: number;
  completedCases: number;
  timeoutCases: number;
  failedCases: number;
  notRunCases: number;
  openWorldCases: number;
  confirmedGoldCases: number;
  rerunCases: number;
  findings: CountSet;
  perCaseRates: Record<keyof RateSet, number[]>;
  knownFixHits: number;
  knownFixEligible: number;
  knownFixCases: number;
  regressionHits: number;
  regressionEligible: number;
  regressionCases: number;
  durationTotal: number;
  durationCount: number;
  usageInput: number | null;
  usageOutput: number | null;
  usageTotal: number | null;
  usageCases: number;
  costs: Map<string, { currency: string | null; cost: number }>;
}

function emptyCounts(): CountSet {
  return { total: 0, adjudicated: 0, confirmed: 0, rejected: 0, neutral: 0, uncertain: 0, pending: 0 };
}

function addCounts(target: CountSet, source: CountSet): void {
  target.total += source.total;
  target.adjudicated += source.adjudicated;
  target.confirmed += source.confirmed;
  target.rejected += source.rejected;
  target.neutral += source.neutral;
  target.uncertain += source.uncertain;
  target.pending += source.pending;
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

function average(values: number[]): number | null {
  return values.length === 0 ? null : values.reduce((total, value) => total + value, 0) / values.length;
}

function addUsage(group: MutableGroup, usage: Usage | null): void {
  if (usage === null) {
    return;
  }
  group.usageCases += 1;
  if (usage.inputTokens !== null) {
    group.usageInput = (group.usageInput ?? 0) + usage.inputTokens;
  }
  if (usage.outputTokens !== null) {
    group.usageOutput = (group.usageOutput ?? 0) + usage.outputTokens;
  }
  if (usage.totalTokens !== null) {
    group.usageTotal = (group.usageTotal ?? 0) + usage.totalTokens;
  }
  if (usage.cost !== null) {
    const key = usage.currency ?? '';
    const existing = group.costs.get(key);
    if (existing === undefined) {
      group.costs.set(key, { currency: usage.currency, cost: usage.cost });
    } else {
      existing.cost += usage.cost;
    }
  }
}

function newGroup(system: SystemMetrics): MutableGroup {
  return {
    agent: system.identity.agent,
    model: system.identity.model,
    revision: system.identity.revision,
    effort: system.identity.effort,
    providers: new Set(system.identity.provider === null ? [] : [system.identity.provider]),
    versions: new Set([system.identity.version]),
    cases: 0,
    completedCases: 0,
    timeoutCases: 0,
    failedCases: 0,
    notRunCases: 0,
    openWorldCases: 0,
    confirmedGoldCases: 0,
    rerunCases: 0,
    findings: emptyCounts(),
    perCaseRates: { confirmed: [], rejected: [], neutral: [], uncertain: [] },
    knownFixHits: 0,
    knownFixEligible: 0,
    knownFixCases: 0,
    regressionHits: 0,
    regressionEligible: 0,
    regressionCases: 0,
    durationTotal: 0,
    durationCount: 0,
    usageInput: null,
    usageOutput: null,
    usageTotal: null,
    usageCases: 0,
    costs: new Map(),
  };
}

function groupKey(system: SystemMetrics): string {
  return JSON.stringify([
    system.identity.agent,
    system.identity.model,
    system.identity.revision,
    system.identity.effort,
  ]);
}

function recallAggregate(
  hits: number,
  eligible: number,
  cases: number,
  label: string,
): { rate: number | null; hits: number | null; eligible: number | null; cases: number; reason: string | null } {
  if (cases === 0) {
    return { rate: null, hits: null, eligible: null, cases: 0, reason: `no case had an applicable ${label} recall denominator` };
  }
  if (eligible === 0) {
    return { rate: null, hits, eligible: 0, cases, reason: `no eligible ${label} labels in aggregated cases` };
  }
  return { rate: hits / eligible, hits, eligible, cases, reason: null };
}

export async function buildLeaderboard(root: string, includeFixture = false): Promise<LeaderboardResult> {
  const runsDir = path.join(root, 'runs');
  let names: string[] = [];
  try {
    names = (await fs.readdir(runsDir)).filter((name) => !name.startsWith('.')).sort();
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
      throw error;
    }
  }
  const groups = new Map<string, MutableGroup>();
  const skipped: { runId: string; reason: string }[] = [];
  let runsScanned = 0;
  let evaluationsUsed = 0;
  for (const runId of names) {
    runsScanned += 1;
    const store = new RunStore(root, runId);
    if (!(await store.exists())) {
      skipped.push({ runId, reason: 'missing run manifest' });
      continue;
    }
    let evaluation: EvaluationRecord | null;
    try {
      evaluation = await latestEvaluation(store, runId);
    } catch (error) {
      skipped.push({ runId, reason: error instanceof Error ? error.message : String(error) });
      continue;
    }
    if (evaluation === null) {
      skipped.push({ runId, reason: 'no evaluation version' });
      continue;
    }
    evaluationsUsed += 1;
    for (const system of Object.values(evaluation.systems)) {
      if (!includeFixture && system.identity.agent === 'fixture') {
        continue;
      }
      const key = groupKey(system);
      let group = groups.get(key);
      if (group === undefined) {
        group = newGroup(system);
        groups.set(key, group);
      } else {
        if (system.identity.provider !== null) {
          group.providers.add(system.identity.provider);
        }
        group.versions.add(system.identity.version);
      }
      group.cases += 1;
      if (system.status === 'completed') {
        group.completedCases += 1;
        if (system.durationMs !== null) {
          group.durationTotal += system.durationMs;
          group.durationCount += 1;
        }
      } else if (system.status === 'timed_out') {
        group.timeoutCases += 1;
      } else if (system.status === 'failed') {
        group.failedCases += 1;
      } else {
        group.notRunCases += 1;
      }
      if (evaluation.case.openWorld) {
        group.openWorldCases += 1;
      }
      if (evaluation.case.goldStatus === 'confirmed') {
        group.confirmedGoldCases += 1;
      }
      if (evaluation.case.rerunOf !== null) {
        group.rerunCases += 1;
      }
      addCounts(group.findings, system.findings);
      for (const key of ['confirmed', 'rejected', 'neutral', 'uncertain'] as const) {
        const rate = system.rates[key];
        if (rate !== null) {
          group.perCaseRates[key].push(rate);
        }
      }
      if (system.recall.knownFixEligible !== null && system.recall.knownFixHits !== null) {
        group.knownFixHits += system.recall.knownFixHits;
        group.knownFixEligible += system.recall.knownFixEligible;
        group.knownFixCases += 1;
      }
      if (system.recall.regressionEligible !== null && system.recall.regressionHits !== null) {
        group.regressionHits += system.recall.regressionHits;
        group.regressionEligible += system.recall.regressionEligible;
        group.regressionCases += 1;
      }
      addUsage(group, system.usage);
    }
  }

  const rows: LeaderboardGroup[] = [...groups.values()].map((group) => {
    const micro = rateSet(group.findings);
    const macro = {
      confirmed: average(group.perCaseRates.confirmed),
      rejected: average(group.perCaseRates.rejected),
      neutral: average(group.perCaseRates.neutral),
      uncertain: average(group.perCaseRates.uncertain),
    };
    return {
      agent: group.agent,
      model: group.model,
      revision: group.revision,
      effort: group.effort,
      providers: [...group.providers].sort(),
      versions: [...group.versions].sort(),
      cases: group.cases,
      completedCases: group.completedCases,
      timeoutCases: group.timeoutCases,
      failedCases: group.failedCases,
      notRunCases: group.notRunCases,
      openWorldCases: group.openWorldCases,
      confirmedGoldCases: group.confirmedGoldCases,
      rerunCases: group.rerunCases,
      findings: group.findings,
      micro,
      macro: group.perCaseRates.confirmed.length === 0 && group.perCaseRates.rejected.length === 0 &&
        group.perCaseRates.neutral.length === 0 && group.perCaseRates.uncertain.length === 0 ? null : macro,
      knownFixRecall: recallAggregate(group.knownFixHits, group.knownFixEligible, group.knownFixCases, 'known-fix'),
      regressionRecall: recallAggregate(group.regressionHits, group.regressionEligible, group.regressionCases, 'regression'),
      timeoutRate: group.cases === 0 ? null : group.timeoutCases / group.cases,
      durationMs: {
        total: group.durationTotal,
        mean: group.durationCount === 0 ? null : group.durationTotal / group.durationCount,
        count: group.durationCount,
      },
      usage: {
        inputTokens: group.usageInput,
        outputTokens: group.usageOutput,
        totalTokens: group.usageTotal,
        casesWithUsage: group.usageCases,
        costsByCurrency: [...group.costs.values()].sort((left, right) =>
          (left.currency ?? '').localeCompare(right.currency ?? '')),
      },
    };
  });
  rows.sort((left, right) => {
    const leftRate = left.micro.confirmed ?? -1;
    const rightRate = right.micro.confirmed ?? -1;
    if (leftRate !== rightRate) {
      return rightRate - leftRate;
    }
    if (left.cases !== right.cases) {
      return right.cases - left.cases;
    }
    return JSON.stringify([left.agent, left.model, left.revision, left.effort])
      .localeCompare(JSON.stringify([right.agent, right.model, right.revision, right.effort]));
  });

  const notes = [
    'Groups are measured systems keyed by agent+model+revision+effort. They are not pure model rankings.',
    'Only the latest evaluation version per run is used; reruns count as separate cases.',
    'Recall aggregates only cases where the evaluation had a confirmed, adjudicated denominator.',
    'Costs in different currencies are never added together.',
    includeFixture ? 'Fixture systems are included; do not treat their rows as model evidence.' : 'Fixture systems are excluded by default.',
  ];
  return {
    schemaVersion: LEADERBOARD_SCHEMA,
    generatedAt: new Date().toISOString(),
    root,
    includeFixture,
    runsScanned,
    evaluationsUsed,
    skipped,
    groups: rows,
    notes,
  };
}

function percent(value: number | null): string {
  return value === null ? 'n/a' : `${(value * 100).toFixed(1)}%`;
}

function macroText(macro: RateSet | null): string {
  if (macro === null) {
    return 'n/a';
  }
  return [macro.confirmed, macro.rejected, macro.neutral, macro.uncertain]
    .map((value) => value === null ? 'n/a' : `${(value * 100).toFixed(1)}%`)
    .join('/');
}

function recallText(recall: LeaderboardGroup['knownFixRecall']): string {
  return recall.rate === null ? `n/a (${recall.reason ?? 'not applicable'})` : `${percent(recall.rate)} (${recall.hits}/${recall.eligible})`;
}

function usageText(usage: LeaderboardGroup['usage']): string {
  const tokens = [usage.inputTokens, usage.outputTokens, usage.totalTokens].every((value) => value === null)
    ? 'tokens n/a'
    : `tokens ${usage.inputTokens ?? '?'}/${usage.outputTokens ?? '?'}/${usage.totalTokens ?? '?'}`;
  const costs = usage.costsByCurrency.length === 0
    ? 'cost n/a'
    : usage.costsByCurrency.map((entry) => `${entry.cost} ${entry.currency ?? 'unknown'}`).join(' + ');
  return `${tokens}; ${costs}`;
}

export function formatLeaderboard(result: LeaderboardResult): string {
  const lines = [
    `root: ${result.root}`,
    `runs scanned: ${result.runsScanned}; evaluations used: ${result.evaluationsUsed}; skipped: ${result.skipped.length}`,
    '',
    'system (agent/model/revision/effort)                         cases  compl  timeout  confirmed  rejected  neutral  uncertain  macro C/R/N/U            known-fix recall  regression recall  avg duration  usage',
  ];
  if (result.groups.length === 0) {
    lines.push('  (no rows; use --include-fixture for fixture-only runs)');
  }
  for (const group of result.groups) {
    const label = [group.agent, group.model ?? 'n/a', group.revision ?? 'n/a', group.effort ?? 'n/a'].join('/');
    lines.push(
      `  ${label.padEnd(58)} ${String(group.cases).padStart(5)}  ${String(group.completedCases).padStart(5)}  ${String(group.timeoutCases).padStart(7)}  ${percent(group.micro.confirmed).padStart(9)}  ${percent(group.micro.rejected).padStart(8)}  ${percent(group.micro.neutral).padStart(7)}  ${percent(group.micro.uncertain).padStart(9)}  ${macroText(group.macro).padStart(23)}  ${recallText(group.knownFixRecall).padStart(17)}  ${recallText(group.regressionRecall).padStart(17)}  ${(group.durationMs.mean === null ? 'n/a' : `${(group.durationMs.mean / 1000).toFixed(1)}s`).padStart(12)}  ${usageText(group.usage)}`,
    );
  }
  lines.push('', ...result.notes.map((note) => `note: ${note}`));
  return `${lines.join('\n')}\n`;
}

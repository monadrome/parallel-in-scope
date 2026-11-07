import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';
import {
  addFix,
  createBaseRepo,
  finding,
  makeTempDir,
  runCliJson,
  writeFixture,
  writeJson,
} from './helpers.js';

test('leaderboard aggregates cases, rates, recall, timeout, duration, and usage by measured system', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-leader-');
  const fix = addFix(base.repo);
  const bench = path.join(tmp, 'bench');
  const fixture = await writeFixture(tmp, 'fixture.json', {});
  const systemsA = path.join(tmp, 'systems-a.json');
  await writeJson(systemsA, {
    systems: [{ agent: 'fixture', fixture, provider: 'fixture', model: 'm-A', revision: 'r1', effort: 'high' }],
    concurrency: 1,
  });
  const systemsB = path.join(tmp, 'systems-b.json');
  await writeJson(systemsB, {
    systems: [{ agent: 'fixture', fixture, provider: 'fixture', model: 'm-B', revision: 'r1', effort: 'high' }],
    concurrency: 1,
  });

  await writeFixture(tmp, 'fixture.json', {
    findings: [finding()],
    usage: { input_tokens: 11, output_tokens: 7, total_tokens: 18, cost: 0.02, currency: 'USD' },
  });
  const first = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systemsA, '--json',
  ]);
  const goldFile = path.join(tmp, 'lb-gold.json');
  const clustersFile = path.join(tmp, 'lb-clusters.json');
  const verdictsFile = path.join(tmp, 'lb-verdicts.json');
  await writeJson(goldFile, {
    status: 'confirmed', confirmedBy: 'tester',
    labels: [{
      goldId: 'G1', kind: 'known-fix', file: 'src/math.ts', lineStart: 1,
      rootCause: 'subtraction bug', introducedByBuggyCommit: true,
    }],
  });
  await writeJson(clustersFile, { clusters: [{ clusterId: 'C01', findingIds: ['fixture:0'] }] });
  await writeJson(verdictsFile, {
    verdicts: [{ clusterId: 'C01', verdict: 'confirmed', goldLabelIds: ['G1'], confidence: 1 }],
  });
  await runCliJson<Record<string, any>>([
    'apply-judgment', first.json.runId as string, '--output', bench,
    '--gold', goldFile, '--clusters', clustersFile, '--verdicts', verdictsFile, '--json',
  ]);

  await writeFixture(tmp, 'fixture.json', {
    findings: [],
    usage: { input_tokens: 3, output_tokens: 2, total_tokens: 5, cost: 0.01, currency: 'EUR' },
  });
  await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systemsA, '--json',
  ]);

  await writeFixture(tmp, 'fixture.json', { delayMs: 60_000 });
  await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systemsA,
    '--timeout', '30', '--review-timeout', '2', '--adjudication-timeout', '3', '--cleanup-timeout', '3',
    '--kill-grace', '1', '--json',
  ], { timeoutMs: 30_000 });

  await writeFixture(tmp, 'fixture.json', {
    findings: [],
    usage: { input_tokens: 4, output_tokens: 1, total_tokens: 5, cost: 0.04, currency: 'EUR' },
  });
  await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systemsB, '--json',
  ]);

  const excluded = await runCliJson<Record<string, any>>([
    'leaderboard', '--output', bench, '--json',
  ]);
  assert.equal(excluded.json.groups.length, 0);
  assert.equal(excluded.json.evaluationsUsed, 4);

  const result = await runCliJson<Record<string, any>>([
    'leaderboard', '--output', bench, '--include-fixture', '--json',
  ]);
  assert.equal(result.json.schemaVersion, 'llm-review-bench/leaderboard/v1');
  assert.equal(result.json.runsScanned, 4);
  assert.equal(result.json.evaluationsUsed, 4);
  const groupA = result.json.groups.find((group: Record<string, any>) => group.model === 'm-A');
  const groupB = result.json.groups.find((group: Record<string, any>) => group.model === 'm-B');
  assert.ok(groupA);
  assert.ok(groupB);
  assert.equal(groupA.cases, 3);
  assert.equal(groupA.completedCases, 2);
  assert.equal(groupA.timeoutCases, 1);
  assert.equal(groupA.timeoutRate, 1 / 3);
  assert.equal(groupA.findings.confirmed, 1);
  assert.equal(groupA.findings.adjudicated, 1);
  assert.equal(groupA.micro.confirmed, 1);
  assert.equal(groupA.macro.confirmed, 1);
  assert.equal(groupA.knownFixRecall.rate, 1);
  assert.equal(groupA.knownFixRecall.hits, 1);
  assert.equal(groupA.knownFixRecall.eligible, 1);
  assert.equal(groupA.knownFixRecall.cases, 1);
  assert.equal(groupA.regressionRecall.rate, null);
  assert.equal(groupA.durationMs.count, 2);
  assert.ok(groupA.durationMs.mean > 0);
  assert.equal(groupA.usage.costsByCurrency.length, 2);
  assert.deepEqual(groupA.usage.costsByCurrency.map((entry: Record<string, any>) => [entry.currency, Number(entry.cost.toFixed(2))]), [['EUR', 0.01], ['USD', 0.02]]);
  assert.equal(groupA.usage.inputTokens, 14);
  assert.equal(groupA.usage.outputTokens, 9);
  assert.equal(groupA.usage.totalTokens, 23);
  assert.equal(groupB.cases, 1);
  assert.equal(groupB.micro.confirmed, null);
  assert.deepEqual(groupB.usage.costsByCurrency, [{ currency: 'EUR', cost: 0.04 }]);
  assert.equal(groupB.usage.totalTokens, 5);
  assert.ok(result.json.notes.some((note: string) => note.includes('not pure model rankings')));
});

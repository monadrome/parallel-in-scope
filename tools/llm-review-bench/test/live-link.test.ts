import assert from 'node:assert/strict';
import { readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {
  addFix,
  createBaseRepo,
  finding,
  makeTempDir,
  readJson,
  readText,
  runCliJson,
  writeFixture,
  writeJson,
  writeSystems,
} from './helpers.js';

test('live review is open-world, and link creates gold/evaluation without calling adapters', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-live-');
  const fixture = await writeFixture(tmp, 'fixture.json', {
    summary: 'live finding',
    findings: [finding()],
  });
  const systems = await writeSystems(tmp, fixture);
  const bench = path.join(tmp, 'bench');
  const live = await runCliJson<Record<string, any>>([
    'review', '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(live.json.mode, 'live');
  assert.equal(live.json.status, 'completed');
  assert.equal(live.json.head, base.buggy);
  const runDir = live.json.runDir as string;
  const runId = live.json.runId as string;
  const evaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v1.json'));
  assert.equal(evaluation.case.mode, 'live');
  assert.equal(evaluation.case.source, 'commit');
  assert.equal(evaluation.case.openWorld, true);
  assert.equal(evaluation.case.goldStatus, 'open-world');
  assert.equal(evaluation.systems.fixture.recall.knownFix, null);
  assert.equal(evaluation.union.recall.knownFix, null);
  const goldV1 = await readJson<Record<string, any>>(path.join(bench, 'gold', runId, 'v1.json'));
  assert.equal(goldV1.status, 'open-world');
  assert.deepEqual(goldV1.labels, []);
  const html = await readText(path.join(runDir, 'reports', 'report-v1.html'));
  assert.ok(html.includes('n/a (open-world case: no gold labels)'));
  assert.ok(!html.includes('0.0% (0/'));

  const attemptsBefore = await readdir(path.join(runDir, 'reviews', 'fixture'));
  const fix = addFix(base.repo);
  await rm(fixture);
  const linked = await runCliJson<Record<string, any>>([
    'link', '--run', runId, '--fix', fix, '--output', bench, '--json',
  ]);
  assert.equal(linked.json.linkedFix, fix);
  assert.equal(linked.json.goldVersion, 2);
  assert.equal(linked.json.goldStatus, 'candidate');
  assert.equal(linked.json.evaluationVersion, 2);
  const attemptsAfterLink = await readdir(path.join(runDir, 'reviews', 'fixture'));
  assert.deepEqual(attemptsAfterLink, attemptsBefore);
  const linkedEvaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v2.json'));
  assert.equal(linkedEvaluation.case.openWorld, false);
  assert.equal(linkedEvaluation.case.goldStatus, 'candidate');
  assert.equal(linkedEvaluation.union.recall.knownFix, null);
  assert.match(linkedEvaluation.union.recall.reason, /candidate/);

  const goldInput = path.join(tmp, 'gold.json');
  const clustersInput = path.join(tmp, 'clusters.json');
  const verdictsInput = path.join(tmp, 'verdicts.json');
  await writeJson(goldInput, {
    status: 'confirmed',
    confirmedBy: 'maintainer',
    labels: [{
      goldId: 'G1', kind: 'known-fix', file: 'src/math.ts', lineStart: 1, lineEnd: 1,
      rootCause: 'subtraction regression', introducedByBuggyCommit: true,
      regressionTest: 'src/math.test.ts',
    }],
  });
  await writeJson(clustersInput, {
    method: 'human',
    clusters: [{ clusterId: 'C01', findingIds: ['fixture:0'] }],
  });
  await writeJson(verdictsInput, {
    judge: { kind: 'human', name: 'maintainer' },
    verdicts: [{ clusterId: 'C01', verdict: 'confirmed', goldLabelIds: ['G1'], confidence: 1 }],
  });
  const judged = await runCliJson<Record<string, any>>([
    'apply-judgment', runId, '--output', bench,
    '--gold', goldInput, '--clusters', clustersInput, '--verdicts', verdictsInput, '--json',
  ]);
  assert.equal(judged.json.imported.gold, 3);
  const finalEvaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v3.json'));
  assert.equal(finalEvaluation.case.fixCommit, fix);
  assert.equal(finalEvaluation.systems.fixture.rates.confirmed, 1);
  assert.equal(finalEvaluation.union.recall.knownFix, 1);
  assert.equal(finalEvaluation.union.recall.regression, null);
  assert.match(finalEvaluation.union.recall.reason, /no eligible regression labels/);
  assert.deepEqual(finalEvaluation.union.goldHits, ['G1']);
  assert.deepEqual((await readdir(path.join(runDir, 'reviews', 'fixture'))), attemptsBefore);

  const oldEvaluationText = await readText(path.join(runDir, 'derived', 'evaluation', 'v3.json'));
  const oldRunId = live.json.runId as string;
  await runCliJson<Record<string, any>>(['cleanup', oldRunId, '--output', bench, '--json']);
  await writeFixture(tmp, 'fixture.json', { findings: [finding()] });
  const rerun = await runCliJson<Record<string, any>>(['rerun', oldRunId, '--output', bench, '--json']);
  assert.notEqual(rerun.json.runId, oldRunId);
  assert.equal(rerun.json.rerunOf, oldRunId);
  assert.equal(rerun.json.source, 'commit');
  assert.equal(rerun.json.gold.status, 'open-world');
  const rerunEvaluation = await readJson<Record<string, any>>(path.join(rerun.json.runDir as string, 'derived', 'evaluation', 'v1.json'));
  assert.equal(rerunEvaluation.case.openWorld, true);
  assert.equal(rerunEvaluation.case.rerunOf, oldRunId);
  assert.equal(rerunEvaluation.union.recall.knownFix, null);
  assert.equal(rerun.json.adjudicationVersion, null);
  assert.equal(await readText(path.join(runDir, 'derived', 'evaluation', 'v3.json')), oldEvaluationText);
});

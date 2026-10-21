import assert from 'node:assert/strict';
import { readdir, readFile, realpath, rm } from 'node:fs/promises';
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
  runGit,
  writeFixture,
  writeJson,
  writeSystems,
} from './helpers.js';

function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(field);
      field = '';
    } else if (char === '\n') {
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else if (char !== '\r') {
      field += char;
    }
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

test('historical run records buggy parent range, immutable inputs, and candidate gold', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-historical-');
  const fixture = await writeFixture(tmp, 'fixture.json', {
    summary: 'fixture review',
    findings: [finding(), finding({ file: 'README.md', line: 1, title: 'second finding' })],
  });
  const systems = await writeSystems(tmp, fixture, { budget: 1 });
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(json.status, 'completed');
  const runDir = json.runDir as string;
  const manifest = await readJson<Record<string, any>>(path.join(runDir, 'run.json'));
  const buggyParent = runGit(base.repo, ['rev-parse', `${base.buggy}^`]);
  assert.equal(manifest.source, 'fix');
  assert.equal(manifest.commits.base, buggyParent);
  assert.equal(manifest.commits.head, base.buggy);
  assert.equal(manifest.commits.fixCommit, fix);
  const caseManifest = await readJson<Record<string, any>>(path.join(runDir, 'inputs', 'manifest.json'));
  assert.equal(caseManifest.range.from, buggyParent);
  assert.equal(caseManifest.range.to, base.buggy);
  const diff = await readText(path.join(runDir, 'inputs', 'diff.patch'));
  assert.match(diff, /a - b/);
  assert.doesNotMatch(diff, /math\.test\.ts/);
  const context = await readText(path.join(runDir, 'inputs', 'context.md'));
  assert.equal(context.match(/refactor: simplify add/g)?.length, 1);
  assert.ok(!context.includes(fix));
  assert.equal(manifest.hashes.contextSha256.length, 64);
  assert.equal(manifest.hashes.contextJsonSha256.length, 64);
  const contextJson = await readJson<Record<string, any>>(path.join(runDir, 'inputs', 'context.json'));
  assert.equal(contextJson.commits.head, base.buggy);
  assert.equal(contextJson.objective.subject, 'refactor: simplify add');
  const goldPath = path.join(bench, 'gold', json.runId as string, 'v1.json');
  const gold = await readJson<Record<string, any>>(goldPath);
  assert.equal(gold.status, 'candidate');
  assert.ok(gold.labels.length > 0);
  assert.ok(gold.labels.every((label: Record<string, unknown>) => label.introducedByBuggyCommit === null));
  assert.equal(await readFile(goldPath).then(() => true), true);
  await assert.rejects(readFile(path.join(runDir, 'derived', 'gold', 'v1.json')));
  const review = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(review.status, 'completed');
  assert.equal(review.findings.length, 1);
  assert.equal(review.rawFindings.length, 2);
  assert.equal(review.budgetDropped, 1);
  assert.equal(review.findings[0].lineStart, 1);
  assert.equal(review.findings[0].lineEnd, 1);
  const state = await readJson<Record<string, any>>(path.join(runDir, 'state.json'));
  assert.equal(state.status, 'completed');
  assert.equal(state.steps.case.status, 'completed');
  assert.equal(state.steps.reviews.fixture.status, 'completed');
  assert.equal(state.steps.derivation.status, 'completed');
  assert.equal(state.steps.evaluation.status, 'completed');
  assert.equal(state.steps.report.status, 'completed');
  const events = (await readText(path.join(runDir, 'events.jsonl'))).trim().split('\n').map((line) => JSON.parse(line));
  assert.equal(events[0].type, 'run.created');
  assert.deepEqual(events.map((event) => event.seq), events.map((_event, index) => index + 1));
  assert.equal(json.reports.html, path.join(runDir, 'reports', 'report-v1.html'));
});

test('resume reuses the immutable context and skips completed reviewers', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-resume-');
  const contextSource = path.join(tmp, 'custom-context.md');
  const { writeFile } = await import('node:fs/promises');
  await writeFile(contextSource, 'CUSTOM CONTEXT 42\n', 'utf8');
  const fixture = await writeFixture(tmp, 'fixture.json', { findings: [finding()] });
  const systems = await writeSystems(tmp, fixture);
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems,
    '--context', contextSource, '--json',
  ]);
  const runDir = json.runDir as string;
  const storedContextPath = path.join(runDir, 'inputs', 'context.md');
  const before = await readText(storedContextPath);
  assert.equal(before, 'CUSTOM CONTEXT 42\n');
  await writeFile(contextSource, 'CHANGED CONTEXT\n', 'utf8');
  await writeJson(fixture, { malformed: true });
  const resumed = await runCliJson<Record<string, any>>([
    'resume', json.runId as string, '--output', bench, '--json',
  ]);
  assert.equal(resumed.json.status, 'completed');
  assert.equal(await readText(storedContextPath), 'CUSTOM CONTEXT 42\n');
  const review = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(review.status, 'completed');
  const attemptsDir = await readdir(path.join(runDir, 'reviews', 'fixture'));
  assert.deepEqual(attemptsDir, ['attempt-1.json']);
  assert.equal(resumed.json.evaluationVersion, 1);
  assert.equal(resumed.json.reports.html, path.join(runDir, 'reports', 'report-v1.html'));
});

test('judgment import, ratios, recall, rescore, report, and cleanup work end to end', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-judgment-');
  const fixture = await writeFixture(tmp, 'fixture.json', {
    summary: 'three findings',
    findings: [
      finding(),
      finding({ file: 'docs/guide.md', line: 3, title: '<img src=x onerror=alert(1)>', severity: 'P2' }),
      finding({ file: 'README.md', line: 1, title: '=cmd()', severity: 'P3' }),
    ],
  });
  const systems = await writeSystems(tmp, fixture, {
    systems: [{
      agent: 'fixture', provider: 'fixture', model: '=HYPERLINK("http://x")', effort: 'none', fixture,
    }],
  });
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  const runDir = json.runDir as string;
  const runId = json.runId as string;
  const goldInput = path.join(tmp, 'gold.json');
  const clustersInput = path.join(tmp, 'clusters.json');
  const verdictsInput = path.join(tmp, 'verdicts.json');
  await writeJson(goldInput, {
    schemaVersion: 'llm-review-bench/gold-input/v1',
    status: 'confirmed',
    confirmedBy: 'test maintainer',
    labels: [
      {
        goldId: 'G1', kind: 'known-fix', file: 'src/math.ts', lineStart: 1, lineEnd: 1,
        rootCause: 'subtraction bug', fixMechanism: 'restore addition',
        introducedByBuggyCommit: true, regressionTest: 'src/math.test.ts',
      },
      {
        goldId: 'G2', kind: 'regression', file: 'src/math.ts', lineStart: 1, lineEnd: 1,
        rootCause: 'regression introduced by buggy commit', introducedByBuggyCommit: true,
      },
    ],
  });
  await writeJson(clustersInput, {
    method: 'human adjudication',
    note: 'merge duplicate code findings',
    clusters: [
      { clusterId: 'C01', findingIds: ['fixture:0', 'fixture:1'] },
      { clusterId: 'C02', findingIds: ['fixture:2'] },
    ],
  });
  await writeJson(verdictsInput, {
    judge: { kind: 'human', name: 'test maintainer' },
    verdicts: [
      { clusterId: 'C01', verdict: 'confirmed', goldLabelIds: ['G1', 'G2'], confidence: 1, rationale: 'real defect' },
      { clusterId: 'C02', verdict: 'rejected', rationale: 'style only' },
    ],
  });
  const judged = await runCliJson<Record<string, any>>([
    'apply-judgment', runId, '--output', bench,
    '--gold', goldInput, '--clusters', clustersInput, '--verdicts', verdictsInput, '--json',
  ]);
  assert.deepEqual(judged.json.imported, { gold: 2, dedup: 2, adjudication: 1 });
  assert.equal(judged.json.evaluationVersion, 2);
  const evaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v2.json'));
  const system = evaluation.systems.fixture;
  assert.deepEqual(system.findings, {
    total: 3, adjudicated: 3, confirmed: 2, rejected: 1, neutral: 0, uncertain: 0, pending: 0,
  });
  assert.equal(system.rates.confirmed, 2 / 3);
  assert.equal(system.rates.rejected, 1 / 3);
  assert.equal(system.rates.neutral, 0);
  assert.equal(system.rates.uncertain, 0);
  assert.equal(system.recall.knownFix, 1);
  assert.equal(system.recall.regression, 1);
  assert.deepEqual(evaluation.union.clusters, {
    total: 2, adjudicated: 2, confirmed: 1, rejected: 1, neutral: 0, uncertain: 0, pending: 0,
  });
  assert.equal(evaluation.union.rates.confirmed, 0.5);
  assert.equal(evaluation.union.rates.rejected, 0.5);
  assert.equal(evaluation.union.findingRates.confirmed, 2 / 3);
  assert.equal(evaluation.union.findingRates.rejected, 1 / 3);
  assert.equal(evaluation.union.recall.knownFix, 1);
  assert.equal(evaluation.union.recall.regression, 1);
  assert.equal(evaluation.aggregate.micro.confirmed, 2 / 3);
  assert.equal(evaluation.aggregate.macro!.rejected, 1 / 3);
  assert.equal(evaluation.case.goldStatus, 'confirmed');
  assert.equal(evaluation.case.goldEligibleLabels, 2);
  const gold = await readJson<Record<string, any>>(path.join(bench, 'gold', runId, 'v2.json'));
  assert.equal(gold.status, 'confirmed');
  assert.equal(gold.provenance.confirmedBy, 'test maintainer');

  const html = await readText(path.join(runDir, 'reports', 'report-v2.html'));
  assert.ok(!html.includes('<img src=x'));
  assert.ok(html.includes('&lt;img src=x'));
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('No subjective quality score'));
  assert.ok(html.includes('Case layer'));
  assert.ok(html.includes('System layer'));

  const csvText = await readText(path.join(runDir, 'reports', 'metrics-v2.csv'));
  const rows = parseCsv(csvText);
  assert.equal(rows[0]!.length, 44);
  for (const row of rows.slice(1)) {
    assert.equal(row.length, 44, `CSV row has ${row.length} columns: ${row.join('|')}`);
  }
  assert.ok(csvText.includes(`"'=HYPERLINK(""http://x"")"`));
  assert.ok(csvText.includes('=cmd()'));

  const attemptFile = path.join(runDir, 'reviews', 'fixture', 'attempt-1.json');
  const attemptBefore = await readText(attemptFile);
  await rm(fixture);
  const rescored = await runCliJson<Record<string, any>>([
    'rescore', runId, '--output', bench, '--json',
  ]);
  assert.equal(rescored.json.evaluationVersion, 3);
  assert.equal(await readText(attemptFile), attemptBefore);
  const evaluationV3 = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v3.json'));
  assert.equal(evaluationV3.systems.fixture.rates.confirmed, 2 / 3);
  assert.equal(evaluationV3.union.recall.knownFix, 1);

  const reportOne = await runCliJson<Record<string, any>>(['report', runId, '--output', bench, '--json']);
  const htmlBefore = await readText(reportOne.json.reports.html);
  const reportTwo = await runCliJson<Record<string, any>>(['report', runId, '--output', bench, '--json']);
  assert.equal(reportTwo.json.reports.html, reportOne.json.reports.html);
  assert.equal(await readText(reportTwo.json.reports.html), htmlBefore);

  const snapshots = runGit(base.repo, ['worktree', 'list', '--porcelain']);
  assert.ok(snapshots.includes(path.join(runDir, 'snapshot')));
  const cleaned = await runCliJson<Record<string, any>>(['cleanup', runId, '--output', bench, '--json']);
  assert.ok(cleaned.json.cleaned[0].removed.includes(path.join(runDir, 'snapshot')));
  const afterCleanup = runGit(base.repo, ['worktree', 'list', '--porcelain']);
  assert.ok(!afterCleanup.includes(path.join(runDir, 'snapshot')));
  assert.equal(await readFile(path.join(runDir, 'inputs', 'context.md')).then(() => true), true);
  assert.equal(await readFile(attemptFile).then(() => true), true);
  const cleanupState = await readJson<Record<string, any>>(path.join(runDir, 'state.json'));
  assert.equal(cleanupState.steps.cleanup.status, 'completed');
  const cleanedAll = await runCliJson<Record<string, any>>(['cleanup', '--all', '--output', bench, '--json']);
  assert.equal(cleanedAll.json.cleaned[0].runId, runId);
  const resumed = await runCliJson<Record<string, any>>(['resume', runId, '--output', bench, '--json']);
  assert.equal(resumed.json.status, 'completed');
});

test('default root stays inside the repo and CLI model/effort overrides reach the manifest', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-default-root-');
  const fixture = await writeFixture(tmp, 'fixture.json', { findings: [finding()] });
  const systems = await writeSystems(tmp, fixture);
  const fix = addFix(base.repo);
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--systems', systems,
    '--agents', 'fixture', '--model', 'override-model', '--effort', 'override-effort', '--json',
  ]);
  const repoReal = await realpath(base.repo);
  assert.equal(json.runDir, path.join(repoReal, '.review-benchmark', 'runs', json.runId));
  const manifest = await readJson<Record<string, any>>(path.join(json.runDir as string, 'run.json'));
  assert.equal(manifest.config.systems[0].model, 'override-model');
  assert.equal(manifest.config.systems[0].effort, 'override-effort');
  assert.equal(manifest.config.systems.length, 1);
  assert.equal(runGit(base.repo, ['status', '--porcelain']), '');
});

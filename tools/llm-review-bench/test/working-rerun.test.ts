import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';
import {
  addFix,
  createBaseRepo,
  finding,
  makeTempDir,
  readJson,
  readText,
  runCli,
  runCliJson,
  runGit,
  writeFixture,
  writeJson,
  writeRepoFile,
  writeSystems,
} from './helpers.js';

test('review --working captures staged, unstaged, and untracked changes without touching the index', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-working-');
  const repo = base.repo;
  await writeRepoFile(repo, 'src/math.ts', 'export function add(a: number, b: number): number { return a + b; }\n// working edit\n');
  await writeRepoFile(repo, 'staged.txt', 'staged version\n');
  runGit(repo, ['add', 'staged.txt']);
  await writeRepoFile(repo, 'staged.txt', 'staged then edited\n');
  await writeRepoFile(repo, 'new-file.txt', 'untracked file\n');
  const statusBefore = runGit(repo, ['status', '--porcelain']);
  const stagedBefore = runGit(repo, ['diff', '--cached', '--binary']);
  const filesBefore = runGit(repo, ['ls-files', '--stage']);
  const headBefore = runGit(repo, ['rev-parse', 'HEAD']);
  const fixture = await writeFixture(tmp, 'fixture.json', {
    findings: [finding()],
    usage: { input_tokens: 11, output_tokens: 7, total_tokens: 18, cost: 0.02, currency: 'usd' },
  });
  const systems = await writeSystems(tmp, fixture, {
    systems: [{ agent: 'fixture', fixture, model: 'm-working', effort: 'high' }],
  });
  const bench = path.join(tmp, 'bench');
  const { json } = await runCliJson<Record<string, any>>([
    'review', '--working', '--repo', repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(json.source, 'working');
  assert.equal(json.rerunOf, null);
  assert.equal(json.base, headBefore);
  assert.notEqual(json.head, headBefore);
  assert.equal(json.status, 'completed');
  const runDir = json.runDir as string;
  const manifest = await readJson<Record<string, any>>(path.join(runDir, 'run.json'));
  assert.equal(manifest.source, 'working');
  assert.equal(manifest.working.baseCommit, headBefore);
  assert.equal(manifest.working.snapshotCommit, json.head);
  assert.match(manifest.working.ref, /^refs\/llm-review-bench\//);
  assert.equal(runGit(repo, ['rev-parse', `refs/llm-review-bench/${json.runId}`]), json.head);
  const diff = await readText(path.join(runDir, 'inputs', 'diff.patch'));
  assert.match(diff, /working edit/);
  assert.match(diff, /new-file\.txt/);
  assert.equal(await readText(path.join(runDir, 'snapshot', 'new-file.txt')), 'untracked file\n');
  assert.match(await readText(path.join(runDir, 'snapshot', 'staged.txt')), /staged then edited/);
  assert.equal(runGit(repo, ['status', '--porcelain']), statusBefore);
  assert.equal(runGit(repo, ['diff', '--cached', '--binary']), stagedBefore);
  assert.equal(runGit(repo, ['ls-files', '--stage']), filesBefore);
  const record = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.deepEqual(record.usage, {
    inputTokens: 11, outputTokens: 7, totalTokens: 18, cost: 0.02, currency: 'USD',
  });
  const evaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v1.json'));
  assert.equal(evaluation.case.source, 'working');
  assert.equal(evaluation.case.openWorld, true);
  assert.deepEqual(evaluation.usage.costsByCurrency, [{ currency: 'USD', cost: 0.02 }]);
  assert.equal(evaluation.usage.totalTokens, 18);
  const html = await readText(path.join(runDir, 'reports', 'report-v1.html'));
  assert.match(html, /tokens 11 in \/ 7 out \/ 18 total/);
  assert.match(html, /0\.02 USD/);

  const oldContextHash = manifest.hashes.contextSha256;
  const oldPromptHash = manifest.hashes.promptSha256;
  const oldDiffHash = manifest.hashes.diffSha256;
  const oldEvaluation = await readText(path.join(runDir, 'derived', 'evaluation', 'v1.json'));
  const cleaned = await runCliJson<Record<string, any>>(['cleanup', json.runId as string, '--output', bench, '--json']);
  assert.ok(cleaned.json.cleaned[0].removed.includes(path.join(runDir, 'snapshot')));
  const rerun = await runCliJson<Record<string, any>>(['rerun', json.runId as string, '--output', bench, '--json']);
  assert.notEqual(rerun.json.runId, json.runId);
  assert.equal(rerun.json.rerunOf, json.runId);
  assert.equal(rerun.json.source, 'working');
  assert.equal(rerun.json.status, 'completed');
  const rerunDir = rerun.json.runDir as string;
  const rerunManifest = await readJson<Record<string, any>>(path.join(rerunDir, 'run.json'));
  assert.deepEqual(rerunManifest.config.systems, manifest.config.systems);
  assert.equal(rerunManifest.hashes.contextSha256, oldContextHash);
  assert.equal(rerunManifest.hashes.promptSha256, oldPromptHash);
  assert.equal(rerunManifest.hashes.diffSha256, oldDiffHash);
  assert.notEqual(rerunManifest.hashes.contextJsonSha256, manifest.hashes.contextJsonSha256);
  assert.equal(await readText(path.join(runDir, 'derived', 'evaluation', 'v1.json')), oldEvaluation);
  assert.equal(await readText(path.join(rerunDir, 'snapshot', 'new-file.txt')), 'untracked file\n');
  const rerunRecord = await readJson<Record<string, any>>(path.join(rerunDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(rerunRecord.status, 'completed');
  const rerunEvaluation = await readJson<Record<string, any>>(path.join(rerunDir, 'derived', 'evaluation', 'v1.json'));
  assert.equal(rerunEvaluation.case.rerunOf, json.runId);
  assert.equal(rerunEvaluation.case.openWorld, true);

  const conflict = await runCli([
    'review', '--working', '--commit', json.head as string, '--repo', repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(conflict.code, 2);
  assert.match(conflict.stderr, /cannot be combined with --commit/);

  const withBase = await runCliJson<Record<string, any>>([
    'review', '--working', '--base', base.initial, '--repo', repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(withBase.json.source, 'working');
  assert.equal(withBase.json.base, base.initial);
  assert.equal(withBase.json.fixCommit, null);
  const withBaseManifest = await readJson<Record<string, any>>(path.join(withBase.json.runDir as string, 'run.json'));
  assert.equal(withBaseManifest.working.baseCommit, headBefore);
});

test('rerun derives fresh candidate gold after cleanup and never reuses old verdicts', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-rerun-fix-');
  const fix = addFix(base.repo);
  const fixture = await writeFixture(tmp, 'fixture.json', { findings: [finding()] });
  const systems = await writeSystems(tmp, fixture, {
    systems: [{ agent: 'fixture', fixture, model: 'm-fix', effort: 'high' }],
  });
  const bench = path.join(tmp, 'bench');
  const first = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  const oldDir = first.json.runDir as string;
  const goldFile = path.join(tmp, 'gold.json');
  const clustersFile = path.join(tmp, 'clusters.json');
  const verdictsFile = path.join(tmp, 'verdicts.json');
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
  const judged = await runCliJson<Record<string, any>>([
    'apply-judgment', first.json.runId as string, '--output', bench,
    '--gold', goldFile, '--clusters', clustersFile, '--verdicts', verdictsFile, '--json',
  ]);
  assert.equal(judged.json.evaluationVersion, 2);
  const oldEvaluation = await readText(path.join(oldDir, 'derived', 'evaluation', 'v2.json'));
  await runCliJson<Record<string, any>>(['cleanup', first.json.runId as string, '--output', bench, '--json']);
  const rerun = await runCliJson<Record<string, any>>(['rerun', first.json.runId as string, '--output', bench, '--json']);
  assert.equal(rerun.json.rerunOf, first.json.runId);
  assert.equal(rerun.json.source, 'fix');
  assert.equal(rerun.json.gold.status, 'candidate');
  assert.equal(rerun.json.adjudicationVersion, null);
  const rerunDir = rerun.json.runDir as string;
  const evaluation = await readJson<Record<string, any>>(path.join(rerunDir, 'derived', 'evaluation', 'v1.json'));
  assert.equal(evaluation.case.rerunOf, first.json.runId);
  assert.equal(evaluation.case.goldStatus, 'candidate');
  assert.equal(evaluation.systems.fixture.findings.confirmed, 0);
  assert.equal(evaluation.systems.fixture.findings.pending, 1);
  assert.equal(evaluation.systems.fixture.rates.confirmed, null);
  assert.equal(evaluation.union.recall.knownFix, null);
  assert.equal(await readText(path.join(oldDir, 'derived', 'evaluation', 'v2.json')), oldEvaluation);
  const newReview = await readJson<Record<string, any>>(path.join(rerunDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(newReview.status, 'completed');
});

test('resume recreates a cleaned working snapshot from its recorded commit', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-working-resume-');
  await writeRepoFile(base.repo, 'src/math.ts', 'export const workingResume = true;\n');
  const fixture = await writeFixture(tmp, 'fixture.json', { malformed: true });
  const systems = await writeSystems(tmp, fixture);
  const bench = path.join(tmp, 'bench');
  const failed = await runCliJson<Record<string, any>>([
    'review', '--working', '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(failed.json.status, 'failed');
  const runDir = failed.json.runDir as string;
  await runCliJson<Record<string, any>>(['cleanup', failed.json.runId as string, '--output', bench, '--json']);
  await writeFixture(tmp, 'fixture.json', { findings: [finding()] });
  const resumed = await runCliJson<Record<string, any>>([
    'resume', failed.json.runId as string, '--output', bench, '--json',
  ]);
  assert.equal(resumed.json.status, 'completed');
  assert.equal(resumed.json.source, 'working');
  assert.equal(await readText(path.join(runDir, 'snapshot', 'src', 'math.ts')), 'export const workingResume = true;\n');
  const record = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-2.json'));
  assert.equal(record.status, 'completed');
});

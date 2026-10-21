import assert from 'node:assert/strict';
import { mkdtemp, readFile, readFile as readTextFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { runLoggedProcess } from '../src/adapters/process.js';
import {
  addFix,
  createBaseRepo,
  finding,
  isProcessAlive,
  makeTempDir,
  readJson,
  readText,
  runCliJson,
  waitFor,
  writeFixture,
  writeSystems,
} from './helpers.js';

const SHORT_TIMEOUTS = ['--timeout', '60', '--review-timeout', '2', '--adjudication-timeout', '5', '--cleanup-timeout', '5', '--kill-grace', '1'];

test('timeout keeps the grace kill armed after the leader exits', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-process-timeout-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const childPidFile = path.join(dir, 'child.pid');
  const childReadyFile = path.join(dir, 'child.ready');
  const childSource = [
    "const fs = require('node:fs');",
    "process.on('SIGTERM', () => {});",
    "process.on('SIGHUP', () => {});",
    'fs.writeFileSync(process.argv[1], "ready");',
    'setInterval(() => {}, 1000);',
  ].join('');
  const parentSource = [
    "const { spawn } = require('node:child_process');",
    "const fs = require('node:fs');",
    `const child = spawn(process.execPath, ['-e', ${JSON.stringify(childSource)}, ${JSON.stringify(childReadyFile)}], {stdio: 'ignore'});`,
    'fs.writeFileSync(process.argv[1], String(child.pid));',
    "process.on('SIGTERM', () => process.exit(0));",
    'setInterval(() => {}, 1000);',
  ].join('');
  const result = await runLoggedProcess({
    command: process.execPath,
    args: ['-e', parentSource, childPidFile],
    cwd: dir,
    stdoutPath: path.join(dir, 'stdout.log'),
    stderrPath: path.join(dir, 'stderr.log'),
    timeoutMs: 800,
    graceMs: 100,
  });
  assert.equal(await readTextFile(childReadyFile, 'utf8'), 'ready');
  const childPid = Number(await readTextFile(childPidFile, 'utf8'));
  assert.equal(result.status, 'timed_out');
  assert.equal(isProcessAlive(childPid), false);
});

test('hard timeout kills the whole process group and records a timeout instead of an empty review', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-timeout-');
  const childPidFile = path.join(tmp, 'grandchild.pid');
  const fixture = await writeFixture(tmp, 'fixture.json', {
    delayMs: 60_000,
    spawnChild: true,
    childPidFile,
  });
  const systems = await writeSystems(tmp, fixture);
  const contextSource = path.join(tmp, 'context.md');
  await writeFile(contextSource, 'TIMEOUT CONTEXT\n', 'utf8');
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const started = Date.now();
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems,
    '--context', contextSource, ...SHORT_TIMEOUTS, '--json',
  ], { timeoutMs: 30_000 });
  const elapsed = Date.now() - started;
  assert.ok(elapsed < 20_000, `timeout run took ${elapsed}ms`);
  assert.equal(json.status, 'failed');
  const runDir = json.runDir as string;
  const system = json.systems.fixture;
  assert.equal(system.status, 'timed_out');
  assert.equal(system.findings, 0);
  const record = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(record.status, 'timed_out');
  assert.equal(record.timedOut, true);
  assert.equal(record.findings, null);
  assert.match(record.error, /hard timeout/);
  assert.ok(await readFile(path.join(runDir, record.stdoutPath as string)).then(() => true));
  const grandchildPid = Number((await readText(childPidFile)).trim());
  assert.ok(Number.isInteger(grandchildPid) && grandchildPid > 0);
  assert.ok(await waitFor(() => !isProcessAlive(grandchildPid), 5000), `grandchild ${grandchildPid} survived`);
  const evaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v1.json'));
  assert.equal(evaluation.systems.fixture.findings.adjudicated, 0);
  assert.equal(evaluation.systems.fixture.rates.confirmed, null);
  assert.equal(evaluation.systems.fixture.recall.knownFix, null);

  const storedContextPath = path.join(runDir, 'inputs', 'context.md');
  const storedContext = await readText(storedContextPath);
  assert.equal(storedContext, 'TIMEOUT CONTEXT\n');
  await writeFile(contextSource, 'CHANGED CONTEXT\n', 'utf8');
  await writeFixture(tmp, 'fixture.json', { summary: 'recovered', findings: [finding()] });
  const resumed = await runCliJson<Record<string, any>>([
    'resume', json.runId as string, '--output', bench, '--json',
  ], { timeoutMs: 30_000 });
  assert.equal(resumed.json.status, 'completed');
  assert.equal(resumed.json.systems.fixture.findings, 1);
  assert.equal(await readText(storedContextPath), storedContext);
  const second = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-2.json'));
  assert.equal(second.status, 'completed');
  const finalState = await readJson<Record<string, any>>(path.join(runDir, 'state.json'));
  assert.equal(finalState.steps.reviews.fixture.attempts.length, 2);
  assert.equal(finalState.steps.reviews.fixture.attempts[0].status, 'timed_out');
  assert.equal(finalState.steps.reviews.fixture.attempts[1].status, 'completed');
});

test('malformed output fails the step and resume retries; an explicit empty review is completed with null rates', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-malformed-');
  const fixture = await writeFixture(tmp, 'fixture.json', { malformed: true });
  const systems = await writeSystems(tmp, fixture);
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(json.status, 'failed');
  const runDir = json.runDir as string;
  const failed = await readJson<Record<string, any>>(path.join(runDir, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(failed.status, 'failed');
  assert.equal(failed.findings, null);
  assert.match(failed.error, /invalid review output/);
  const raw = await readText(path.join(runDir, failed.stdoutPath as string));
  assert.match(raw, /this is not json/);

  await writeFixture(tmp, 'fixture.json', { summary: 'empty', findings: [] });
  const resumed = await runCliJson<Record<string, any>>([
    'resume', json.runId as string, '--output', bench, '--json',
  ]);
  assert.equal(resumed.json.status, 'completed');
  const evaluation = await readJson<Record<string, any>>(path.join(runDir, 'derived', 'evaluation', 'v2.json'));
  assert.equal(evaluation.systems.fixture.findings.total, 0);
  for (const rate of ['confirmed', 'rejected', 'neutral', 'uncertain']) {
    assert.equal(evaluation.systems.fixture.rates[rate], null);
  }
  assert.equal(evaluation.union.rates.confirmed, null);
  const html = await readText(path.join(runDir, 'reports', 'report-v2.html'));
  assert.match(html, /n\/a/);
  const state = await readJson<Record<string, any>>(path.join(runDir, 'state.json'));
  assert.equal(state.steps.reviews.fixture.attempts.length, 2);
  assert.equal(state.steps.reviews.fixture.attempts[1].status, 'completed');
});

test('a reviewer that modifies the snapshot fails the step instead of producing a result', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-dirty-');
  const fixture = await writeFixture(tmp, 'fixture.json', {
    findings: [finding()],
    modifyFile: 'src/math.ts',
    modifyContent: 'export const tampered = true;\n',
  });
  const systems = await writeSystems(tmp, fixture);
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const { json } = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  assert.equal(json.status, 'failed');
  const record = await readJson<Record<string, any>>(path.join(json.runDir as string, 'reviews', 'fixture', 'attempt-1.json'));
  assert.equal(record.status, 'failed');
  assert.equal(record.worktreeModified, true);
  assert.equal(record.findings, null);
  assert.match(record.error, /modified the read-only snapshot/);
  const snapshot = path.join(json.runDir as string, 'snapshot', 'src', 'math.ts');
  assert.match(await readText(snapshot), /tampered/);
});

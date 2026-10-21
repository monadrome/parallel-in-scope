import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  atomicWriteFile,
  listVersions,
  writeJsonImmutable,
  writeNextVersion,
  appendLine,
  readLines,
} from '../src/util/fsx.js';
import { parseStrictJson } from '../src/util/json.js';
import {
  assertRepoRelativePath,
  assertGitRev,
  resolveWithin,
} from '../src/util/path-safety.js';
import { assertStepTransition, computeRunStatus, createInitialState, rootLayout } from '../src/protocol/store.js';
import { csvCell, escapeHtml } from '../src/evaluation/report.js';
import { ImmutableWriteError, ValidationError } from '../src/util/errors.js';

test('atomic writes replace a file and leave no temporary artifacts', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-atomic-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'value.json');
  await atomicWriteFile(file, 'one\n');
  await atomicWriteFile(file, 'two\n');
  assert.equal(await readFile(file, 'utf8'), 'two\n');
  const entries = await readdir(dir);
  assert.deepEqual(entries, ['value.json']);
});

test('immutable writes refuse to overwrite and preserve the original bytes', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-immutable-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'v1.json');
  await writeJsonImmutable(file, { value: 1 });
  await assert.rejects(writeJsonImmutable(file, { value: 2 }), (error: unknown) =>
    error instanceof ImmutableWriteError);
  assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { value: 1 });
  const entries = await readdir(dir);
  assert.deepEqual(entries, ['v1.json']);
});

test('versioned writes allocate increasing versions without mutating older versions', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-versions-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const first = await writeNextVersion(dir, { version: 1 });
  const second = await writeNextVersion(dir, { version: 2 });
  assert.equal(first.version, 1);
  assert.equal(second.version, 2);
  assert.deepEqual(JSON.parse(await readFile(first.path, 'utf8')), { version: 1 });
  assert.deepEqual(JSON.parse(await readFile(second.path, 'utf8')), { version: 2 });
  assert.deepEqual((await listVersions(dir)).map((entry) => entry.version), [1, 2]);
});

test('events append as JSONL without rewriting earlier lines', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-events-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'events.jsonl');
  await appendLine(file, JSON.stringify({ seq: 1 }));
  await appendLine(file, JSON.stringify({ seq: 2 }));
  assert.deepEqual((await readLines(file)).map((line) => JSON.parse(line)), [{ seq: 1 }, { seq: 2 }]);
});

test('strict JSON rejects duplicate keys and trailing commas', () => {
  assert.throws(() => parseStrictJson('{"a":1,"a":2}', 'input'), /duplicate object key/);
  assert.throws(() => parseStrictJson('{"outer":{"x":1,"x":2}}', 'input'), /duplicate object key/);
  assert.throws(() => parseStrictJson('{"a":1,}', 'input'), /invalid JSON/);
  assert.deepEqual(parseStrictJson('{"a":1}', 'input'), { a: 1 });
});

test('path safety rejects traversal, absolute paths, and unsafe git revisions', () => {
  assert.throws(() => resolveWithin('/tmp/root', '..', 'escape'), ValidationError);
  assert.throws(() => resolveWithin('/tmp/root', 'runs', '..', '..', 'etc'), ValidationError);
  assert.equal(resolveWithin('/tmp/root', 'runs', 'id'), '/tmp/root/runs/id');
  assert.throws(() => assertRepoRelativePath('/etc/passwd', 'file'), /repository-relative/);
  assert.throws(() => assertRepoRelativePath('../etc/passwd', 'file'), /escapes/);
  assert.throws(() => assertRepoRelativePath('a/../../b', 'file'), /escapes/);
  assert.throws(() => assertRepoRelativePath('a\\b', 'file'), /backslash/);
  assert.equal(assertRepoRelativePath('./src/a.ts', 'file'), 'src/a.ts');
  assert.throws(() => assertGitRev('--help'), /unsafe/);
  assert.throws(() => assertGitRev('main..dev'), /unsafe/);
  assert.equal(assertGitRev('HEAD~1'), 'HEAD~1');
});

test('state machine enforces legal step transitions and run status', () => {
  assert.throws(() => assertStepTransition('completed', 'running', 'review'), /illegal/);
  assert.throws(() => assertStepTransition('pending', 'timed_out', 'review'), /illegal/);
  assert.doesNotThrow(() => assertStepTransition('running', 'timed_out', 'review'));
  assert.doesNotThrow(() => assertStepTransition('failed', 'running', 'review'));

  const systems = [{
    agent: 'fixture', version: 'x', provider: 'fixture', model: null, revision: null,
    effort: null, command: null, extraArgs: [], fixture: '/tmp/f.json',
  }];
  const state = createInitialState('run-1', systems);
  assert.equal(computeRunStatus(state), 'created');
  state.steps.case.status = 'completed';
  assert.equal(computeRunStatus(state), 'running');
  state.steps.reviews.fixture!.status = 'completed';
  assert.equal(computeRunStatus(state), 'completed');
  const two = createInitialState('run-2', [
    systems[0]!,
    { ...systems[0]!, agent: 'fixture-two' },
  ]);
  two.steps.case.status = 'completed';
  two.steps.reviews.fixture!.status = 'completed';
  two.steps.reviews['fixture-two']!.status = 'failed';
  assert.equal(computeRunStatus(two), 'partial');
  two.steps.reviews['fixture-two']!.status = 'timed_out';
  assert.equal(computeRunStatus(two), 'partial');
  two.steps.reviews.fixture!.status = 'failed';
  assert.equal(computeRunStatus(two), 'failed');
  two.steps.reviews.fixture!.status = 'running';
  assert.equal(computeRunStatus(two), 'running');
  assert.doesNotThrow(() => assertStepTransition('timed_out', 'running', 'review'));
});

test('root layout isolates gold from run directories', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-layout-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const layout = rootLayout(dir);
  assert.equal(layout.goldDir, path.join(dir, 'gold'));
  assert.ok(!layout.goldDir.startsWith(layout.runsDir + path.sep));
});

test('HTML escaping handles all user-controlled metacharacters', () => {
  const escaped = escapeHtml('<script>alert("x&y")</script>');
  assert.equal(escaped, '&lt;script&gt;alert(&quot;x&amp;y&quot;)&lt;/script&gt;');
  assert.ok(!escaped.includes('<script>'));
});

test('CSV escaping quotes delimiters and neutralizes formula injection', () => {
  assert.equal(csvCell('plain'), 'plain');
  assert.equal(csvCell('a,b'), '"a,b"');
  assert.equal(csvCell('say "hi"'), '"say ""hi"""');
  assert.equal(csvCell('line\nbreak'), '"line\nbreak"');
  assert.equal(csvCell('=HYPERLINK("http://x")'), `"'=HYPERLINK(""http://x"")"`);
  assert.equal(csvCell('  @SUM(A1)'), `'  @SUM(A1)`);
  assert.equal(csvCell('+1'), `'+1`);
  assert.equal(csvCell('-1'), `'-1`);
});

test('temporary files are cleaned when a write fails', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-fail-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'x');
  await writeFile(file, 'exists');
  await assert.rejects(atomicWriteFile(file, 'x', { overwrite: false }));
  assert.deepEqual(await readdir(dir), ['x']);
});

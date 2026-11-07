import assert from 'node:assert/strict';
import path from 'node:path';
import { realpath } from 'node:fs/promises';
import test from 'node:test';
import { addFix, createBaseRepo, makeTempDir, runCliJson, writeFixture, writeSystems, finding } from './helpers.js';

test('discover lists fix-like commits and existing runs without writing', async (t) => {
  const base = await createBaseRepo(t);
  const tmp = await makeTempDir(t, 'lrb-discover-');
  const bench = path.join(tmp, 'bench');
  const fix = addFix(base.repo);
  const discovered = await runCliJson<Record<string, any>>([
    'discover', '--repo', base.repo, '--output', bench, '--limit', '10', '--json',
  ]);
  assert.equal(discovered.json.repository, await realpath(base.repo));
  const shas = (discovered.json.candidates as { sha: string }[]).map((candidate) => candidate.sha);
  assert.ok(shas.includes(fix));
  assert.deepEqual(discovered.json.runs, []);

  const fixture = await writeFixture(tmp, 'fixture.json', { findings: [finding()] });
  const systems = await writeSystems(tmp, fixture);
  const run = await runCliJson<Record<string, any>>([
    'run', '--fix', fix, '--repo', base.repo, '--output', bench, '--systems', systems, '--json',
  ]);
  const again = await runCliJson<Record<string, any>>([
    'discover', '--repo', base.repo, '--output', bench, '--json',
  ]);
  assert.equal((again.json.runs as { runId: string }[])[0]!.runId, run.json.runId);
  assert.equal((again.json.runs as { mode: string }[])[0]!.mode, 'historical');
});

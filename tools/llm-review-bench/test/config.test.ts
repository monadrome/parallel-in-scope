import assert from 'node:assert/strict';
import test from 'node:test';
import { parseSystemsFile, assertSafeExtraArgs, buildConfig } from '../src/protocol/config.js';
import { ValidationError } from '../src/util/errors.js';

test('systems files require fixture paths and unique agent names', () => {
  assert.throws(
    () => parseSystemsFile(JSON.stringify({ systems: [{ agent: 'fixture' }] }), 'systems'),
    /fixture/,
  );
  assert.throws(
    () => parseSystemsFile(JSON.stringify({
      systems: [
        { agent: 'fixture', fixture: '/tmp/a.json' },
        { agent: 'fixture', fixture: '/tmp/b.json' },
      ],
    }), 'systems'),
    /duplicate agent/,
  );
});

test('dangerous bypass and auto-approval flags are rejected in extraArgs', () => {
  for (const flag of ['--dangerously-skip-permissions', '--dangerously-bypass-approvals-and-sandbox', '--yolo', '--auto', '--trust-all-tools']) {
    assert.throws(() => assertSafeExtraArgs([flag]), ValidationError, flag);
  }
  assert.doesNotThrow(() => assertSafeExtraArgs(['--model', 'x']));
});

test('README-style systems configuration parses with per-system settings', () => {
  const parsed = parseSystemsFile(JSON.stringify({
    systems: [
      { agent: 'codex', provider: 'openai', model: 'gpt-5', effort: 'high', extraArgs: ['-c', 'x=1'] },
      { agent: 'fixture', fixture: '/tmp/review.json', model: 'fixture-1' },
    ],
    budget: 12,
    concurrency: 2,
    timeouts: { totalSeconds: 600, reviewSeconds: 500, adjudicationSeconds: 60, cleanupSeconds: 30 },
  }), 'systems');
  assert.equal(parsed.systems.length, 2);
  assert.equal(parsed.systems[0]!.effort, 'high');
  assert.equal(parsed.budget, 12);
  assert.equal(parsed.timeouts.reviewSeconds, 500);
});

test('timeout configuration refuses impossible budgets', () => {
  const systems = [{ agent: 'fixture', version: 'x', provider: null, model: null, revision: null, effort: null, command: null, extraArgs: [], fixture: '/tmp/x' }];
  assert.throws(
    () => buildConfig(systems, { timeouts: { totalSeconds: 60, reviewSeconds: 90, adjudicationSeconds: 5, cleanupSeconds: 5, killGraceSeconds: 2 } }),
    /exceeds total/,
  );
  assert.throws(
    () => buildConfig(systems, { timeouts: { totalSeconds: 10, reviewSeconds: 5, adjudicationSeconds: 5, cleanupSeconds: 5, killGraceSeconds: 2 } }),
    /must leave room/,
  );
});

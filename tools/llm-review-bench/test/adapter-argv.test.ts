import assert from 'node:assert/strict';
import test from 'node:test';
import type { AdapterContext } from '../src/adapters/types.js';
import type { SystemConfig } from '../src/protocol/types.js';
import { KimiAdapter } from '../src/adapters/kimi.js';
import { ClaudeAdapter } from '../src/adapters/claude.js';

const PROMPT = 'Review this change.\nReturn JSON.';

function contextFor(agent: string, model: string | null, extraArgs: string[]): AdapterContext {
  const system: SystemConfig = {
    agent,
    version: 'test-version',
    provider: 'test-provider',
    model,
    revision: null,
    effort: 'high',
    command: null,
    extraArgs,
    fixture: null,
  };
  return {
    runId: 'run-1',
    system,
    attempt: 1,
    snapshotDir: '/tmp/snapshot',
    runDir: '/tmp/run',
    agentOutputDir: '/tmp/run/agent-output/agent/attempt-1',
    contextPath: '/tmp/run/inputs/context.md',
    diffPath: '/tmp/run/inputs/diff.patch',
    promptPath: '/tmp/run/inputs/prompt.md',
    promptText: PROMPT,
    base: 'base-sha',
    head: 'head-sha',
  };
}

test('Kimi puts every option and extraArgs before -p and the prompt last', () => {
  const plan = new KimiAdapter().buildPlan(contextFor('kimi', 'kimi-model', ['--add-dir', '/tmp/reviews', '--verbose']));
  assert.deepEqual(plan.args, [
    '--output-format', 'stream-json',
    '--model', 'kimi-model',
    '--add-dir', '/tmp/reviews',
    '--verbose',
    '-p', PROMPT,
  ]);
  assert.equal(plan.args[plan.args.length - 1], PROMPT);
  assert.equal(plan.args[plan.args.length - 2], '-p');
  assert.equal(plan.args.filter((arg) => arg === '-p').length, 1);
  assert.equal(plan.args.indexOf(PROMPT), plan.args.length - 1);
});

test('Claude puts model and extraArgs before -p and the prompt last', () => {
  const plan = new ClaudeAdapter().buildPlan(contextFor('claude', 'claude-model', ['--verbose', '--add-dir', '/tmp/reviews']));
  assert.deepEqual(plan.args, [
    '--model', 'claude-model',
    '--verbose',
    '--add-dir', '/tmp/reviews',
    '-p', PROMPT,
  ]);
  assert.equal(plan.args[plan.args.length - 1], PROMPT);
  assert.equal(plan.args[plan.args.length - 2], '-p');
  assert.equal(plan.args.filter((arg) => arg === '-p').length, 1);
  assert.equal(plan.args.indexOf(PROMPT), plan.args.length - 1);
});

test('Kimi and Claude keep prompt last without a model override', () => {
  const kimi = new KimiAdapter().buildPlan(contextFor('kimi', null, ['--verbose']));
  assert.deepEqual(kimi.args, ['--output-format', 'stream-json', '--verbose', '-p', PROMPT]);
  const claude = new ClaudeAdapter().buildPlan(contextFor('claude', null, []));
  assert.deepEqual(claude.args, ['-p', PROMPT]);
});

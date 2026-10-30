import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { parseReviewOutput } from '../src/protocol/findings.js';
import { extractCodexUsage } from '../src/adapters/codex.js';

test('OCR-style summary usage is recorded without inventing missing values', () => {
  const parsed = parseReviewOutput(JSON.stringify({
    status: 'complete',
    summary: {
      files_reviewed: 1,
      comments: 0,
      total_tokens: 396829,
      input_tokens: 390489,
      output_tokens: 6340,
    },
    comments: [],
  }));
  assert.deepEqual(parsed.usage, {
    inputTokens: 390489,
    outputTokens: 6340,
    totalTokens: 396829,
    cost: null,
    currency: null,
  });
  const withoutUsage = parseReviewOutput('{"findings":[]}');
  assert.equal(withoutUsage.usage, null);
});

test('Codex explicit "tokens used" log footer is parsed as a total only', async (t) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lrb-usage-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const stdoutPath = path.join(dir, 'stdout.log');
  const stderrPath = path.join(dir, 'stderr.log');
  await writeFile(stdoutPath, 'review complete\n\ntokens used\n124,283\n', 'utf8');
  await writeFile(stderrPath, '', 'utf8');
  const usage = await extractCodexUsage({
    exitCode: 0,
    timedOut: false,
    stdoutPath,
    stderrPath,
  }, 1024 * 1024);
  assert.deepEqual(usage, {
    inputTokens: null,
    outputTokens: null,
    totalTokens: 124283,
    cost: null,
    currency: null,
  });
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { materializeFindings, parseReviewOutput } from '../src/protocol/findings.js';
import { extractKimiText, stripAnsi } from '../src/adapters/common.js';
import { ValidationError } from '../src/util/errors.js';

test('parses a fenced JSON review with aliases and normalizes the line range', () => {
  const parsed = parseReviewOutput(`Here is the review:
\`\`\`json
{"summary":"found one","findings":[{"file_path":"./src/math.ts","line":12,"title":"bad math","description":"trigger","impact":"wrong sum","detail":"evidence","priority":"critical","confidence":90,"suggestion":"fix it"}]}
\`\`\``);
  assert.equal(parsed.summary, 'found one');
  assert.equal(parsed.findings.length, 1);
  const finding = parsed.findings[0]!;
  assert.equal(finding.file, 'src/math.ts');
  assert.equal(finding.lineStart, 12);
  assert.equal(finding.lineEnd, 12);
  assert.equal(finding.severity, 'P0');
  assert.equal(finding.confidence, 0.9);
  assert.equal(finding.trigger, 'trigger');
  assert.equal(finding.consequence, 'wrong sum');
  assert.equal(finding.suggestedFix, 'fix it');
});

test('accepts an explicit empty findings array', () => {
  const parsed = parseReviewOutput('{"summary":"nothing","findings":[]}');
  assert.deepEqual(parsed.findings, []);
  assert.equal(parsed.summary, 'nothing');
});

test('empty output and malformed output are failures, not empty reviews', () => {
  assert.throws(() => parseReviewOutput(''), /output is empty/);
  assert.throws(() => parseReviewOutput('I found no issues.'), /no JSON review object/);
  assert.throws(() => parseReviewOutput('{"findings": [ this is not json'), /invalid JSON|no JSON review object/);
  assert.throws(() => parseReviewOutput('{"summary":"x"}'), /no findings array/);
});

test('rejects missing required fields and unsafe paths', () => {
  assert.throws(
    () => parseReviewOutput('{"findings":[{"file":"src/a.ts","title":"","severity":"P1","confidence":1}]}'),
    /title/,
  );
  assert.throws(
    () => parseReviewOutput('{"findings":[{"file":"../a.ts","title":"x","severity":"P1","confidence":1}]}'),
    /escapes/,
  );
  assert.throws(
    () => parseReviewOutput('{"findings":[{"file":"src/a.ts","title":"x","severity":"urgent","confidence":1}]}'),
    /unknown severity/,
  );
  assert.throws(
    () => parseReviewOutput('{"findings":[{"file":"src/a.ts","title":"x","severity":"P1","confidence":200}]}'),
    /confidence/,
  );
});

test('missing narrative fields and confidence default deterministically with warnings', () => {
  const parsed = parseReviewOutput('{"findings":[{"file":"src/a.ts","line":3,"title":"x","severity":"P2"}]}');
  const finding = parsed.findings[0]!;
  assert.equal(finding.trigger, '');
  assert.equal(finding.consequence, '');
  assert.equal(finding.evidence, '');
  assert.equal(finding.suggestedFix, '');
  assert.equal(finding.confidence, 0.5);
  assert.ok(parsed.warnings.some((warning) => warning.includes('confidence')));
});

test('emits findings with stable ids and enforces the finding budget', () => {
  const parsed = parseReviewOutput(JSON.stringify({
    findings: [
      { file: 'a.ts', line: 1, title: 'one', severity: 'P1', confidence: 1 },
      { file: 'b.ts', line: 2, title: 'two', severity: 'P2', confidence: 1 },
      { file: 'c.ts', line: 3, title: 'three', severity: 'P3', confidence: 1 },
    ],
  }));
  const materialized = materializeFindings('fixture', 2, parsed.findings, 2);
  assert.equal(materialized.findings.length, 2);
  assert.equal(materialized.dropped, 1);
  assert.equal(materialized.findings[0]!.id, 'fixture:0');
  assert.equal(materialized.findings[1]!.id, 'fixture:1');
  assert.equal(materialized.findings[0]!.attempt, 2);
});

test('accepts a JSON array review and a nested result wrapper', () => {
  const array = parseReviewOutput('[{"file":"src/a.ts","line":1,"title":"x","severity":"P1","confidence":1}]');
  assert.equal(array.findings.length, 1);
  const wrapped = parseReviewOutput(JSON.stringify({
    result: JSON.stringify({ summary: 'nested', findings: [{ file: 'src/b.ts', line: 4, title: 'y', severity: 'P2', confidence: 0.4 }] }),
  }));
  assert.equal(wrapped.summary, 'nested');
  assert.equal(wrapped.findings[0]!.file, 'src/b.ts');
});

test('parse errors are ValidationError instances', () => {
  assert.throws(() => parseReviewOutput('{}'), (error: unknown) => error instanceof ValidationError);
});

test('Kimi stream-json text is extracted and ANSI color codes are stripped', () => {
  const stream = [
    JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: '{"summary":"kimi",' }] } }),
    JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: '"findings":[]}' }] } }),
  ].join('\n');
  const parsed = parseReviewOutput(extractKimiText(stream));
  assert.equal(parsed.summary, 'kimi');
  assert.deepEqual(parsed.findings, []);
  assert.equal(stripAnsi('\u001b[31mred\u001b[0m'), 'red');
});

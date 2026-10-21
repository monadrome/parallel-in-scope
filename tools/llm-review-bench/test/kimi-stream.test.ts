import assert from 'node:assert/strict';
import test from 'node:test';
import { extractKimiText } from '../src/adapters/common.js';
import { parseReviewOutput } from '../src/protocol/findings.js';

const JAVA_SOURCE = `package io.github.monadrome.parallelinscope;

public final class Example {
    private final int value;

    public Example(int value) {
        this.value = value;
    }

    public int classify(int input) {
        if (input > value) {
            return input;
        } else if (input < value) {
            return -input;
        }
        return 0;
    }

    public String jsonLike() {
        return "{\\"nested\\": {\\"braces\\": [1, 2, 3]}}";
    }
}

{"summary":"tool output must never become reviewer text","findings":[{"file":"src/fake.ts","line":1,"title":"fake"}]}`;

function stream(events: unknown[]): string {
  return events.map((event) => JSON.stringify(event)).join('\n');
}

test('Kimi stream extracts only assistant text and finds the final empty review JSON', () => {
  const final = JSON.stringify({
    summary: '审查完成：六个 annotation-only 变更均与冻结字段一致，未发现运行时缺陷。',
    findings: [],
  });
  const text = extractKimiText(stream([
    { role: 'meta', type: 'meta', session_id: 'session-1', command: 'review', content: 'To resume this session: kimi -r session-1' },
    {
      role: 'assistant',
      tool_calls: [{
        type: 'function',
        id: 'tool-1',
        function: { name: 'Read', arguments: JSON.stringify({ path: 'src/Example.java' }) },
      }],
    },
    { role: 'tool', tool_call_id: 'tool-1', content: JAVA_SOURCE },
    { role: 'tool', tool_call_id: 'tool-2', content: JAVA_SOURCE },
    { role: 'assistant', content: '正在检查变更，先读取 diff 和源文件。' },
    { role: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: final }] } },
  ]));
  assert.equal(text, final);
  assert.ok(!text.includes('package io.github.monadrome'));
  assert.ok(!text.includes('tool output must never become reviewer text'));
  assert.ok(!text.includes('To resume this session'));
  assert.ok(!text.includes('正在检查变更'));
  const parsed = parseReviewOutput(text);
  assert.equal(parsed.findings.length, 0);
  assert.match(parsed.summary ?? '', /未发现运行时缺陷/);
});

test('Kimi stream keeps a non-empty assistant review JSON and ignores later non-assistant events', () => {
  const final = JSON.stringify({
    summary: '发现一个真实问题。',
    findings: [{
      file: 'src/math.ts',
      lineStart: 12,
      lineEnd: 14,
      title: '加法被改成减法',
      trigger: 'add(2, 3)',
      consequence: '返回 -1 而不是 5',
      evidence: 'src/math.ts:12 使用 a - b',
      severity: 'P1',
      confidence: 0.9,
      suggestedFix: '恢复 a + b',
    }],
  });
  const text = extractKimiText(stream([
    { role: 'meta', type: 'meta', content: 'To resume this session: kimi -r session-2' },
    { role: 'tool', tool_call_id: 'tool-1', content: `class Braces { void run() { if (true) { } } }\n${JAVA_SOURCE}` },
    { role: 'assistant', content: '进度：已经定位到数学函数。' },
    { role: 'assistant', content: final },
    { role: 'meta', type: 'meta', content: 'To resume this session: kimi -r session-2' },
  ]));
  assert.equal(text, final);
  const parsed = parseReviewOutput(text);
  assert.equal(parsed.findings.length, 1);
  assert.equal(parsed.findings[0]!.file, 'src/math.ts');
  assert.equal(parsed.findings[0]!.severity, 'P1');
});

test('Kimi nested message text still works when split across assistant events', () => {
  const text = extractKimiText(stream([
    { type: 'assistant', message: { content: [{ type: 'text', text: '{"summary":"split",' }] } },
    { type: 'assistant', message: { content: [{ type: 'text', text: '"findings":[]}' }] } },
  ]));
  assert.equal(text, '{"summary":"split","findings":[]}');
  assert.deepEqual(parseReviewOutput(text).findings, []);
});

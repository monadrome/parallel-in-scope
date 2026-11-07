import assert from 'node:assert/strict';
import test from 'node:test';
import { parseClustersInput } from '../src/evaluation/dedup.js';
import { parseVerdictsInput } from '../src/evaluation/adjudication.js';
import { parseGoldInput } from '../src/evaluation/gold.js';
import type { CaseManifest } from '../src/protocol/types.js';

const caseManifest = {
  commits: { base: 'b', head: 'h', fixCommit: 'f' },
} as unknown as CaseManifest;

test('cluster input must partition every finding exactly once', () => {
  const known = new Set(['fixture:0', 'fixture:1']);
  assert.throws(
    () => parseClustersInput(JSON.stringify({ clusters: [{ findingIds: ['fixture:0'] }] }), 'clusters', known),
    /not assigned to any cluster/,
  );
  assert.throws(
    () => parseClustersInput(JSON.stringify({
      clusters: [{ findingIds: ['fixture:0'] }, { findingIds: ['fixture:0', 'fixture:1'] }],
    }), 'clusters', known),
    /more than one cluster/,
  );
  assert.throws(
    () => parseClustersInput(JSON.stringify({ clusters: [{ findingIds: ['fixture:9'] }] }), 'clusters', known),
    /unknown finding id/,
  );
  const parsed = parseClustersInput(JSON.stringify({
    clusters: [{ clusterId: 'C01', findingIds: ['fixture:0', 'fixture:1'] }],
  }), 'clusters', known);
  assert.equal(parsed.clusters.length, 1);
  assert.deepEqual(parsed.clusters[0]!.findingIds, ['fixture:0', 'fixture:1']);
});

test('verdict input rejects unknown clusters and duplicate verdicts', () => {
  const fallbackJudge = { kind: 'human' as const, name: 'tester', model: null, note: null };
  assert.throws(
    () => parseVerdictsInput(JSON.stringify({
      verdicts: [{ clusterId: 'C99', verdict: 'confirmed' }],
    }), 'verdicts', { clusterIds: new Set(['C01']), goldIds: null, fallbackJudge }),
    /unknown cluster/,
  );
  assert.throws(
    () => parseVerdictsInput(JSON.stringify({
      verdicts: [
        { clusterId: 'C01', verdict: 'confirmed' },
        { clusterId: 'C01', verdict: 'rejected' },
      ],
    }), 'verdicts', { clusterIds: new Set(['C01']), goldIds: null, fallbackJudge }),
    /duplicate verdict/,
  );
  assert.throws(
    () => parseVerdictsInput(JSON.stringify({
      verdicts: [{ clusterId: 'C01', verdict: 'confirmed', goldLabelIds: ['G9'] }],
    }), 'verdicts', { clusterIds: new Set(['C01']), goldIds: new Set(['G1']), fallbackJudge }),
    /unknown gold label/,
  );
});

test('confirmed gold requires an explicit introduced-by relationship', () => {
  assert.throws(
    () => parseGoldInput(JSON.stringify({
      status: 'confirmed',
      confirmedBy: 'tester',
      labels: [{ file: 'src/a.ts', lineStart: 1, rootCause: 'x' }],
    }), 'gold', { caseManifest, confirmedBy: null }),
    /introducedByBuggyCommit/,
  );
  assert.throws(
    () => parseGoldInput(JSON.stringify({ status: 'confirmed', labels: [] }), 'gold', { caseManifest, confirmedBy: null }),
    /confirmedBy/,
  );
  const parsed = parseGoldInput(JSON.stringify({
    status: 'confirmed',
    confirmedBy: 'tester',
    labels: [{ file: 'src/a.ts', lineStart: 1, lineEnd: 2, rootCause: 'x', introducedByBuggyCommit: false }],
  }), 'gold', { caseManifest, confirmedBy: null });
  assert.equal(parsed.labels[0]!.introducedByBuggyCommit, false);
  assert.equal(parsed.status, 'confirmed');
});

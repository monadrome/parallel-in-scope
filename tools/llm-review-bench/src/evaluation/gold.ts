import { ValidationError } from '../util/errors.js';
import {
  jsonArray,
  jsonBoolean,
  jsonEnum,
  jsonInteger,
  jsonObject,
  jsonString,
  parseStrictJson,
} from '../util/json.js';
import { assertRepoRelativePath, assertSafeSegment } from '../util/path-safety.js';
import type { CaseManifest, ExcludedFixScope, GoldLabel, GoldRecord, GoldStatus } from '../protocol/types.js';
import { GOLD_SCHEMA, GOLD_KINDS } from '../protocol/types.js';

export function openWorldGold(
  runId: string,
  version: number,
  createdAt: string,
  caseManifest: CaseManifest,
): GoldRecord {
  return {
    schemaVersion: GOLD_SCHEMA,
    runId,
    version,
    createdAt,
    status: 'open-world',
    case: {
      fixCommit: caseManifest.commits.fixCommit,
      base: caseManifest.commits.base,
      head: caseManifest.commits.head,
    },
    provenance: { source: 'live', confirmedBy: null, note: 'live review has no gold labels' },
    labels: [],
    excludedFixScope: [],
  };
}

interface HunkLabel {
  file: string;
  lineStart: number;
  lineEnd: number;
  fixMechanism: string;
}

export function parseUnifiedDiffHunks(diff: string): HunkLabel[] {
  const labels: HunkLabel[] = [];
  let currentFile: string | null = null;
  let currentHunk: HunkLabel | null = null;
  const flush = (): void => {
    if (currentHunk !== null) {
      currentHunk.fixMechanism = currentHunk.fixMechanism.trim().slice(0, 600);
      labels.push(currentHunk);
      currentHunk = null;
    }
  };
  for (const line of diff.split('\n')) {
    if (line.startsWith('diff --git ')) {
      flush();
      currentFile = null;
      continue;
    }
    if (line.startsWith('+++ ')) {
      const raw = line.slice(4).trim();
      if (raw === '/dev/null') {
        currentFile = null;
      } else {
        const candidate = raw.startsWith('b/') ? raw.slice(2) : raw;
        currentFile = candidate;
      }
      continue;
    }
    if (line.startsWith('@@ ')) {
      flush();
      if (currentFile === null) {
        continue;
      }
      const match = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(line);
      if (match?.[1] === undefined) {
        continue;
      }
      const start = Number(match[1]);
      const count = match[2] === undefined ? 1 : Number(match[2]);
      currentHunk = {
        file: currentFile,
        lineStart: Math.max(1, start),
        lineEnd: Math.max(1, start + Math.max(0, count - 1)),
        fixMechanism: '',
      };
      continue;
    }
    if (currentHunk !== null && line.startsWith('+') && !line.startsWith('+++')) {
      currentHunk.fixMechanism += `${line.slice(1)}\n`;
    }
  }
  flush();
  return labels;
}

export function candidateGoldFromFix(
  runId: string,
  version: number,
  createdAt: string,
  caseManifest: CaseManifest,
  fixCommit: string,
  fixSubject: string,
  fixDiff: string,
): GoldRecord {
  const hunks = parseUnifiedDiffHunks(fixDiff);
  const labels: GoldLabel[] = hunks.slice(0, 200).map((hunk, index) => ({
    goldId: `G${index + 1}`,
    kind: 'known-fix',
    file: assertRepoRelativePath(hunk.file, `fix hunk ${index + 1} file`),
    lineStart: hunk.lineStart,
    lineEnd: hunk.lineEnd,
    rootCause: fixSubject,
    fixMechanism: hunk.fixMechanism.length > 0 ? hunk.fixMechanism : null,
    regressionTest: null,
    introducedByBuggyCommit: null,
    evidence: `candidate extracted from fix commit ${fixCommit}`,
  }));
  const truncated = hunks.length > labels.length;
  return {
    schemaVersion: GOLD_SCHEMA,
    runId,
    version,
    createdAt,
    status: 'candidate',
    case: {
      fixCommit,
      base: caseManifest.commits.base,
      head: caseManifest.commits.head,
    },
    provenance: {
      source: 'fix-diff-candidate',
      confirmedBy: null,
      note: truncated
        ? `candidate labels extracted from ${fixCommit}; ${hunks.length - labels.length} hunks omitted by the 200-label cap`
        : `candidate labels extracted from ${fixCommit}; no semantic confirmation was performed`,
    },
    labels,
    excludedFixScope: [],
  };
}

export interface GoldInputOptions {
  caseManifest: CaseManifest;
  confirmedBy: string | null;
}

function parseExcluded(value: unknown, label: string): ExcludedFixScope {
  const object = jsonObject(value, label);
  return {
    reason: jsonString(object['reason'], `${label}.reason`),
    rootCause: jsonString(object['rootCause'], `${label}.rootCause`),
    goldId: object['goldId'] === undefined || object['goldId'] === null
      ? null
      : assertSafeSegment(jsonString(object['goldId'], `${label}.goldId`), 'gold id'),
  };
}

export function parseGoldInput(
  text: string,
  label: string,
  options: GoldInputOptions,
): GoldRecord {
  const root = jsonObject(parseStrictJson(text, label), label);
  const status = jsonEnum(root['status'], `${label}.status`, ['candidate', 'confirmed'] as const) as GoldStatus;
  const confirmedByInput = root['confirmedBy'] === undefined || root['confirmedBy'] === null
    ? null
    : jsonString(root['confirmedBy'], `${label}.confirmedBy`, { nonEmpty: false });
  const confirmedBy = confirmedByInput ?? options.confirmedBy;
  if (status === 'confirmed' && (confirmedBy === null || confirmedBy.trim().length === 0)) {
    throw new ValidationError(`${label}: confirmed gold requires confirmedBy`);
  }
  const note = root['note'] === undefined || root['note'] === null
    ? null
    : jsonString(root['note'], `${label}.note`, { nonEmpty: false });
  const rawLabels = jsonArray(root['labels'], `${label}.labels`);
  const labels: GoldLabel[] = [];
  const goldIds = new Set<string>();
  rawLabels.forEach((value, index) => {
    const item = jsonObject(value, `${label}.labels[${index}]`);
    const goldId = item['goldId'] === undefined
      ? `G${index + 1}`
      : assertSafeSegment(jsonString(item['goldId'], `${label}.labels[${index}].goldId`), 'gold id');
    if (goldIds.has(goldId)) {
      throw new ValidationError(`${label}.labels[${index}].goldId: duplicate ${goldId}`);
    }
    goldIds.add(goldId);
    const introduced = item['introducedByBuggyCommit'] === undefined || item['introducedByBuggyCommit'] === null
      ? null
      : jsonBoolean(item['introducedByBuggyCommit'], `${label}.labels[${index}].introducedByBuggyCommit`);
    if (status === 'confirmed' && introduced === null) {
      throw new ValidationError(
        `${label}.labels[${index}].introducedByBuggyCommit: confirmed gold must state the introduced relationship explicitly`,
      );
    }
    const lineStart = jsonInteger(item['lineStart'] ?? item['line'] ?? 0, `${label}.labels[${index}].lineStart`, 0, 10_000_000);
    const lineEnd = jsonInteger(item['lineEnd'] ?? lineStart, `${label}.labels[${index}].lineEnd`, 0, 10_000_000);
    const fixMechanism = item['fixMechanism'] === undefined || item['fixMechanism'] === null
      ? null
      : jsonString(item['fixMechanism'], `${label}.labels[${index}].fixMechanism`, { nonEmpty: false });
    const regressionTest = item['regressionTest'] === undefined || item['regressionTest'] === null
      ? null
      : jsonString(item['regressionTest'], `${label}.labels[${index}].regressionTest`, { nonEmpty: false });
    labels.push({
      goldId,
      kind: jsonEnum(item['kind'] ?? 'known-fix', `${label}.labels[${index}].kind`, GOLD_KINDS),
      file: assertRepoRelativePath(jsonString(item['file'], `${label}.labels[${index}].file`), `${label}.labels[${index}].file`),
      lineStart: Math.min(lineStart, lineEnd),
      lineEnd: Math.max(lineStart, lineEnd),
      rootCause: jsonString(item['rootCause'], `${label}.labels[${index}].rootCause`),
      fixMechanism,
      regressionTest,
      introducedByBuggyCommit: introduced,
      evidence: item['evidence'] === undefined || item['evidence'] === null
        ? null
        : jsonString(item['evidence'], `${label}.labels[${index}].evidence`, { nonEmpty: false }),
    });
  });
  if (status === 'confirmed' && labels.length === 0) {
    throw new ValidationError(`${label}: confirmed gold must contain at least one label`);
  }
  const excluded = root['excludedFixScope'] === undefined
    ? []
    : jsonArray(root['excludedFixScope'], `${label}.excludedFixScope`).map((value, index) =>
        parseExcluded(value, `${label}.excludedFixScope[${index}]`));
  return {
    schemaVersion: GOLD_SCHEMA,
    runId: '',
    version: 0,
    createdAt: '',
    status,
    case: {
      fixCommit: options.caseManifest.commits.fixCommit,
      base: options.caseManifest.commits.base,
      head: options.caseManifest.commits.head,
    },
    provenance: { source: 'explicit-json', confirmedBy, note },
    labels,
    excludedFixScope: excluded,
  };
}

export function finalizeGold(
  record: GoldRecord,
  runId: string,
  version: number,
  createdAt: string,
  caseManifest: CaseManifest,
): GoldRecord {
  return {
    ...record,
    runId,
    version,
    createdAt,
    case: {
      fixCommit: record.case.fixCommit ?? caseManifest.commits.fixCommit,
      base: caseManifest.commits.base,
      head: caseManifest.commits.head,
    },
  };
}

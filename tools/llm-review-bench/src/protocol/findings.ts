import { ValidationError } from '../util/errors.js';
import { isJsonObject, parseStrictJson } from '../util/json.js';
import { assertRepoRelativePath } from '../util/path-safety.js';
import { findingId } from '../util/ids.js';
import type { Finding, ParseResult, RawFinding, Severity, Usage } from './types.js';
import { usageFromReviewObject } from './usage.js';

const SEVERITY_ALIASES: Record<string, Severity> = {
  p0: 'P0',
  p1: 'P1',
  p2: 'P2',
  p3: 'P3',
  critical: 'P0',
  blocker: 'P0',
  high: 'P1',
  major: 'P1',
  error: 'P1',
  medium: 'P2',
  moderate: 'P2',
  warning: 'P2',
  low: 'P3',
  minor: 'P3',
  info: 'P3',
  nit: 'P3',
};

const ARRAY_KEYS = ['findings', 'suggestions', 'issues', 'comments', 'reviewComments', 'review_comments'];

function asString(value: unknown): string | null {
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }
  return null;
}

function pick(object: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) {
    if (object[key] !== undefined && object[key] !== null) {
      return object[key];
    }
  }
  return undefined;
}

function parseLine(value: unknown, label: string): number {
  if (value === undefined || value === null) {
    return 0;
  }
  const number = typeof value === 'string' ? Number(value) : value;
  if (typeof number !== 'number' || !Number.isInteger(number) || number < 0) {
    throw new ValidationError(`${label}: expected a non-negative integer line number`);
  }
  return number;
}

function parseSeverity(value: unknown, label: string): Severity {
  if (typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 3) {
    return `P${value}` as Severity;
  }
  if (typeof value !== 'string') {
    throw new ValidationError(`${label}: expected severity P0-P3 or critical/high/medium/low`);
  }
  const normalized = SEVERITY_ALIASES[value.trim().toLowerCase()];
  if (normalized === undefined) {
    throw new ValidationError(`${label}: unknown severity ${JSON.stringify(value)}; expected P0-P3 or critical/high/medium/low`);
  }
  return normalized;
}

function parseConfidence(value: unknown, label: string, warnings: string[]): number {
  if (value === undefined || value === null) {
    warnings.push(`${label}: confidence missing, defaulted to 0.5`);
    return 0.5;
  }
  const number = typeof value === 'string' ? Number(value) : value;
  if (typeof number !== 'number' || !Number.isFinite(number) || number < 0) {
    throw new ValidationError(`${label}: expected confidence between 0 and 1`);
  }
  if (number > 1 && number <= 100) {
    warnings.push(`${label}: confidence ${number} interpreted as a percentage`);
    return number / 100;
  }
  if (number > 1) {
    throw new ValidationError(`${label}: confidence must be between 0 and 1`);
  }
  return number;
}

function normalizeFinding(value: unknown, index: number, label: string, warnings: string[]): RawFinding {
  if (!isJsonObject(value)) {
    throw new ValidationError(`${label}[${index}]: finding must be a JSON object`);
  }
  const fileValue = pick(value, ['file', 'file_path', 'filepath', 'path', 'filename']);
  if (typeof fileValue !== 'string') {
    throw new ValidationError(`${label}[${index}].file: expected a repository-relative string`);
  }
  const file = assertRepoRelativePath(fileValue, `${label}[${index}].file`);
  const title = asString(pick(value, ['title', 'message', 'summary', 'name']));
  if (title === null || title.trim().length === 0) {
    throw new ValidationError(`${label}[${index}].title: expected a non-empty string`);
  }
  const lineStart = parseLine(pick(value, ['lineStart', 'line_start', 'startLine', 'start_line', 'line', 'line_number']), `${label}[${index}].lineStart`);
  const rawLineEnd = parseLine(pick(value, ['lineEnd', 'line_end', 'endLine', 'end_line', 'end_line_number']), `${label}[${index}].lineEnd`);
  const lineEnd = rawLineEnd === 0 ? lineStart : rawLineEnd;
  const severity = parseSeverity(pick(value, ['severity', 'priority', 'level']), `${label}[${index}].severity`);
  const confidence = parseConfidence(pick(value, ['confidence', 'certainty']), `${label}[${index}].confidence`, warnings);
  const optional = (keys: string[], fallback: string): string => {
    const text = asString(pick(value, keys));
    if (text === null || text.trim().length === 0) {
      warnings.push(`${label}[${index}]: missing ${keys[0]}, defaulted to empty string`);
      return fallback;
    }
    return text;
  };
  return {
    file,
    lineStart: Math.min(lineStart, lineEnd),
    lineEnd: Math.max(lineStart, lineEnd),
    title,
    trigger: optional(['trigger', 'description', 'reproduction', 'repro'], ''),
    consequence: optional(['consequence', 'impact', 'risk'], ''),
    evidence: optional(['evidence', 'proof', 'detail', 'details', 'code'], ''),
    severity,
    confidence,
    suggestedFix: optional(['suggestedFix', 'suggested_fix', 'suggestion', 'fix', 'recommendation'], ''),
  };
}

function reviewFromObject(value: unknown, label: string, depth: number): { items: unknown[]; summary: string | null; usage: Usage | null } | null {
  if (depth > 3) {
    return null;
  }
  if (Array.isArray(value)) {
    return { items: value, summary: null, usage: null };
  }
  if (!isJsonObject(value)) {
    return null;
  }
  for (const key of ARRAY_KEYS) {
    const candidate = value[key];
    if (Array.isArray(candidate)) {
      const summary = asString(value['summary']);
      return { items: candidate, summary, usage: usageFromReviewObject(value) };
    }
  }
  for (const wrapper of ['review', 'result', 'data', 'output']) {
    const nested = value[wrapper];
    if (isJsonObject(nested)) {
      const result = reviewFromObject(nested, `${label}.${wrapper}`, depth + 1);
      if (result !== null) {
        return result;
      }
    }
    if (typeof nested === 'string') {
      const text = nested.trim();
      if (text.startsWith('{') || text.startsWith('[') || text.includes('```')) {
        try {
          const result = reviewFromText(text, `${label}.${wrapper}`, depth + 1);
          if (result !== null) {
            return result;
          }
        } catch {
          continue;
        }
      }
    }
  }
  return null;
}

function outermostJsonStructures(text: string): string[] {
  const structures: string[] = [];
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    if (char !== '{' && char !== '[') {
      index += 1;
      continue;
    }
    const start = index;
    let depth = 0;
    let inString = false;
    let escaped = false;
    while (index < text.length) {
      const current = text[index];
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (current === '\\') {
          escaped = true;
        } else if (current === '"') {
          inString = false;
        }
        index += 1;
        continue;
      }
      if (current === '"') {
        inString = true;
      } else if (current === '{' || current === '[') {
        depth += 1;
      } else if (current === '}' || current === ']') {
        depth -= 1;
        if (depth === 0) {
          index += 1;
          structures.push(text.slice(start, index));
          break;
        }
      }
      index += 1;
    }
    if (depth !== 0) {
      break;
    }
  }
  return structures;
}

function extractCandidates(text: string): string[] {
  const candidates: string[] = [];
  const trimmed = text.trim();
  if (trimmed.length > 0) {
    candidates.push(trimmed);
  }
  const fenced = [...text.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)].map((match) => match[1]?.trim() ?? '');
  for (const block of fenced.reverse()) {
    if (block.length > 0) {
      candidates.push(block);
    }
  }
  for (const structure of outermostJsonStructures(text).reverse()) {
    candidates.push(structure);
  }
  return candidates;
}

function reviewFromText(text: string, label: string, depth: number): { items: unknown[]; summary: string | null; usage: Usage | null } | null {
  let lastError: unknown = null;
  let parsedAny = false;
  for (const candidate of extractCandidates(text)) {
    try {
      const value = parseStrictJson(candidate, label);
      parsedAny = true;
      const review = reviewFromObject(value, label, depth);
      if (review !== null) {
        return review;
      }
    } catch (error) {
      lastError = error;
    }
  }
  if (parsedAny) {
    throw new ValidationError(`${label}: output contains JSON but no findings array`, { cause: lastError });
  }
  if (lastError !== null) {
    throw new ValidationError(`${label}: no JSON review object with a findings array was found`, { cause: lastError });
  }
  return null;
}

export function parseReviewOutput(
  text: string,
  options: { label?: string; maxBytes?: number } = {},
): ParseResult {
  const label = options.label ?? 'review output';
  if (Buffer.byteLength(text, 'utf8') > (options.maxBytes ?? 8 * 1024 * 1024)) {
    throw new ValidationError(`${label}: output exceeds the ${options.maxBytes ?? 8 * 1024 * 1024} byte limit`);
  }
  if (text.trim().length === 0) {
    throw new ValidationError(`${label}: output is empty; an empty review must be the JSON object {"findings":[]}`);
  }
  const review = reviewFromText(text, label, 0);
  if (review === null) {
    throw new ValidationError(`${label}: no JSON review object with a findings array was found`);
  }
  const warnings: string[] = [];
  const findings = review.items.map((item, index) => normalizeFinding(item, index, label, warnings));
  return { summary: review.summary, findings, warnings, usage: review.usage };
}

export function materializeFindings(
  agent: string,
  attempt: number,
  raw: RawFinding[],
  budget: number,
): { findings: Finding[]; dropped: number } {
  const kept = raw.slice(0, budget);
  return {
    findings: kept.map((finding, index) => ({
      ...finding,
      id: findingId(agent, index),
      reviewer: agent,
      attempt,
    })),
    dropped: raw.length - kept.length,
  };
}

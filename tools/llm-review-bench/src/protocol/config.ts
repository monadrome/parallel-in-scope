import { UsageError, ValidationError } from '../util/errors.js';
import {
  jsonArray,
  jsonInteger,
  jsonNullableString,
  jsonObject,
  jsonString,
  parseStrictJson,
} from '../util/json.js';
import { assertAgentName } from '../util/path-safety.js';
import { readTextFile } from '../util/fsx.js';
import type { BenchmarkConfig, SystemConfig, TimeoutConfig } from './types.js';

export const DEFAULT_TIMEOUTS: TimeoutConfig = {
  totalSeconds: 3060,
  reviewSeconds: 2700,
  adjudicationSeconds: 300,
  cleanupSeconds: 60,
  killGraceSeconds: 10,
};

export const DEFAULT_BUDGET = 20;
export const DEFAULT_CONCURRENCY = 3;
export const DEFAULT_MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
export const REAL_AGENTS = ['codex', 'claude', 'kimi', 'ocr', 'kiro'] as const;

const DANGEROUS_ARG_PATTERNS = [
  /^--dangerously/i,
  /^--yolo(?:=.*)?$/i,
  /^--allow-all-tools(?:=.*)?$/i,
  /^--trust-all-tools(?:=.*)?$/i,
  /^--auto(?:=.*)?$/i,
  /^--full-auto(?:=.*)?$/i,
];

export function assertSafeExtraArgs(args: string[]): string[] {
  for (const arg of args) {
    if (DANGEROUS_ARG_PATTERNS.some((pattern) => pattern.test(arg))) {
      throw new ValidationError(
        `extraArgs must not contain bypass or auto-approval flag ${JSON.stringify(arg)}; adapters use their audited defaults`,
      );
    }
  }
  return args;
}

function onlyKeys(object: Record<string, unknown>, allowed: string[], label: string): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw new ValidationError(`${label}: unknown key ${JSON.stringify(key)}; allowed keys are ${allowed.join(', ')}`);
    }
  }
}

function parseSystem(value: unknown, label: string): SystemConfig {
  const object = jsonObject(value, label);
  onlyKeys(object, ['agent', 'version', 'provider', 'model', 'revision', 'effort', 'command', 'extraArgs', 'fixture'], label);
  const agent = assertAgentName(jsonString(object['agent'], `${label}.agent`));
  const extraArgs = object['extraArgs'] === undefined
    ? []
    : jsonArray(object['extraArgs'], `${label}.extraArgs`).map((arg, index) =>
        jsonString(arg, `${label}.extraArgs[${index}]`));
  assertSafeExtraArgs(extraArgs);
  const command = object['command'] === undefined ? null : jsonString(object['command'], `${label}.command`);
  const fixture = object['fixture'] === undefined ? null : jsonString(object['fixture'], `${label}.fixture`);
  if (agent === 'fixture' && fixture === null) {
    throw new ValidationError(`${label}.fixture: the fixture adapter requires a fixture JSON file`);
  }
  return {
    agent,
    version: object['version'] === undefined ? 'unknown' : jsonString(object['version'], `${label}.version`),
    provider: jsonNullableString(object['provider'], `${label}.provider`),
    model: jsonNullableString(object['model'], `${label}.model`),
    revision: jsonNullableString(object['revision'], `${label}.revision`),
    effort: jsonNullableString(object['effort'], `${label}.effort`),
    command,
    extraArgs,
    fixture,
  };
}

export function parseTimeouts(value: unknown, label: string): TimeoutConfig {
  const object = jsonObject(value, label);
  onlyKeys(object, ['totalSeconds', 'reviewSeconds', 'adjudicationSeconds', 'cleanupSeconds', 'killGraceSeconds'], label);
  const timeouts = { ...DEFAULT_TIMEOUTS };
  const fields: (keyof TimeoutConfig)[] = [
    'totalSeconds',
    'reviewSeconds',
    'adjudicationSeconds',
    'cleanupSeconds',
    'killGraceSeconds',
  ];
  for (const field of fields) {
    if (object[field] !== undefined) {
      timeouts[field] = jsonInteger(object[field], `${label}.${field}`, 1, 86_400);
    }
  }
  return timeouts;
}

export interface SystemsFile {
  systems: SystemConfig[];
  budget: number;
  concurrency: number;
  timeouts: TimeoutConfig;
  maxOutputBytes: number;
}

export function parseSystemsFile(text: string, label: string): SystemsFile {
  const root = jsonObject(parseStrictJson(text, label), label);
  onlyKeys(root, ['systems', 'budget', 'concurrency', 'timeouts', 'maxOutputBytes'], label);
  const systems = jsonArray(root['systems'], `${label}.systems`).map((system, index) =>
    parseSystem(system, `${label}.systems[${index}]`));
  const seen = new Set<string>();
  for (const system of systems) {
    if (seen.has(system.agent)) {
      throw new ValidationError(`${label}.systems: duplicate agent ${system.agent}`);
    }
    seen.add(system.agent);
  }
  return {
    systems,
    budget: root['budget'] === undefined ? DEFAULT_BUDGET : jsonInteger(root['budget'], `${label}.budget`, 0, 10_000),
    concurrency: root['concurrency'] === undefined
      ? DEFAULT_CONCURRENCY
      : jsonInteger(root['concurrency'], `${label}.concurrency`, 1, 64),
    timeouts: root['timeouts'] === undefined ? { ...DEFAULT_TIMEOUTS } : parseTimeouts(root['timeouts'], `${label}.timeouts`),
    maxOutputBytes: root['maxOutputBytes'] === undefined
      ? DEFAULT_MAX_OUTPUT_BYTES
      : jsonInteger(root['maxOutputBytes'], `${label}.maxOutputBytes`, 1024, 256 * 1024 * 1024),
  };
}

export async function loadSystemsFile(file: string): Promise<SystemsFile> {
  return parseSystemsFile(await readTextFile(file), file);
}

export function buildConfig(
  systems: SystemConfig[],
  options: {
    budget?: number;
    concurrency?: number;
    timeouts?: TimeoutConfig;
    maxOutputBytes?: number;
  } = {},
): BenchmarkConfig {
  const timeouts = options.timeouts ?? { ...DEFAULT_TIMEOUTS };
  for (const [key, value] of Object.entries(timeouts)) {
    if (!Number.isInteger(value) || value < 1) {
      throw new ValidationError(`timeouts.${key} must be a positive integer, got ${String(value)}`);
    }
  }
  if (timeouts.reviewSeconds > timeouts.totalSeconds) {
    throw new UsageError(
      `review timeout (${timeouts.reviewSeconds}s) exceeds total timeout (${timeouts.totalSeconds}s); raise --timeout or lower --review-timeout`,
    );
  }
  if (timeouts.adjudicationSeconds + timeouts.cleanupSeconds >= timeouts.totalSeconds) {
    throw new UsageError(
      `adjudication (${timeouts.adjudicationSeconds}s) plus cleanup (${timeouts.cleanupSeconds}s) must leave room inside the ${timeouts.totalSeconds}s total budget`,
    );
  }
  return {
    systems,
    budget: options.budget ?? DEFAULT_BUDGET,
    concurrency: options.concurrency ?? DEFAULT_CONCURRENCY,
    timeouts,
    maxOutputBytes: options.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES,
  };
}

export function applySystemOverrides(
  systems: SystemConfig[],
  overrides: { model?: string; effort?: string },
): SystemConfig[] {
  return systems.map((system) => ({
    ...system,
    model: overrides.model ?? system.model,
    effort: overrides.effort ?? system.effort,
  }));
}


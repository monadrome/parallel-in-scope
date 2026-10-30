import fs from 'node:fs/promises';
import { ValidationError } from '../util/errors.js';
import { parseStrictJson } from '../util/json.js';
import { parseReviewOutput } from '../protocol/findings.js';
import type { ParseResult, SystemConfig, SystemIdentity } from '../protocol/types.js';
import type { AdapterContext, AdapterPlan, ExecutionView } from './types.js';
import { probeCommand } from './process.js';

export async function resolveIdentity(
  system: SystemConfig,
  defaultCommand: string,
  versionArgs: string[] = ['--version'],
): Promise<SystemIdentity> {
  const command = system.command ?? defaultCommand;
  const probe = await probeCommand(command, versionArgs);
  if (!probe.found) {
    throw new ValidationError(
      `${system.agent} CLI not found (command ${JSON.stringify(command)}); install it, set system.command, or select another --agents value`,
    );
  }
  return {
    agent: system.agent,
    version: system.version !== 'unknown' ? system.version : probe.version,
    provider: system.provider,
    model: system.model,
    revision: system.revision,
    effort: system.effort,
  };
}

export async function readLimited(file: string, maxBytes: number, label: string): Promise<string | null> {
  let stat;
  try {
    stat = await fs.stat(file);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return null;
    }
    throw error;
  }
  if (stat.size > maxBytes) {
    throw new ValidationError(`${label} is ${stat.size} bytes, exceeding the ${maxBytes} byte limit`);
  }
  return fs.readFile(file, 'utf8');
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function assistantRole(record: Record<string, unknown>): string | undefined {
  if (typeof record['role'] === 'string') {
    return record['role'];
  }
  const message = record['message'];
  if (isObject(message) && typeof message['role'] === 'string') {
    return message['role'];
  }
  return record['type'] === 'assistant' ? 'assistant' : undefined;
}

function appendAssistantText(value: unknown, chunks: string[]): void {
  if (typeof value === 'string') {
    chunks.push(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      appendAssistantText(item, chunks);
    }
    return;
  }
  if (!isObject(value)) {
    return;
  }
  if (typeof value['text'] === 'string') {
    chunks.push(value['text']);
  }
  if (typeof value['content'] === 'string') {
    chunks.push(value['content']);
  } else if (Array.isArray(value['content'])) {
    appendAssistantText(value['content'], chunks);
  }
  if (Array.isArray(value['parts'])) {
    appendAssistantText(value['parts'], chunks);
  }
}

function assistantText(record: Record<string, unknown>): string[] {
  const chunks: string[] = [];
  if (typeof record['text'] === 'string') {
    chunks.push(record['text']);
  }
  if (typeof record['result'] === 'string') {
    chunks.push(record['result']);
  }
  appendAssistantText(record['content'], chunks);
  const message = record['message'];
  if (typeof message === 'string') {
    chunks.push(message);
  } else if (message !== undefined) {
    appendAssistantText(message, chunks);
  }
  return chunks;
}

function looksLikeReviewObject(text: string): boolean {
  return text.includes('{') && /"(findings|suggestions|issues|comments|reviewComments|review_comments)"\s*:/.test(text);
}

export function extractKimiText(stream: string): string {
  const chunks: string[] = [];
  let parsedAny = false;
  for (const line of stream.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.length === 0 || !trimmed.startsWith('{')) {
      continue;
    }
    let value: unknown;
    try {
      value = parseStrictJson(trimmed, 'kimi stream event');
      parsedAny = true;
    } catch {
      continue;
    }
    if (!isObject(value) || assistantRole(value) !== 'assistant') {
      continue;
    }
    chunks.push(...assistantText(value));
  }
  if (!parsedAny) {
    return stream;
  }
  for (let start = chunks.length - 1; start >= 0; start -= 1) {
    const candidate = chunks.slice(start).join('');
    if (candidate.trim().length === 0 || !looksLikeReviewObject(candidate)) {
      continue;
    }
    try {
      parseReviewOutput(stripAnsi(candidate));
      return candidate;
    } catch {
      continue;
    }
  }
  return chunks.join('');
}

export function stripAnsi(text: string): string {
  return text.replace(/\u001b\[[0-9;]*m/g, '');
}

export async function readFileTail(file: string, maxBytes: number): Promise<string | null> {
  let handle;
  try {
    handle = await fs.open(file, 'r');
    const stat = await handle.stat();
    const length = Math.min(stat.size, maxBytes);
    const buffer = Buffer.alloc(length);
    if (length > 0) {
      await handle.read(buffer, 0, length, stat.size - length);
    }
    return buffer.toString('utf8');
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return null;
    }
    throw error;
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

export async function parseStandardOutput(
  context: AdapterContext,
  plan: AdapterPlan,
  execution: ExecutionView,
  maxBytes: number,
  options: { streamJson?: boolean } = {},
): Promise<ParseResult> {
  const label = `${context.system.agent} attempt ${context.attempt}`;
  let text: string | null = null;
  if (plan.resultFile !== null) {
    text = await readLimited(plan.resultFile, maxBytes, `${label} last-message file`);
  }
  if (text === null || text.trim().length === 0) {
    text = await readLimited(execution.stdoutPath, maxBytes, `${label} stdout`);
  }
  if (text === null || text.trim().length === 0) {
    throw new ValidationError(`${label}: produced no review text (exit code ${String(execution.exitCode)})`);
  }
  const normalized = options.streamJson === true ? extractKimiText(text) : text;
  return parseReviewOutput(stripAnsi(normalized), { label, maxBytes });
}

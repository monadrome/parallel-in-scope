import { ValidationError } from './errors.js';

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

type Token =
  | { kind: 'string'; value: string }
  | { kind: 'punct'; value: '{' | '}' | '[' | ']' | ':' | ',' }
  | { kind: 'scalar'; value: string };

function tokenize(text: string, label: string): Token[] {
  const tokens: Token[] = [];
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    if (char === undefined) {
      break;
    }
    if (char === ' ' || char === '\t' || char === '\n' || char === '\r') {
      index += 1;
      continue;
    }
    if ('{}[]:,'.includes(char)) {
      tokens.push({ kind: 'punct', value: char as '{' });
      index += 1;
      continue;
    }
    if (char === '"') {
      const start = index;
      index += 1;
      let escaped = false;
      while (index < text.length) {
        const current = text[index];
        if (escaped) {
          escaped = false;
          index += 1;
          continue;
        }
        if (current === '\\') {
          escaped = true;
          index += 1;
          continue;
        }
        if (current === '"') {
          index += 1;
          break;
        }
        index += 1;
      }
      const raw = text.slice(start, index);
      try {
        tokens.push({ kind: 'string', value: JSON.parse(raw) as string });
      } catch (error) {
        throw new ValidationError(`${label}: malformed JSON string at offset ${start}`, { cause: error });
      }
      continue;
    }
    const start = index;
    while (index < text.length && !' \t\n\r{}[]:,'.includes(text[index] ?? '')) {
      index += 1;
    }
    if (start === index) {
      throw new ValidationError(`${label}: unexpected character ${JSON.stringify(char)} at offset ${index}`);
    }
    tokens.push({ kind: 'scalar', value: text.slice(start, index) });
  }
  return tokens;
}

function assertNoDuplicateKeys(text: string, label: string): void {
  type Frame = { kind: 'object'; keys: Set<string>; expectKey: boolean } | { kind: 'array' };
  const stack: Frame[] = [];
  for (const token of tokenize(text, label)) {
    const top = stack[stack.length - 1];
    if (token.kind === 'punct') {
      switch (token.value) {
        case '{':
          stack.push({ kind: 'object', keys: new Set(), expectKey: true });
          break;
        case '[':
          stack.push({ kind: 'array' });
          break;
        case '}':
        case ']':
          stack.pop();
          break;
        case ',':
          if (top?.kind === 'object') {
            top.expectKey = true;
          }
          break;
        case ':':
          break;
      }
      continue;
    }
    if (top?.kind === 'object' && top.expectKey && token.kind === 'string') {
      if (top.keys.has(token.value)) {
        throw new ValidationError(`${label}: duplicate object key ${JSON.stringify(token.value)}`);
      }
      top.keys.add(token.value);
      top.expectKey = false;
    }
  }
}

export function parseStrictJson(text: string, label: string): unknown {
  const withoutBom = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  let value: unknown;
  try {
    value = JSON.parse(withoutBom) as unknown;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new ValidationError(`${label}: invalid JSON: ${message}`, { cause: error });
  }
  assertNoDuplicateKeys(withoutBom, label);
  return value;
}

export function stringifyJson(value: unknown): string {
  return `${JSON.stringify(value, null, 2)}\n`;
}

export function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function jsonObject(value: unknown, label: string): Record<string, unknown> {
  if (!isJsonObject(value)) {
    throw new ValidationError(`${label}: expected a JSON object`);
  }
  return value;
}

export function jsonArray(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) {
    throw new ValidationError(`${label}: expected a JSON array`);
  }
  return value;
}

export function jsonString(value: unknown, label: string, options: { nonEmpty?: boolean } = {}): string {
  if (typeof value !== 'string') {
    throw new ValidationError(`${label}: expected a string`);
  }
  if (options.nonEmpty !== false && value.trim().length === 0) {
    throw new ValidationError(`${label}: expected a non-empty string`);
  }
  return value;
}

export function jsonNullableString(value: unknown, label: string): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  return jsonString(value, label, { nonEmpty: false });
}

export function jsonInteger(value: unknown, label: string, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    throw new ValidationError(`${label}: expected an integer between ${min} and ${max}`);
  }
  return value;
}

export function jsonNumber(value: unknown, label: string, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) {
    throw new ValidationError(`${label}: expected a number between ${min} and ${max}`);
  }
  return value;
}

export function jsonEnum<T extends string>(value: unknown, label: string, allowed: readonly T[]): T {
  if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) {
    throw new ValidationError(`${label}: expected one of ${allowed.join(', ')}, got ${JSON.stringify(value)}`);
  }
  return value as T;
}

export function jsonBoolean(value: unknown, label: string): boolean {
  if (typeof value !== 'boolean') {
    throw new ValidationError(`${label}: expected a boolean`);
  }
  return value;
}

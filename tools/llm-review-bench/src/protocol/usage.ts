import type { Usage } from './types.js';

function numberOrNull(value: unknown, integer = false): number | null {
  const parsed = typeof value === 'string' ? Number(value) : value;
  if (typeof parsed !== 'number' || !Number.isFinite(parsed) || parsed < 0) {
    return null;
  }
  return integer ? Math.trunc(parsed) : parsed;
}

function pickNumber(object: Record<string, unknown>, keys: string[], integer = false): number | null {
  for (const key of keys) {
    const value = numberOrNull(object[key], integer);
    if (value !== null) {
      return value;
    }
  }
  return null;
}

function pickCurrency(object: Record<string, unknown>): string | null {
  for (const key of ['currency', 'cost_currency', 'costCurrency']) {
    const value = object[key];
    if (typeof value === 'string' && value.trim().length > 0) {
      return value.trim().toUpperCase();
    }
  }
  return null;
}

export function normalizeUsage(value: unknown): Usage | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  const object = value as Record<string, unknown>;
  const usage: Usage = {
    inputTokens: pickNumber(object, ['input_tokens', 'inputTokens', 'prompt_tokens', 'promptTokens'], true),
    outputTokens: pickNumber(object, ['output_tokens', 'outputTokens', 'completion_tokens', 'completionTokens'], true),
    totalTokens: pickNumber(object, ['total_tokens', 'totalTokens'], true),
    cost: pickNumber(object, ['cost', 'total_cost', 'totalCost']),
    currency: pickCurrency(object),
  };
  if (
    usage.inputTokens === null &&
    usage.outputTokens === null &&
    usage.totalTokens === null &&
    usage.cost === null &&
    usage.currency === null
  ) {
    return null;
  }
  return usage;
}

export function usageFromReviewObject(object: Record<string, unknown>): Usage | null {
  const candidates = [object['usage'], object['summary'], object['metrics'], object['manifest']];
  for (const candidate of candidates) {
    const usage = normalizeUsage(candidate);
    if (usage !== null) {
      return usage;
    }
  }
  for (const wrapper of ['result', 'review', 'data', 'output']) {
    const nested = object[wrapper];
    if (nested !== null && typeof nested === 'object' && !Array.isArray(nested)) {
      const usage = usageFromReviewObject(nested as Record<string, unknown>);
      if (usage !== null) {
        return usage;
      }
    }
  }
  return normalizeUsage(object);
}

export function mergeUsage(primary: Usage | null, fallback: Usage | null): Usage | null {
  if (primary === null) {
    return fallback;
  }
  if (fallback === null) {
    return primary;
  }
  return {
    inputTokens: primary.inputTokens ?? fallback.inputTokens,
    outputTokens: primary.outputTokens ?? fallback.outputTokens,
    totalTokens: primary.totalTokens ?? fallback.totalTokens,
    cost: primary.cost ?? fallback.cost,
    currency: primary.currency ?? fallback.currency,
  };
}

export function usageTokenSum(usages: Usage[], key: 'inputTokens' | 'outputTokens' | 'totalTokens'): number | null {
  const values = usages.map((usage) => usage[key]).filter((value): value is number => value !== null);
  return values.length === 0 ? null : values.reduce((total, value) => total + value, 0);
}

export class BenchError extends Error {
  readonly exitCode: number;
  readonly code: string;

  constructor(message: string, options: { exitCode?: number; code?: string; cause?: unknown } = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'BenchError';
    this.exitCode = options.exitCode ?? 1;
    this.code = options.code ?? 'bench_error';
  }
}

export class UsageError extends BenchError {
  constructor(message: string) {
    super(message, { exitCode: 2, code: 'usage_error' });
    this.name = 'UsageError';
  }
}

export class ValidationError extends BenchError {
  constructor(message: string, options: { cause?: unknown } = {}) {
    super(message, { exitCode: 1, code: 'validation_error', ...options });
    this.name = 'ValidationError';
  }
}

export class ImmutableWriteError extends BenchError {
  constructor(path: string) {
    super(`refusing to overwrite immutable file ${path}`, { code: 'immutable_write' });
    this.name = 'ImmutableWriteError';
  }
}

export class NotFoundError extends BenchError {
  constructor(message: string) {
    super(message, { code: 'not_found' });
    this.name = 'NotFoundError';
  }
}

export function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

export function errorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'code' in error
    ? String((error as { code: unknown }).code)
    : undefined;
}

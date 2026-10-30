import path from 'node:path';
import { ValidationError } from './errors.js';

const SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const AGENT = /^[a-z][a-z0-9-]*$/;

export function assertSafeSegment(value: string, label: string): string {
  if (!SEGMENT.test(value) || value.length > 128 || value === '.' || value === '..') {
    throw new ValidationError(`${label} must match ${SEGMENT.source} and be at most 128 characters, got ${JSON.stringify(value)}`);
  }
  return value;
}

export function assertAgentName(value: string): string {
  if (!AGENT.test(value) || value.length > 64) {
    throw new ValidationError(`agent name must match ${AGENT.source} and be at most 64 characters, got ${JSON.stringify(value)}`);
  }
  return value;
}

export function assertRepoRelativePath(value: string, label: string): string {
  if (value.length === 0 || value.length > 4096) {
    throw new ValidationError(`${label} must be a non-empty path of at most 4096 characters`);
  }
  if (value.includes('\0') || value.includes('\\')) {
    throw new ValidationError(`${label} must be a repository-relative POSIX path without NUL or backslash, got ${JSON.stringify(value)}`);
  }
  if (path.posix.isAbsolute(value) || /^[A-Za-z]:\//.test(value) || value.startsWith('//')) {
    throw new ValidationError(`${label} must be repository-relative, got absolute path ${JSON.stringify(value)}`);
  }
  const normalized = path.posix.normalize(value);
  if (normalized === '.' || normalized === '..' || normalized.startsWith('../')) {
    throw new ValidationError(`${label} escapes the repository with ${JSON.stringify(value)}`);
  }
  if (normalized.split('/').some((part) => part === '..')) {
    throw new ValidationError(`${label} escapes the repository with ${JSON.stringify(value)}`);
  }
  return normalized;
}

export function resolveWithin(root: string, ...segments: string[]): string {
  const resolvedRoot = path.resolve(root);
  const resolved = path.resolve(resolvedRoot, ...segments);
  if (resolved !== resolvedRoot && !resolved.startsWith(resolvedRoot + path.sep)) {
    throw new ValidationError(`path ${JSON.stringify(segments.join('/'))} escapes ${resolvedRoot}`);
  }
  return resolved;
}

export function assertGitRev(value: string): string {
  if (value.length === 0 || value.length > 256) {
    throw new ValidationError('git revision must be between 1 and 256 characters');
  }
  if (value.startsWith('-') || value.includes('..') || value.includes('@{') || value.includes('\0') || /\s/.test(value)) {
    throw new ValidationError(`unsafe git revision ${JSON.stringify(value)}`);
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._/~^-]*$/.test(value)) {
    throw new ValidationError(`unsupported characters in git revision ${JSON.stringify(value)}`);
  }
  return value;
}

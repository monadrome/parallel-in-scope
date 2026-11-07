import { randomBytes } from 'node:crypto';
import { utcStamp } from './time.js';
import { assertSafeSegment } from './path-safety.js';

export function shortSha(sha: string): string {
  return sha.slice(0, 8);
}

export function repoNameFromRoot(root: string): string {
  const parts = root.split(/[\\/]/).filter((part) => part.length > 0);
  return parts[parts.length - 1] ?? 'repo';
}

export function createRunId(repoName: string, head: string, date = new Date()): string {
  const safeRepo = (repoName.replace(/[^A-Za-z0-9._-]/g, '-').replace(/^[^A-Za-z0-9]+/, '') || 'repo').slice(0, 60);
  const suffix = randomBytes(3).toString('hex');
  return assertSafeSegment(`${safeRepo}-${shortSha(head)}-${utcStamp(date)}-${suffix}`, 'run id');
}

export function findingId(agent: string, index: number): string {
  return `${agent}:${index}`;
}

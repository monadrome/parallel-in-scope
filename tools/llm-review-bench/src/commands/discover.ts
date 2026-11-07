import fs from 'node:fs/promises';
import path from 'node:path';
import { resolveRepo } from '../util/git.js';
import { changedFilesInCommit, recentCommits } from '../util/git.js';
import { errorCode } from '../util/errors.js';
import { RunStore } from '../protocol/store.js';
import { resolveBenchRoot } from './shared.js';

export interface DiscoverOptions {
  repo?: string;
  output?: string;
  limit?: number;
  json?: boolean;
}

const FIX_LIKE = /\b(fix|bugfix|hotfix|repair|regression|revert|broken|failure)\b/i;
const TEST_FILE = /(^|\/)(test|tests|__tests__|spec)\/|\.(test|spec)\.[^/]+$|(Test|Tests|IT)\.(java|kt|ts|js|cs|go|rs)$/;

export interface Candidate {
  sha: string;
  committedAt: string;
  subject: string;
  reason: string;
  files: number;
  touchesTests: boolean;
}

export interface ExistingRun {
  runId: string;
  mode: string | null;
  status: string | null;
  head: string | null;
  fixCommit: string | null;
  path: string;
}

export async function discover(options: DiscoverOptions): Promise<{
  repository: string;
  candidates: Candidate[];
  runs: ExistingRun[];
}> {
  const repo = await resolveRepo(options.repo ?? process.cwd());
  const limit = Math.min(Math.max(options.limit ?? 20, 1), 200);
  const commits = await recentCommits(repo, limit);
  const candidates: Candidate[] = [];
  for (const commit of commits) {
    const files = await changedFilesInCommit(repo, commit.sha);
    const touchesTests = files.some((file) => TEST_FILE.test(file));
    const touchesSource = files.some((file) => !TEST_FILE.test(file));
    let reason: string | null = null;
    if (FIX_LIKE.test(commit.subject)) {
      reason = 'fix-like subject';
    } else if (touchesTests && touchesSource) {
      reason = 'source and test change';
    }
    if (reason !== null) {
      candidates.push({
        sha: commit.sha,
        committedAt: commit.committedAt,
        subject: commit.subject,
        reason,
        files: files.length,
        touchesTests,
      });
    }
  }

  const root = options.output === undefined ? path.join(repo, '.review-benchmark') : resolveBenchRoot(options.output);
  const runsDir = path.join(root, 'runs');
  const runs: ExistingRun[] = [];
  let names: string[] = [];
  try {
    names = await fs.readdir(runsDir);
  } catch (error) {
    if (errorCode(error) !== 'ENOENT') {
      throw error;
    }
  }
  for (const name of names.sort()) {
    if (name.startsWith('.')) {
      continue;
    }
    try {
      const store = new RunStore(root, name);
      const manifest = await store.readManifest();
      const state = await store.readState();
      runs.push({
        runId: name,
        mode: manifest.mode,
        status: state.status,
        head: manifest.commits.head,
        fixCommit: manifest.commits.fixCommit,
        path: store.runDir,
      });
    } catch {
      continue;
    }
  }
  return { repository: repo, candidates, runs };
}

export function formatDiscover(result: { repository: string; candidates: Candidate[]; runs: ExistingRun[] }): string {
  const lines = [`repository: ${result.repository}`, '', 'candidate commits:'];
  if (result.candidates.length === 0) {
    lines.push('  (none in the selected history window)');
  } else {
    for (const candidate of result.candidates) {
      lines.push(`  ${candidate.sha.slice(0, 12)}  ${candidate.committedAt.slice(0, 10)}  ${candidate.reason.padEnd(22)}  ${candidate.subject}`);
    }
  }
  lines.push('', 'existing runs:');
  if (result.runs.length === 0) {
    lines.push('  (none)');
  } else {
    for (const run of result.runs) {
      lines.push(`  ${run.runId}  ${(run.mode ?? '?').padEnd(10)}  ${(run.status ?? '?').padEnd(9)}  head=${(run.head ?? '?').slice(0, 12)}  fix=${run.fixCommit === null ? '-' : run.fixCommit.slice(0, 12)}`);
    }
  }
  return `${lines.join('\n')}\n`;
}

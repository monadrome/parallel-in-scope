import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { ValidationError } from '../util/errors.js';
import { removePath } from '../util/fsx.js';
import { git, gitRaw } from '../util/git.js';
import { nowIso } from '../util/time.js';
import type { WorkingSnapshot } from '../protocol/types.js';

const SNAPSHOT_IDENTITY = {
  GIT_AUTHOR_NAME: 'llm-review-bench',
  GIT_AUTHOR_EMAIL: 'noreply@llm-review-bench.invalid',
  GIT_COMMITTER_NAME: 'llm-review-bench',
  GIT_COMMITTER_EMAIL: 'noreply@llm-review-bench.invalid',
};

export interface CapturedWorkingTree extends WorkingSnapshot {
  tree: string;
  message: string;
}

export async function captureWorkingTree(
  repo: string,
  runId: string,
  baseCommit: string,
): Promise<CapturedWorkingTree> {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lrb-index-'));
  const indexFile = path.join(tempDir, 'index');
  try {
    const readTree = await gitRaw(repo, ['read-tree', baseCommit], {
      env: { GIT_INDEX_FILE: indexFile },
      timeoutMs: 120_000,
    });
    if (readTree.code !== 0) {
      throw new ValidationError(`could not prepare a temporary index for the working snapshot: ${readTree.stderr.trim()}`);
    }
    const add = await gitRaw(repo, ['add', '-A', '--', '.'], {
      env: { GIT_INDEX_FILE: indexFile },
      timeoutMs: 120_000,
    });
    if (add.code !== 0) {
      throw new ValidationError(`could not capture the working tree: ${add.stderr.trim()}`);
    }
    const tree = (await git(repo, ['write-tree'], { env: { GIT_INDEX_FILE: indexFile } })).trim();
    const baseTree = (await git(repo, ['rev-parse', `${baseCommit}^{tree}`])).trim();
    if (tree === baseTree) {
      throw new ValidationError(
        'the working tree has no changes relative to HEAD; use review --commit or review --base for committed changes',
      );
    }
    const message = `working tree snapshot ${nowIso()}`;
    const commit = (await git(repo, [
      'commit-tree',
      tree,
      '-p',
      baseCommit,
      '-m',
      message,
    ], { env: SNAPSHOT_IDENTITY })).trim();
    const ref = `refs/llm-review-bench/${runId}`;
    await git(repo, ['update-ref', ref, commit]);
    return { baseCommit, snapshotCommit: commit, ref, tree, message };
  } finally {
    await removePath(tempDir);
  }
}

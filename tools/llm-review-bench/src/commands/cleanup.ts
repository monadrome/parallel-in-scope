import fs from 'node:fs/promises';
import path from 'node:path';
import { UsageError, describeError } from '../util/errors.js';
import { pathExists, removePath } from '../util/fsx.js';
import { worktreePrune, worktreeRemove } from '../util/git.js';
import { markStepCompleted, setStepStatus } from '../evaluation/pipeline.js';
import { openRun, printJson, resolveBenchRoot } from './shared.js';

export interface CleanupOptions {
  output?: string;
  all?: boolean;
  dryRun?: boolean;
  json?: boolean;
}

interface CleanupResult {
  runId: string;
  removed: string[];
  dryRun: boolean;
  error?: string;
}

async function cleanOne(root: string, runId: string, dryRun: boolean): Promise<CleanupResult> {
  const { store, manifest } = await openRun(root, runId);
  const snapshot = store.resolve(manifest.paths.snapshotDir);
  const agentOutput = store.resolve('agent-output');
  if (dryRun) {
    return { runId, removed: [snapshot, agentOutput], dryRun: true };
  }
  const state = await store.readState();
  if (state.steps.cleanup.status !== 'completed' && state.steps.cleanup.status !== 'running') {
    await setStepStatus(store, 'cleanup', 'running');
  }
  const removed: string[] = [];
  if (await pathExists(snapshot)) {
    await worktreeRemove(manifest.repository.root, snapshot, manifest.config.timeouts.cleanupSeconds * 1000);
    if (await pathExists(snapshot)) {
      await removePath(snapshot);
    }
    removed.push(snapshot);
  }
  await worktreePrune(manifest.repository.root, manifest.config.timeouts.cleanupSeconds * 1000).catch(() => undefined);
  if (await pathExists(agentOutput)) {
    await removePath(agentOutput);
    removed.push(agentOutput);
  }
  await markStepCompleted(store, 'cleanup');
  await store.event('cleanup.completed', { removed });
  return { runId, removed, dryRun: false };
}

export async function cleanupCommand(runId: string | null, options: CleanupOptions): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const runIds: string[] = [];
  if (options.all === true) {
    const runsDir = path.join(root, 'runs');
    const names = await fs.readdir(runsDir).catch(() => [] as string[]);
    for (const name of names.sort()) {
      if (!name.startsWith('.')) {
        runIds.push(name);
      }
    }
  } else {
    if (runId === null) {
      throw new UsageError('cleanup requires a run id or --all');
    }
    runIds.push(runId);
  }
  const results: CleanupResult[] = [];
  for (const id of runIds) {
    try {
      results.push(await cleanOne(root, id, options.dryRun === true));
    } catch (error) {
      if (options.all !== true) {
        throw error;
      }
      results.push({ runId: id, removed: [], dryRun: options.dryRun === true, error: describeError(error) });
    }
  }
  if (options.json === true) {
    printJson({ cleaned: results });
  } else {
    for (const result of results) {
      if (result.error !== undefined) {
        process.stdout.write(`${result.runId}: error: ${result.error}\n`);
      } else {
        process.stdout.write(`${result.runId}: ${result.dryRun ? 'would remove' : 'removed'} ${result.removed.join(', ') || 'nothing (already clean)'}\n`);
      }
    }
  }
  return results.some((result) => result.error !== undefined) ? 1 : 0;
}

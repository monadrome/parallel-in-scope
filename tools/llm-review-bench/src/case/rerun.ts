import fs from 'node:fs/promises';
import { NotFoundError, ValidationError } from '../util/errors.js';
import {
  pathExists,
  readJsonFile,
  readTextFile,
  sha256File,
  writeImmutable,
  writeJsonImmutable,
} from '../util/fsx.js';
import { nowIso } from '../util/time.js';
import { createRunId, repoNameFromRoot, shortSha } from '../util/ids.js';
import { git, gitRaw, worktreeAdd } from '../util/git.js';
import type { CaseManifest, RunManifest, RunState, WorkingSnapshot } from '../protocol/types.js';
import { RunStore, createInitialState, ensureBenchRoot } from '../protocol/store.js';
import { verifyInputs } from './prepare.js';
import { TOOL_NAME, TOOL_VERSION } from '../version.js';

export interface StartedRerun {
  root: string;
  store: RunStore;
  manifest: RunManifest;
  caseManifest: CaseManifest;
  state: RunState;
}

export async function startRerun(root: string, oldRunId: string): Promise<StartedRerun> {
  const oldStore = new RunStore(root, oldRunId);
  if (!(await oldStore.exists())) {
    throw new NotFoundError(`run ${oldRunId} not found under ${root}/runs`);
  }
  const oldManifest = await oldStore.readManifest();
  const oldCase = await verifyInputs(oldStore);
  const oldContextJson = await readJsonFile<Record<string, unknown>>(
    oldStore.resolve(oldManifest.paths.inputsDir, 'context.json'),
    `context json for ${oldRunId}`,
  );
  const repo = oldManifest.repository.root;
  const head = oldManifest.commits.head;
  const headCheck = await gitRaw(repo, ['cat-file', '-e', `${head}^{commit}`]);
  if (headCheck.code !== 0) {
    throw new ValidationError(
      `snapshot commit ${head} for run ${oldRunId} is no longer present in ${repo}; rerun cannot reproduce this case`,
    );
  }
  await ensureBenchRoot(root);
  const newRunId = createRunId(repoNameFromRoot(repo), head);
  const store = new RunStore(root, newRunId);
  if (await store.exists()) {
    throw new ValidationError(`rerun run id collision at ${store.runDir}; retry the command`);
  }
  await store.createDir();

  const contextText = await readTextFile(oldStore.resolve(oldManifest.paths.inputsDir, 'context.md'), 'context');
  const diffText = await readTextFile(oldStore.resolve(oldManifest.paths.inputsDir, 'diff.patch'), 'diff');
  const promptText = await readTextFile(oldStore.resolve(oldManifest.promptPath), 'prompt');
  await writeImmutable(store.resolve('inputs', 'context.md'), contextText);
  await writeImmutable(store.resolve('inputs', 'diff.patch'), diffText);
  await writeImmutable(store.resolve('inputs', 'prompt.md'), promptText);

  const now = nowIso();
  const working: WorkingSnapshot | null = oldManifest.source === 'working'
    ? (() => {
        const ref = `refs/llm-review-bench/${newRunId}`;
        return {
          baseCommit: oldManifest.working?.baseCommit ?? oldManifest.commits.base,
          snapshotCommit: head,
          ref,
        };
      })()
    : null;
  if (working !== null) {
    await git(repo, ['update-ref', working.ref, head]);
  }
  const contextJson = {
    ...oldContextJson,
    runId: newRunId,
    source: oldManifest.source,
    rerunOf: oldRunId,
    createdAt: now,
    working,
    snapshot: { path: 'snapshot', head, policy: 'read-only-by-policy' },
  };
  await writeJsonImmutable(store.resolve('inputs', 'context.json'), contextJson);
  const contextJsonPath = store.resolve('inputs', 'context.json');

  const snapshot = store.resolve('snapshot');
  if (await pathExists(snapshot)) {
    throw new ValidationError(`rerun snapshot path already exists at ${snapshot}`);
  }
  await worktreeAdd(repo, snapshot, head);

  const contextJsonStat = await fs.stat(contextJsonPath);
  const caseManifest: CaseManifest = {
    ...oldCase,
    runId: newRunId,
    source: oldManifest.source,
    rerunOf: oldRunId,
    working,
    createdAt: now,
    contextJson: {
      path: 'inputs/context.json',
      sha256: await sha256File(contextJsonPath),
      bytes: contextJsonStat.size,
    },
    snapshot: { path: 'snapshot', head, policy: 'read-only-by-policy' },
    range: {
      ...oldCase.range,
      label: oldManifest.source === 'working'
        ? `${shortSha(oldCase.commits.base)}..${shortSha(head)} (working tree)`
        : oldCase.range.label,
    },
  };
  await writeJsonImmutable(store.resolve('inputs', 'manifest.json'), caseManifest);
  const caseManifestPath = store.resolve('inputs', 'manifest.json');

  const manifest: RunManifest = {
    ...oldManifest,
    runId: newRunId,
    source: oldManifest.source,
    rerunOf: oldRunId,
    working,
    createdAt: now,
    tool: { name: TOOL_NAME, version: TOOL_VERSION },
    hashes: {
      contextSha256: await sha256File(store.resolve('inputs', 'context.md')),
      contextJsonSha256: await sha256File(contextJsonPath),
      diffSha256: await sha256File(store.resolve('inputs', 'diff.patch')),
      caseManifestSha256: await sha256File(caseManifestPath),
      promptSha256: await sha256File(store.resolve('inputs', 'prompt.md')),
    },
    promptPath: 'inputs/prompt.md',
  };
  await store.writeManifest(manifest);
  const state = createInitialState(newRunId, oldManifest.config.systems);
  await store.writeInitialState(state);
  await store.event('run.created', {
    mode: manifest.mode,
    source: manifest.source,
    rerunOf: oldRunId,
    base: manifest.commits.base,
    head,
    fixCommit: manifest.commits.fixCommit,
    systems: manifest.config.systems.map((system) => system.agent),
  });
  await store.event('case.rerun', {
    rerunOf: oldRunId,
    source: manifest.source,
    contextSha256: manifest.hashes.contextSha256,
    diffSha256: manifest.hashes.diffSha256,
    promptSha256: manifest.hashes.promptSha256,
  });
  return { root, store, manifest, caseManifest, state };
}

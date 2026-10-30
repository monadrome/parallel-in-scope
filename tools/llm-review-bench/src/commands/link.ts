import { ValidationError } from '../util/errors.js';
import { readTextFile, ensureDir } from '../util/fsx.js';
import { nowIso } from '../util/time.js';
import { commitInfo, isAncestor, resolveCommit, showCommitDiff } from '../util/git.js';
import { candidateGoldFromFix, finalizeGold, parseGoldInput } from '../evaluation/gold.js';
import { readCaseManifest, verifyInputs } from '../case/prepare.js';
import { openRun, printJson, resolveBenchRoot, runSummary } from './shared.js';
import { finalizeRun } from './shared.js';

export interface LinkOptions {
  output?: string;
  gold?: string;
  confirmedBy?: string;
  json?: boolean;
}

export async function linkCommand(runId: string, fixRevision: string, options: LinkOptions): Promise<number> {
  const root = resolveBenchRoot(options.output);
  const { store, manifest } = await openRun(root, runId);
  await verifyInputs(store);
  const repo = manifest.repository.root;
  const fix = await resolveCommit(repo, fixRevision);
  if (!(await isAncestor(repo, manifest.commits.head, fix))) {
    throw new ValidationError(
      `fix commit ${fix} does not descend from run head ${manifest.commits.head}; link requires a later fix`,
    );
  }
  const caseManifest = await readCaseManifest(store);
  const linkedCase = {
    ...caseManifest,
    commits: { ...caseManifest.commits, fixCommit: fix },
  };
  const version = await store.nextGoldVersion();
  let gold;
  if (options.gold !== undefined) {
    const parsed = parseGoldInput(await readTextFile(options.gold, 'gold input'), options.gold, {
      caseManifest: linkedCase,
      confirmedBy: options.confirmedBy ?? null,
    });
    gold = finalizeGold(parsed, runId, version, nowIso(), linkedCase);
  } else {
    const [diff, info] = await Promise.all([
      showCommitDiff(repo, fix),
      commitInfo(repo, fix),
    ]);
    gold = candidateGoldFromFix(runId, version, nowIso(), linkedCase, fix, info.subject, diff);
  }
  await ensureDir(store.goldDir);
  await store.writeGoldVersion(version, gold);
  await store.event('gold.linked', {
    version,
    fixCommit: fix,
    status: gold.status,
    labels: gold.labels.length,
    source: gold.provenance.source,
  });
  const result = await finalizeRun(store, manifest, await store.readState(), {
    createGold: false,
    render: true,
  });
  const state = await store.readState();
  const summary = {
    ...runSummary(store, manifest, result, state),
    linkedFix: fix,
    goldVersion: gold.version,
    goldStatus: gold.status,
    goldLabels: gold.labels.length,
  };
  if (options.json === true) {
    printJson(summary);
  } else {
    process.stdout.write(`linked ${runId} to fix ${fix}\n`);
    process.stdout.write(`gold: v${gold.version} ${gold.status} labels=${gold.labels.length}${gold.status === 'candidate' ? ' (confirm with apply-judgment --gold)' : ''}\n`);
    if (result.reports !== null) {
      process.stdout.write(`report: ${result.reports.html}\n`);
    }
  }
  return 0;
}

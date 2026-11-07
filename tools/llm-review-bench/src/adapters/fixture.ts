import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ValidationError } from '../util/errors.js';
import { pathExists } from '../util/fsx.js';
import type { SystemConfig, SystemIdentity } from '../protocol/types.js';
import type { ReviewAdapter, AdapterContext, AdapterPlan, ExecutionView } from './types.js';
import { parseStandardOutput } from './common.js';

const WORKER = fileURLToPath(new URL('./fixture-worker.js', import.meta.url));

export class FixtureAdapter implements ReviewAdapter {
  readonly name = 'fixture';

  async identity(system: SystemConfig): Promise<SystemIdentity> {
    return {
      agent: system.agent,
      version: system.version !== 'unknown' ? system.version : `node ${process.version}`,
      provider: system.provider ?? 'fixture',
      model: system.model ?? 'fixture-model',
      revision: system.revision,
      effort: system.effort,
    };
  }

  buildPlan(context: AdapterContext): AdapterPlan {
    const fixture = context.system.fixture;
    if (fixture === null) {
      throw new ValidationError('fixture adapter requires system.fixture');
    }
    const fixturePath = path.resolve(fixture);
    return {
      command: context.system.command ?? process.execPath,
      args: [WORKER, fixturePath],
      cwd: context.snapshotDir,
      resultFile: null,
      env: {
        LLM_REVIEW_BENCH_FIXTURE: fixturePath,
        LLM_REVIEW_BENCH_OUTPUT_DIR: context.agentOutputDir,
      },
    };
  }

  async parse(
    context: AdapterContext,
    plan: AdapterPlan,
    execution: ExecutionView,
    maxBytes: number,
  ) {
    return parseStandardOutput(context, plan, execution, maxBytes);
  }
}

export async function assertFixtureFile(system: SystemConfig): Promise<void> {
  if (system.fixture === null) {
    throw new ValidationError(`system ${system.agent}: fixture path is required`);
  }
  if (!(await pathExists(path.resolve(system.fixture)))) {
    throw new ValidationError(`system ${system.agent}: fixture file ${system.fixture} does not exist`);
  }
}

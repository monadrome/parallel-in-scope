import type { SystemConfig, SystemIdentity } from '../protocol/types.js';
import type { ReviewAdapter, AdapterContext, AdapterPlan, ExecutionView } from './types.js';
import { parseStandardOutput, resolveIdentity } from './common.js';

export class KimiAdapter implements ReviewAdapter {
  readonly name = 'kimi';

  async identity(system: SystemConfig): Promise<SystemIdentity> {
    return resolveIdentity(system, 'kimi');
  }

  buildPlan(context: AdapterContext): AdapterPlan {
    const system = context.system;
    const args = ['--output-format', 'stream-json'];
    if (system.model !== null) {
      args.push('--model', system.model);
    }
    args.push(...system.extraArgs, '-p', context.promptText);
    return {
      command: system.command ?? 'kimi',
      args,
      cwd: context.snapshotDir,
      resultFile: null,
      env: {},
    };
  }

  async parse(
    context: AdapterContext,
    plan: AdapterPlan,
    execution: ExecutionView,
    maxBytes: number,
  ) {
    return parseStandardOutput(context, plan, execution, maxBytes, { streamJson: true });
  }
}

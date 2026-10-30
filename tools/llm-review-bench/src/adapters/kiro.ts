import type { SystemConfig, SystemIdentity } from '../protocol/types.js';
import type { ReviewAdapter, AdapterContext, AdapterPlan, ExecutionView } from './types.js';
import { parseStandardOutput, resolveIdentity } from './common.js';

export class KiroAdapter implements ReviewAdapter {
  readonly name = 'kiro';

  async identity(system: SystemConfig): Promise<SystemIdentity> {
    return resolveIdentity(system, 'kiro-cli');
  }

  buildPlan(context: AdapterContext): AdapterPlan {
    const system = context.system;
    const args = ['chat', '--v3', '--no-interactive', '--trust-all-tools'];
    if (system.model !== null) {
      args.push('--model', system.model);
    }
    args.push(...system.extraArgs, context.promptText);
    return {
      command: system.command ?? 'kiro-cli',
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
    return parseStandardOutput(context, plan, execution, maxBytes);
  }
}

import type { SystemConfig, SystemIdentity } from '../protocol/types.js';
import type { ReviewAdapter, AdapterContext, AdapterPlan, ExecutionView } from './types.js';
import { parseStandardOutput, resolveIdentity } from './common.js';

export class OcrAdapter implements ReviewAdapter {
  readonly name = 'ocr';

  async identity(system: SystemConfig): Promise<SystemIdentity> {
    return resolveIdentity(system, 'ocr');
  }

  buildPlan(context: AdapterContext): AdapterPlan {
    const system = context.system;
    const args = [
      'review',
      '--commit',
      context.head,
      '--format',
      'json',
      '--audience',
      'agent',
      '--background-file',
      context.contextPath,
    ];
    if (system.effort !== null) {
      args.push('--effort', system.effort);
    }
    if (system.model !== null) {
      args.push('--model', system.model);
    }
    args.push(...system.extraArgs);
    return {
      command: system.command ?? 'ocr',
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

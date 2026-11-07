import path from 'node:path';
import type { SystemConfig, SystemIdentity, Usage } from '../protocol/types.js';
import type { ReviewAdapter, AdapterContext, AdapterPlan, ExecutionView } from './types.js';
import { parseStandardOutput, readFileTail, resolveIdentity } from './common.js';
import { mergeUsage } from '../protocol/usage.js';

export class CodexAdapter implements ReviewAdapter {
  readonly name = 'codex';

  async identity(system: SystemConfig): Promise<SystemIdentity> {
    return resolveIdentity(system, 'codex');
  }

  buildPlan(context: AdapterContext): AdapterPlan {
    const system = context.system;
    const command = system.command ?? 'codex';
    const resultFile = path.join(context.agentOutputDir, 'last-message.json');
    const args = [
      'exec',
      '-C',
      context.snapshotDir,
      '--sandbox',
      'read-only',
      '--add-dir',
      context.agentOutputDir,
      '--output-last-message',
      resultFile,
    ];
    if (system.model !== null) {
      args.push('--model', system.model);
    }
    if (system.effort !== null) {
      args.push('-c', `model_reasoning_effort=${system.effort}`);
    }
    args.push(...system.extraArgs, context.promptText);
    return { command, args, cwd: context.snapshotDir, resultFile, env: {} };
  }

  async parse(
    context: AdapterContext,
    plan: AdapterPlan,
    execution: ExecutionView,
    maxBytes: number,
  ) {
    const parsed = await parseStandardOutput(context, plan, execution, maxBytes);
    if (parsed.usage?.totalTokens !== null && parsed.usage?.totalTokens !== undefined) {
      return parsed;
    }
    const usage = await extractCodexUsage(execution, maxBytes);
    return usage === null ? parsed : { ...parsed, usage: mergeUsage(parsed.usage, usage) };
  }
}

export async function extractCodexUsage(execution: ExecutionView, maxBytes: number): Promise<Usage | null> {
  for (const file of [execution.stdoutPath, execution.stderrPath]) {
    const tail = await readFileTail(file, Math.min(maxBytes, 256 * 1024));
    if (tail === null) {
      continue;
    }
    const matches = [...tail.matchAll(/(?:^|\n)[ \t]*tokens used[ \t]*(?::[ \t]*|\r?\n[ \t]*)([\d,]+)/gi)];
    const match = matches[matches.length - 1];
    if (match?.[1] !== undefined) {
      const total = Number(match[1].replace(/,/g, ''));
      if (Number.isFinite(total) && total >= 0) {
        return { inputTokens: null, outputTokens: null, totalTokens: total, cost: null, currency: null };
      }
    }
  }
  return null;
}

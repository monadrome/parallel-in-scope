import type { ParseResult, SystemConfig, SystemIdentity } from '../protocol/types.js';

export interface AdapterContext {
  runId: string;
  system: SystemConfig;
  attempt: number;
  snapshotDir: string;
  runDir: string;
  agentOutputDir: string;
  contextPath: string;
  diffPath: string;
  promptPath: string;
  promptText: string;
  base: string;
  head: string;
}

export interface AdapterPlan {
  command: string;
  args: string[];
  cwd: string;
  resultFile: string | null;
  env: Record<string, string | null>;
}

export interface ExecutionView {
  exitCode: number | null;
  timedOut: boolean;
  stdoutPath: string;
  stderrPath: string;
}

export interface ReviewAdapter {
  readonly name: string;
  identity(system: SystemConfig): Promise<SystemIdentity>;
  buildPlan(context: AdapterContext): AdapterPlan;
  parse(context: AdapterContext, plan: AdapterPlan, execution: ExecutionView, maxBytes: number): Promise<ParseResult>;
}

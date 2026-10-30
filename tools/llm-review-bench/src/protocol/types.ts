export const RUN_SCHEMA = 'llm-review-bench/run/v1';
export const CASE_SCHEMA = 'llm-review-bench/case/v1';
export const REVIEW_SCHEMA = 'llm-review-bench/review/v1';
export const STATE_SCHEMA = 'llm-review-bench/state/v1';
export const EVENT_SCHEMA = 'llm-review-bench/event/v1';
export const CONTEXT_SCHEMA = 'llm-review-bench/context/v1';
export const DEDUP_SCHEMA = 'llm-review-bench/dedup/v1';
export const ADJUDICATION_SCHEMA = 'llm-review-bench/adjudication/v1';
export const GOLD_SCHEMA = 'llm-review-bench/gold/v1';
export const EVALUATION_SCHEMA = 'llm-review-bench/evaluation/v1';

export const VERDICTS = ['confirmed', 'rejected', 'neutral', 'uncertain'] as const;
export type Verdict = (typeof VERDICTS)[number];

export const SEVERITIES = ['P0', 'P1', 'P2', 'P3'] as const;
export type Severity = (typeof SEVERITIES)[number];

export const GOLD_KINDS = ['known-fix', 'regression'] as const;
export type GoldKind = (typeof GOLD_KINDS)[number];

export const GOLD_STATUSES = ['open-world', 'candidate', 'confirmed'] as const;
export type GoldStatus = (typeof GOLD_STATUSES)[number];

export const CASE_MODES = ['historical', 'live'] as const;
export type CaseMode = (typeof CASE_MODES)[number];

export const CASE_SOURCES = ['fix', 'commit', 'working'] as const;
export type CaseSource = (typeof CASE_SOURCES)[number];

export const RUN_STATUSES = ['created', 'running', 'completed', 'partial', 'failed'] as const;
export type RunStatus = (typeof RUN_STATUSES)[number];

export const STEP_STATUSES = ['pending', 'running', 'completed', 'failed', 'timed_out'] as const;
export type StepStatus = (typeof STEP_STATUSES)[number];

export interface SystemIdentity {
  agent: string;
  version: string;
  provider: string | null;
  model: string | null;
  revision: string | null;
  effort: string | null;
}

export interface SystemConfig extends SystemIdentity {
  command: string | null;
  extraArgs: string[];
  fixture: string | null;
}

export interface TimeoutConfig {
  totalSeconds: number;
  reviewSeconds: number;
  adjudicationSeconds: number;
  cleanupSeconds: number;
  killGraceSeconds: number;
}

export interface BenchmarkConfig {
  systems: SystemConfig[];
  budget: number;
  concurrency: number;
  timeouts: TimeoutConfig;
  maxOutputBytes: number;
}

export interface Finding {
  id: string;
  reviewer: string;
  attempt: number;
  file: string;
  lineStart: number;
  lineEnd: number;
  title: string;
  trigger: string;
  consequence: string;
  evidence: string;
  severity: Severity;
  confidence: number;
  suggestedFix: string;
}

export interface RawFinding {
  file: string;
  lineStart: number;
  lineEnd: number;
  title: string;
  trigger: string;
  consequence: string;
  evidence: string;
  severity: Severity;
  confidence: number;
  suggestedFix: string;
}

export interface Usage {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  cost: number | null;
  currency: string | null;
}

export interface ParseResult {
  summary: string | null;
  findings: RawFinding[];
  warnings: string[];
  usage: Usage | null;
}

export interface CommandRecord {
  file: string;
  args: string[];
  cwd: string;
}

export interface ReviewRecord {
  schemaVersion: typeof REVIEW_SCHEMA;
  runId: string;
  agent: string;
  attempt: number;
  status: 'completed' | 'failed' | 'timed_out';
  system: SystemIdentity;
  startedAt: string;
  endedAt: string;
  durationMs: number;
  exitCode: number | null;
  timedOut: boolean;
  command: CommandRecord | null;
  summary: string | null;
  findings: Finding[] | null;
  rawFindings: RawFinding[] | null;
  usage: Usage | null;
  budgetDropped: number | null;
  parseWarnings: string[];
  error: string | null;
  stdoutPath: string;
  stderrPath: string;
  messagePath: string | null;
  worktreeModified: boolean;
}

export interface WorkingSnapshot {
  baseCommit: string;
  snapshotCommit: string;
  ref: string;
}

export interface RunManifest {
  schemaVersion: typeof RUN_SCHEMA;
  runId: string;
  mode: CaseMode;
  source: CaseSource;
  rerunOf: string | null;
  working: WorkingSnapshot | null;
  createdAt: string;
  tool: { name: string; version: string };
  repository: { root: string; name: string };
  commits: { base: string; head: string; fixCommit: string | null };
  config: BenchmarkConfig;
  paths: {
    inputsDir: string;
    snapshotDir: string;
    reviewsDir: string;
    logsDir: string;
    derivedDir: string;
    reportsDir: string;
  };
  hashes: {
    contextSha256: string;
    contextJsonSha256: string;
    diffSha256: string;
    caseManifestSha256: string;
    promptSha256: string;
  };
  promptPath: string;
}

export interface CaseFileEntry {
  path: string;
  status: string;
  additions: number;
  deletions: number;
}

export interface CaseManifest {
  schemaVersion: typeof CASE_SCHEMA;
  runId: string;
  mode: CaseMode;
  source: CaseSource;
  rerunOf: string | null;
  working: WorkingSnapshot | null;
  createdAt: string;
  repository: { root: string; name: string; remote: string | null };
  commits: { base: string; head: string; fixCommit: string | null };
  range: { from: string; to: string; label: string };
  diff: { path: string; sha256: string; bytes: number; files: CaseFileEntry[] };
  context: { path: string; sha256: string; bytes: number; source: 'generated' | 'provided' };
  contextJson: { path: string; sha256: string; bytes: number };
  snapshot: { path: string; head: string; policy: 'read-only-by-policy' };
  rulesFiles: string[];
}

export interface AttemptState {
  attempt: number;
  status: StepStatus;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  exitCode: number | null;
  timedOut: boolean;
  error: string | null;
  recordPath: string;
  logBase: string;
}

export interface ReviewStepState {
  agent: string;
  status: StepStatus;
  attempts: AttemptState[];
  updatedAt: string;
}

export interface StepState {
  status: StepStatus;
  updatedAt: string;
  error: string | null;
}

export interface RunState {
  schemaVersion: typeof STATE_SCHEMA;
  runId: string;
  status: RunStatus;
  createdAt: string;
  updatedAt: string;
  steps: {
    case: StepState;
    reviews: Record<string, ReviewStepState>;
    derivation: StepState;
    evaluation: StepState;
    report: StepState;
    cleanup: StepState;
  };
  eventSeq: number;
}

export interface BenchEvent {
  schemaVersion: typeof EVENT_SCHEMA;
  seq: number;
  at: string;
  runId: string;
  type: string;
  agent: string | null;
  step: string | null;
  data: Record<string, unknown>;
}

export interface DedupCluster {
  clusterId: string;
  findingIds: string[];
  note: string | null;
}

export interface DedupRecord {
  schemaVersion: typeof DEDUP_SCHEMA;
  runId: string;
  version: number;
  createdAt: string;
  method: 'identity' | 'imported';
  note: string;
  clusters: DedupCluster[];
}

export interface AdjudicationVerdict {
  clusterId: string;
  verdict: Verdict;
  goldLabelIds: string[];
  rationale: string | null;
  confidence: number | null;
}

export interface JudgeIdentity {
  kind: 'human' | 'agent';
  name: string;
  model: string | null;
  note: string | null;
}

export interface AdjudicationRecord {
  schemaVersion: typeof ADJUDICATION_SCHEMA;
  runId: string;
  version: number;
  dedupVersion: number;
  createdAt: string;
  judge: JudgeIdentity;
  verdicts: AdjudicationVerdict[];
}

export interface GoldLabel {
  goldId: string;
  kind: GoldKind;
  file: string;
  lineStart: number;
  lineEnd: number;
  rootCause: string;
  fixMechanism: string | null;
  regressionTest: string | null;
  introducedByBuggyCommit: boolean | null;
  evidence: string | null;
}

export interface ExcludedFixScope {
  reason: string;
  rootCause: string;
  goldId: string | null;
}

export interface GoldRecord {
  schemaVersion: typeof GOLD_SCHEMA;
  runId: string;
  version: number;
  createdAt: string;
  status: GoldStatus;
  case: { fixCommit: string | null; base: string; head: string };
  provenance: {
    source: 'live' | 'fix-diff-candidate' | 'explicit-json';
    confirmedBy: string | null;
    note: string | null;
  };
  labels: GoldLabel[];
  excludedFixScope: ExcludedFixScope[];
}

export interface CountSet {
  total: number;
  adjudicated: number;
  confirmed: number;
  rejected: number;
  neutral: number;
  uncertain: number;
  pending: number;
}

export interface RateSet {
  confirmed: number | null;
  rejected: number | null;
  neutral: number | null;
  uncertain: number | null;
}

export interface RecallSet {
  knownFix: number | null;
  knownFixHits: number | null;
  knownFixEligible: number | null;
  regression: number | null;
  regressionHits: number | null;
  regressionEligible: number | null;
  reason: string | null;
}

export interface SystemMetrics {
  identity: SystemIdentity;
  status: 'completed' | 'failed' | 'timed_out' | 'not_run';
  durationMs: number | null;
  attempts: number;
  timeout: boolean;
  findings: CountSet;
  rates: RateSet;
  recall: RecallSet;
  goldHits: string[];
  usage: Usage | null;
  error: string | null;
}

export interface UnionMetrics {
  clusters: CountSet;
  findings: CountSet;
  rates: RateSet;
  findingRates: RateSet;
  recall: RecallSet;
  goldHits: string[];
}

export interface EvaluationRecord {
  schemaVersion: typeof EVALUATION_SCHEMA;
  runId: string;
  version: number;
  createdAt: string;
  inputs: {
    dedupVersion: number;
    adjudicationVersion: number | null;
    goldVersion: number | null;
    reviews: { agent: string; attempt: number; sha256: string }[];
  };
  case: {
    mode: CaseMode;
    source: CaseSource;
    rerunOf: string | null;
    openWorld: boolean;
    base: string;
    head: string;
    fixCommit: string | null;
    goldStatus: GoldStatus;
    goldEligibleLabels: number;
  };
  usage: {
    inputTokens: number | null;
    outputTokens: number | null;
    totalTokens: number | null;
    systemsWithUsage: number;
    systemsTotal: number;
    costsByCurrency: { currency: string | null; cost: number }[];
  };
  systems: Record<string, SystemMetrics>;
  union: UnionMetrics;
  aggregate: { micro: RateSet; macro: RateSet | null; systemsAdjudicated: number };
  notes: string[];
}

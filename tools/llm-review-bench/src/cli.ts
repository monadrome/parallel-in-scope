#!/usr/bin/env node
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { BenchError, UsageError, describeError } from './util/errors.js';
import { discover, formatDiscover } from './commands/discover.js';
import { resumeCommand, runCase } from './commands/run.js';
import { rerunCommand } from './commands/rerun.js';
import { linkCommand } from './commands/link.js';
import { rescoreCommand } from './commands/rescore.js';
import { reportCommand } from './commands/report.js';
import { judgmentCommand } from './commands/judgment.js';
import { cleanupCommand } from './commands/cleanup.js';
import { leaderboardCommand } from './commands/leaderboard.js';
import { printJson } from './commands/shared.js';

const USAGE = `llm-review-bench - cross-agent code review benchmark

Usage:
  llm-review-bench discover [--repo PATH] [--output DIR] [--limit N] [--json]
  llm-review-bench run --fix SHA [options]
  llm-review-bench review [--commit SHA] [--base SHA] [options]
  llm-review-bench resume RUN_ID [--output DIR] [--json]
  llm-review-bench rerun RUN_ID [--output DIR] [--json]
  llm-review-bench link --run RUN_ID --fix SHA [--gold FILE] [--output DIR] [--json]
  llm-review-bench rescore RUN_ID [--output DIR] [--json]
  llm-review-bench report RUN_ID [--output DIR] [--json]
  llm-review-bench apply-judgment RUN_ID [--clusters FILE] [--verdicts FILE] [--gold FILE] [options]
  llm-review-bench leaderboard [--output DIR] [--include-fixture] [--json]
  llm-review-bench cleanup [RUN_ID | --all] [--dry-run] [--output DIR] [--json]

Run options:
  --repo PATH                 Repository (default: current directory)
  --output DIR                Benchmark root (default: <repo>/.review-benchmark for new runs)
  --systems FILE              Systems JSON with per-system provider/model/effort/fixture/extraArgs
  --agents LIST               Comma-separated agents (codex,claude,kimi,ocr,kiro,fixture)
  --model NAME                Override model for every selected system
  --effort NAME               Override effort metadata for every selected system
  --budget N                  Maximum normalized findings per reviewer (default 20)
  --concurrency N             Reviewers running at once (default 3)
  --timeout S                 Total wall-clock budget (default 3060 = 51m)
  --review-timeout S          Reviewer phase budget (default 2700 = 45m)
  --adjudication-timeout S    Reserved adjudication budget (default 300 = 5m)
  --cleanup-timeout S         Cleanup budget (default 60 = 1m)
  --kill-grace S              SIGTERM-to-SIGKILL grace (default 10)
  --context FILE              Explicit immutable context.md for a new run
  --working                   Review uncommitted working-tree changes (review only)
  --include-fixture           Include fixture systems in leaderboard aggregation

Judgment options:
  --rescore / --no-rescore    Recompute evaluation after import (default: rescore)
  --judge-kind human|agent    Judge kind recorded with verdicts
  --judge-name NAME           Judge name (default: unspecified)
  --judge-model NAME          Judge model
  --confirmed-by NAME         Confirmer for --gold when the JSON omits it

Global options:
  --json                      Print one JSON object
  --help                      Show this help
  --debug                     Print stack traces on failure
`;

interface ParsedValues {
  [key: string]: string | boolean | undefined;
}

const OPTIONS = {
  help: { type: 'boolean' },
  json: { type: 'boolean' },
  debug: { type: 'boolean' },
  output: { type: 'string' },
  repo: { type: 'string' },
  fix: { type: 'string' },
  commit: { type: 'string' },
  base: { type: 'string' },
  context: { type: 'string' },
  working: { type: 'boolean' },
  agents: { type: 'string' },
  systems: { type: 'string' },
  model: { type: 'string' },
  effort: { type: 'string' },
  budget: { type: 'string' },
  concurrency: { type: 'string' },
  timeout: { type: 'string' },
  'review-timeout': { type: 'string' },
  'adjudication-timeout': { type: 'string' },
  'cleanup-timeout': { type: 'string' },
  'kill-grace': { type: 'string' },
  clusters: { type: 'string' },
  verdicts: { type: 'string' },
  gold: { type: 'string' },
  run: { type: 'string' },
  rescore: { type: 'boolean' },
  'no-rescore': { type: 'boolean' },
  'judge-name': { type: 'string' },
  'judge-kind': { type: 'string' },
  'judge-model': { type: 'string' },
  'confirmed-by': { type: 'string' },
  limit: { type: 'string' },
  all: { type: 'boolean' },
  'dry-run': { type: 'boolean' },
  'include-fixture': { type: 'boolean' },
} as const;

function stringValue(values: ParsedValues, key: string): string | undefined {
  const value = values[key];
  if (value === undefined || value === false) {
    return undefined;
  }
  if (typeof value !== 'string') {
    throw new UsageError(`--${key} requires a value`);
  }
  return value;
}

function intValue(values: ParsedValues, key: string, min: number, max: number): number | undefined {
  const raw = stringValue(values, key);
  if (raw === undefined) {
    return undefined;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new UsageError(`--${key} must be an integer between ${min} and ${max}, got ${raw}`);
  }
  return value;
}

function judgeKind(values: ParsedValues): 'human' | 'agent' | undefined {
  const raw = stringValue(values, 'judge-kind');
  if (raw === undefined) {
    return undefined;
  }
  if (raw !== 'human' && raw !== 'agent') {
    throw new UsageError(`--judge-kind must be human or agent, got ${raw}`);
  }
  return raw;
}

function requirePositional(positionals: string[], index: number, label: string): string {
  const value = positionals[index];
  if (value === undefined) {
    throw new UsageError(`missing ${label}`);
  }
  return value;
}

export async function main(argv = process.argv.slice(2)): Promise<number> {
  let values: ParsedValues = {};
  let positionals: string[] = [];
  try {
    const parsed = parseArgs({ args: argv, options: OPTIONS, allowPositionals: true, strict: true });
    values = parsed.values as ParsedValues;
    positionals = parsed.positionals;
  } catch (error) {
    process.stderr.write(`error: ${describeError(error)}\n\n${USAGE}`);
    return 2;
  }
  if (values['help'] === true || positionals.length === 0) {
    process.stdout.write(USAGE);
    return 0;
  }
  const command = positionals[0] as string;
  const rest = positionals.slice(1);
  const json = values['json'] === true;
  const output = stringValue(values, 'output');
  const debug = values['debug'] === true;
  try {
    switch (command) {
      case 'discover': {
        if (rest.length > 0) {
          throw new UsageError('discover does not accept positional arguments');
        }
        const result = await discover({
          repo: stringValue(values, 'repo'),
          output,
          limit: intValue(values, 'limit', 1, 200),
          json,
        });
        if (json) {
          printJson(result);
        } else {
          process.stdout.write(formatDiscover(result));
        }
        return 0;
      }
      case 'run': {
        if (rest.length > 0) {
          throw new UsageError('run does not accept positional arguments');
        }
        return await runCase('historical', {
          output,
          repo: stringValue(values, 'repo'),
          fix: stringValue(values, 'fix'),
          context: stringValue(values, 'context'),
          working: values['working'] === true,
          agents: stringValue(values, 'agents'),
          systems: stringValue(values, 'systems'),
          model: stringValue(values, 'model'),
          effort: stringValue(values, 'effort'),
          budget: intValue(values, 'budget', 0, 10_000),
          concurrency: intValue(values, 'concurrency', 1, 64),
          timeout: intValue(values, 'timeout', 1, 86_400),
          reviewTimeout: intValue(values, 'review-timeout', 1, 86_400),
          adjudicationTimeout: intValue(values, 'adjudication-timeout', 1, 86_400),
          cleanupTimeout: intValue(values, 'cleanup-timeout', 1, 86_400),
          killGrace: intValue(values, 'kill-grace', 1, 600),
          json,
        });
      }
      case 'review': {
        if (rest.length > 0) {
          throw new UsageError('review does not accept positional arguments');
        }
        return await runCase('live', {
          output,
          repo: stringValue(values, 'repo'),
          commit: stringValue(values, 'commit'),
          base: stringValue(values, 'base'),
          context: stringValue(values, 'context'),
          working: values['working'] === true,
          agents: stringValue(values, 'agents'),
          systems: stringValue(values, 'systems'),
          model: stringValue(values, 'model'),
          effort: stringValue(values, 'effort'),
          budget: intValue(values, 'budget', 0, 10_000),
          concurrency: intValue(values, 'concurrency', 1, 64),
          timeout: intValue(values, 'timeout', 1, 86_400),
          reviewTimeout: intValue(values, 'review-timeout', 1, 86_400),
          adjudicationTimeout: intValue(values, 'adjudication-timeout', 1, 86_400),
          cleanupTimeout: intValue(values, 'cleanup-timeout', 1, 86_400),
          killGrace: intValue(values, 'kill-grace', 1, 600),
          json,
        });
      }
      case 'resume': {
        const runId = requirePositional(rest, 0, 'run id');
        if (rest.length > 1) {
          throw new UsageError('resume accepts exactly one run id');
        }
        return await resumeCommand(runId, { output, json });
      }
      case 'rerun': {
        const runId = requirePositional(rest, 0, 'run id');
        if (rest.length > 1) {
          throw new UsageError('rerun accepts exactly one run id');
        }
        return await rerunCommand(runId, { output, json });
      }
      case 'link': {
        const runId = stringValue(values, 'run') ?? rest[0];
        if (runId === undefined) {
          throw new UsageError('link requires --run <id>');
        }
        const fix = stringValue(values, 'fix');
        if (fix === undefined) {
          throw new UsageError('link requires --fix <sha>');
        }
        return await linkCommand(runId, fix, {
          output,
          gold: stringValue(values, 'gold'),
          confirmedBy: stringValue(values, 'confirmed-by'),
          json,
        });
      }
      case 'rescore': {
        const runId = requirePositional(rest, 0, 'run id');
        if (rest.length > 1) {
          throw new UsageError('rescore accepts exactly one run id');
        }
        return await rescoreCommand(runId, { output, json });
      }
      case 'report': {
        const runId = requirePositional(rest, 0, 'run id');
        if (rest.length > 1) {
          throw new UsageError('report accepts exactly one run id');
        }
        return await reportCommand(runId, { output, json });
      }
      case 'apply-judgment': {
        const runId = requirePositional(rest, 0, 'run id');
        if (rest.length > 1) {
          throw new UsageError('apply-judgment accepts exactly one run id');
        }
        if (values['rescore'] === true && values['no-rescore'] === true) {
          throw new UsageError('--rescore and --no-rescore are mutually exclusive');
        }
        return await judgmentCommand(runId, {
          output,
          clusters: stringValue(values, 'clusters'),
          verdicts: stringValue(values, 'verdicts'),
          gold: stringValue(values, 'gold'),
          rescore: values['no-rescore'] === true ? false : true,
          judgeName: stringValue(values, 'judge-name'),
          judgeKind: judgeKind(values),
          judgeModel: stringValue(values, 'judge-model'),
          confirmedBy: stringValue(values, 'confirmed-by'),
          json,
        });
      }
      case 'leaderboard': {
        if (rest.length > 0) {
          throw new UsageError('leaderboard does not accept positional arguments');
        }
        return await leaderboardCommand({
          output,
          includeFixture: values['include-fixture'] === true,
          json,
        });
      }
      case 'cleanup': {
        const all = values['all'] === true;
        const runId = rest[0] ?? null;
        if (all && runId !== null) {
          throw new UsageError('cleanup accepts a run id or --all, not both');
        }
        if (!all && runId === null) {
          throw new UsageError('cleanup requires a run id or --all');
        }
        return await cleanupCommand(runId, {
          output,
          all,
          dryRun: values['dry-run'] === true,
          json,
        });
      }
      default:
        throw new UsageError(`unknown command ${JSON.stringify(command)}`);
    }
  } catch (error) {
    if (error instanceof UsageError) {
      process.stderr.write(`error: ${error.message}\n\n${USAGE}`);
      return 2;
    }
    if (error instanceof BenchError) {
      process.stderr.write(`error: ${error.message}\n`);
      if (debug && error.stack !== undefined) {
        process.stderr.write(`${error.stack}\n`);
      }
      return error.exitCode;
    }
    process.stderr.write(`error: ${describeError(error)}\n`);
    if (debug && error instanceof Error && error.stack !== undefined) {
      process.stderr.write(`${error.stack}\n`);
    }
    return 1;
  }
}

const direct = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;
if (direct) {
  main().then((code) => {
    process.exitCode = code;
  }).catch((error: unknown) => {
    process.stderr.write(`error: ${describeError(error)}\n`);
    process.exitCode = 1;
  });
}

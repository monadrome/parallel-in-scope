import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

interface FixtureSpec {
  summary?: string;
  findings?: unknown[];
  usage?: unknown;
  delayMs?: number;
  exitCode?: number;
  malformed?: boolean;
  spawnChild?: boolean;
  childPidFile?: string;
  modifyFile?: string;
  modifyContent?: string;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main(): Promise<void> {
  const fixturePath = process.argv[2];
  if (fixturePath === undefined) {
    process.stderr.write('fixture-worker: missing fixture path\n');
    process.exitCode = 2;
    return;
  }
  const spec = JSON.parse(fs.readFileSync(fixturePath, 'utf8')) as FixtureSpec;
  if (spec.spawnChild === true) {
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
      stdio: 'ignore',
    });
    child.unref();
    const pidFile = spec.childPidFile ?? path.join(path.dirname(fixturePath), 'grandchild.pid');
    fs.writeFileSync(pidFile, String(child.pid ?? ''), 'utf8');
  }
  if (spec.modifyFile !== undefined) {
    fs.writeFileSync(path.resolve(process.cwd(), spec.modifyFile), spec.modifyContent ?? 'modified by fixture\n', 'utf8');
  }
  if ((spec.delayMs ?? 0) > 0) {
    await sleep(spec.delayMs ?? 0);
  }
  if (spec.malformed === true) {
    process.stdout.write('{"findings": [ this is not json');
    process.exitCode = spec.exitCode ?? 0;
    return;
  }
  if (spec.exitCode !== undefined && spec.exitCode !== 0) {
    process.stderr.write('fixture requested a non-zero exit\n');
    process.exitCode = spec.exitCode;
    return;
  }
  process.stdout.write(JSON.stringify({
    summary: spec.summary ?? 'fixture review',
    findings: spec.findings ?? [],
    usage: spec.usage ?? null,
  }));
}

await main();

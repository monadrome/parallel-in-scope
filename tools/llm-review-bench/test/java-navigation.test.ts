import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import {
  commitAll, initRepo, makeTempDir, readText, runCli, runCliJson, runGit, writeFixture,
  writeRepoFile, writeSystems,
} from './helpers.js';

const source = 'core/src/main/java/sample';
const tests = 'core/src/test/java/sample';

function hasJdk(): boolean {
  const home = process.env.JAVA_HOME;
  const javac = home ? path.join(home, 'bin', 'javac') : 'javac';
  try {
    const version = execFileSync(javac, ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    return Number(/javac (\d+)/.exec(version)?.[1]) >= 17;
  } catch {
    return false;
  }
}

test('review navigation uses the captured range and exact overloads even with unrelated compiler errors', async (t) => {
  if (!hasJdk()) {
    t.skip('Java review navigation requires JDK 17 or newer');
    return;
  }
  const directory = await makeTempDir(t);
  const repo = path.join(directory, 'repo');
  await initRepo(repo);
  const api = [
    'package sample;',
    'class Api {',
    '    Api() {}',
    '    static String pick(String value) {',
    '        java.util.Objects.requireNonNull(value);',
    '        return value;',
    '    }',
    '    static String pick(Integer value) { return value.toString(); }',
    '    static String removed() { return "old"; }',
    '}',
    '',
  ].join('\n');
  await writeRepoFile(repo, `${source}/Api.java`, api);
  await writeRepoFile(repo, `${source}/Caller.java`, [
    'package sample;',
    'class Caller {',
    '    String stringCall = Api.pick("value");',
    '    String integerCall = Api.pick(1);',
    '    String ambiguousCall = Api.pick(null);',
    '    java.util.function.Function<String, String> reference = Api::pick;',
    '    Api instance = new Api();',
    '}',
  ].join('\n'));
  await writeRepoFile(repo, `${source}/Guide.java`, 'package sample; /** See {@link Api}. */ class Guide {}\n');
  await writeRepoFile(repo, `${tests}/OldCaller.java`, 'package sample; class OldCaller { String value = Api.removed(); }\n');
  const base = commitAll(repo, 'before the API change');
  await writeRepoFile(repo, `${source}/Api.java`, api
    .replace('Api() {}', 'Api() { System.nanoTime(); }')
    .replace('        java.util.Objects.requireNonNull(value);\n', '')
    .replace('    static String removed() { return "old"; }\n', ''));
  const head = commitAll(repo, 'change constructor, remove validation and remove an overload');
  await writeRepoFile(repo, `${source}/Caller.java`, 'package sample; class Caller {}\n');
  const dirty = runGit(repo, ['status', '--porcelain']);
  const fixture = await writeFixture(directory, 'fixture.json', { findings: [] });
  const systems = await writeSystems(directory, fixture);
  const output = path.join(directory, 'runs');
  const { json } = await runCliJson<{ runDir: string; runId: string }>([
    'review', '--repo', repo, '--base', base, '--commit', head, '--java-navigation', '--systems', systems,
    '--output', output, '--json',
  ], { timeoutMs: 60_000 });
  const context = await readText(path.join(json.runDir, 'inputs/context.md'));
  assert.match(context, /Head .*partial .*Base .*partial/);
  assert.match(context, /Guide\.java:1.*head, Javadoc.*sample\.Guide.*sample\.Api/);
  assert.match(context, /Caller\.java:3.*head, call.*sample\.Api#pick\(java\.lang\.String\)/);
  assert.match(context, /Caller\.java:6.*head, method reference.*sample\.Api#pick\(java\.lang\.String\)/);
  assert.match(context, /Caller\.java:7.*head, new.*sample\.Api#<init>\(\)/);
  assert.match(context, /OldCaller\.java:1.*base, call.*sample\.Api#removed\(\)/);
  assert.doesNotMatch(context, /Caller\.java:[45].*head, call/);
  assert.equal(runGit(repo, ['status', '--porcelain']), dirty);
  assert.doesNotMatch(runGit(repo, ['worktree', 'list', '--porcelain']), /lrb-java-/);
  const prompt = await readText(path.join(json.runDir, 'inputs/prompt.md'));
  assert.ok(prompt.endsWith(context));
  const rerun = await runCliJson<{ runDir: string }>(['rerun', json.runId, '--output', output, '--json']);
  assert.equal(await readText(path.join(rerun.json.runDir, 'inputs/context.md')), context);
});

test('an unavailable JDK leaves the requested navigation visibly incomplete', async (t) => {
  const directory = await makeTempDir(t);
  const repo = path.join(directory, 'repo');
  await initRepo(repo);
  await writeRepoFile(repo, `${source}/Api.java`, 'package sample; class Api {}\n');
  const base = commitAll(repo, 'add API');
  await writeRepoFile(repo, `${source}/Api.java`, 'package sample; class Api { void run() {} }\n');
  const head = commitAll(repo, 'add a method');
  const fixture = await writeFixture(directory, 'fixture.json', { findings: [] });
  const systems = await writeSystems(directory, fixture);
  const contextFile = await writeFixture(directory, 'context.md', 'custom review objective');
  const { json } = await runCliJson<{ runDir: string }>([
    'review', '--repo', repo, '--base', base, '--commit', head, '--java-navigation', '--systems', systems,
    '--context', contextFile, '--output', path.join(directory, 'runs'), '--json',
  ], { env: { JAVA_HOME: path.join(directory, 'missing-jdk') } });
  const context = await readText(path.join(json.runDir, 'inputs/context.md'));
  assert.match(context, /custom review objective[\s\S]*Java review navigation/);
  assert.match(context, /Head .*unavailable .*Base .*unavailable/);

  const rejected = await runCli(['review', '--java-classpath', 'classpath']);
  assert.equal(rejected.code, 2);
  assert.match(rejected.stderr, /--java-classpath requires --java-navigation/);
});

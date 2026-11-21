import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { removePath } from '../util/fsx.js';
import { git, worktreeAdd, worktreeRemove } from '../util/git.js';
import { shortSha } from '../util/ids.js';

const execFileAsync = promisify(execFile);
const helper = fileURLToPath(new URL('../java/ReviewNavigation.java', import.meta.url));
const roots = ['core/src/main/java/', 'core/src/test/java/'];
const fileLimit = 12;
const callLimit = 24;
const heading = '## Java review navigation';

type Side = 'head' | 'base';
type Span = readonly [start: number, end: number];
type Declaration = { kind: string; file: string; start: number; end: number; id: string };
type Reference = {
  kind: string;
  file: string;
  line: number;
  owner: string;
  target: string;
  member: string;
};
type Index = { declarations: Declaration[]; references: Reference[]; errors: number | null };

export interface JavaNavigationOptions {
  classpathFile: string | null;
}

interface Input {
  repo: string;
  snapshot: string;
  base: string;
  head: string;
  options: JavaNavigationOptions;
}

function isSource(file: string): boolean {
  return roots.some((root) => file.startsWith(root)) && /^[^\r\n\t]+\.java$/.test(file);
}

function parseChangedSpans(diff: string): Record<Side, Span[]> {
  const ranges: Record<Side, Span[]> = { head: [], base: [] };
  for (const match of diff.matchAll(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/gm)) {
    for (const [side, offset] of [['base', 1], ['head', 3]] as const) {
      const start = Number(match[offset]);
      const length = Number(match[offset + 1] ?? 1);
      if (length > 0) {
        ranges[side].push([start, start + length - 1]);
      }
    }
  }
  return ranges;
}

async function readChanges(input: Input): Promise<Map<string, Record<Side, Span[]>>> {
  const paths = (await git(input.repo, [
    'diff', '--name-only', '-z', '--no-renames', input.base, input.head, '--', ...roots,
  ])).split('\0').filter(isSource);
  const changes = new Map<string, Record<Side, Span[]>>();
  for (const file of paths) {
    const diff = await git(input.repo, [
      'diff', '--no-color', '--no-ext-diff', '--no-textconv', '--no-renames', '--unified=0',
      input.base, input.head, '--', `:(literal)${file}`,
    ]);
    changes.set(file, parseChangedSpans(diff));
  }
  return changes;
}

async function writeClasspath(option: string | null, directory: string): Promise<string> {
  const file = path.join(directory, 'classpath');
  const content = option === null ? '' : await fs.readFile(option, 'utf8');
  const entries = content.trim().split(path.delimiter).filter((entry) => entry.length > 0);
  if (entries.some((entry) => /[\r\n]/.test(entry))) {
    throw new Error('Java navigation classpath must be a single path-separated line');
  }
  await fs.writeFile(file, entries.map((entry) => path.resolve(entry)).join('\n'), 'utf8');
  return file;
}

function parseIndex(raw: string): Index {
  const result: Index = { declarations: [], references: [], errors: null };
  for (const line of raw.trimEnd().split('\n')) {
    const [record, kind, file, start, endOrOwner, idOrTarget, member] = line.split('\t');
    if (record === 'errors' && /^\d+$/.test(kind ?? '')) {
      result.errors = Number(kind);
    } else if (record === 'declaration') {
      result.declarations.push({ kind, file, start: Number(start), end: Number(endOrOwner), id: idOrTarget });
    } else if (record === 'reference') {
      result.references.push({ kind, file, line: Number(start), owner: endOrOwner, target: idOrTarget, member: member ?? '' });
    }
  }
  return result;
}

async function indexSnapshot(root: string, directory: string, side: Side, classpath: string): Promise<Index> {
  const files = (await git(root, ['ls-files', '-z', '--', ...roots])).split('\0').filter(isSource);
  const trackedSources: string[] = [];
  for (const file of files) {
    if ((await fs.lstat(path.join(root, file))).isFile()) {
      trackedSources.push(file);
    }
  }
  if (trackedSources.length === 0) {
    return { declarations: [], references: [], errors: 0 };
  }
  const sourceList = path.join(directory, `${side}-sources`);
  await fs.writeFile(sourceList, trackedSources.join('\n'), 'utf8');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  try {
    const { stdout } = await execFileAsync(java, ['--source', '17', helper, root, sourceList, classpath], {
      cwd: directory,
      encoding: 'utf8',
      timeout: 45_000,
      maxBuffer: 32 * 1024 * 1024,
    });
    return parseIndex(stdout);
  } catch {
    return { declarations: [], references: [], errors: null };
  }
}

function code(value: string): string {
  return `\`${value.replaceAll('\`', '\\`')}\``;
}

function changedIds(index: Index, ranges: Map<string, Record<Side, Span[]>>, side: Side, kind: string): Set<string> {
  return new Set(index.declarations
    .filter((decl) => decl.kind === kind && ranges.get(decl.file)?.[side].some(
      ([start, end]) => decl.start <= end && decl.end >= start,
    ))
    .map((decl) => decl.id));
}

function classReferences(
  indexes: Record<Side, Index>, changes: Map<string, Record<Side, Span[]>>,
): string[] {
  const changedTypes = new Set([
    ...changedIds(indexes.head, changes, 'head', 'type'),
    ...changedIds(indexes.base, changes, 'base', 'type'),
  ]);
  const files = new Map<string, { side: Side; reference: Reference }>();
  for (const side of ['head', 'base'] as const) {
    for (const ref of indexes[side].references) {
      if (!changedTypes.has(ref.target) || changes.has(ref.file)) {
        continue;
      }
      const found = files.get(ref.file);
      if (found === undefined || (found.side === side && found.reference.kind === 'Javadoc' && ref.kind !== 'Javadoc')) {
        files.set(ref.file, { side, reference: ref });
      }
    }
  }
  return [...files.values()].map(({ side, reference: ref }) =>
    `- ${code(ref.file + ':' + ref.line)} (${side}, ${ref.kind}) — ${code(ref.owner)} → ${code(ref.target)}`);
}

function callReferences(
  indexes: Record<Side, Index>, changes: Map<string, Record<Side, Span[]>>,
): string[] {
  const touched = new Set([
    ...changedIds(indexes.head, changes, 'head', 'member'),
    ...changedIds(indexes.base, changes, 'base', 'member'),
  ]);
  const declarationsAtHead = new Set(indexes.head.declarations.map((decl) => decl.id));
  const sites = new Map<string, string>();
  for (const side of ['head', 'base'] as const) {
    for (const ref of indexes[side].references) {
      if (!touched.has(ref.member) || (side === 'base' && declarationsAtHead.has(ref.member))) {
        continue;
      }
      const location = ref.file + ':' + ref.line;
      sites.set(`${side}\0${location}\0${ref.kind}\0${ref.member}`,
        `- ${code(location)} (${side}, ${ref.kind}) → ${code(ref.member)}`);
    }
  }
  return [...sites.values()];
}

function section(title: string, lines: string[], limit: number): string {
  const entries = lines.slice(0, limit);
  if (lines.length > limit) {
    entries.push(`- ${lines.length - limit} additional locations omitted`);
  }
  return [`### ${title}`, '', ...(entries.length ? entries : ['No resolved matches.'])].join('\n');
}

function status(index: Index): string {
  if (index.errors === null) {
    return 'unavailable (check JAVA_HOME or PATH for JDK 17+ and the classpath)';
  }
  return index.errors === 0 ? 'no compiler errors' : `partial (${index.errors} compiler errors)`;
}

function renderNavigation(
  input: Input, indexes: Record<Side, Index>, changes: Map<string, Record<Side, Span[]>>,
): string {
  return [
    heading,
    '',
    `Head ${code(shortSha(input.head))}: ${status(indexes.head)}. Base ${code(shortSha(input.base))}: ${status(indexes.base)}.`,
    input.options.classpathFile === null ? 'Classpath: JDK and captured source only.' : 'Classpath: supplied dependency file and captured source.',
    'These references guide reading the full diff. Call targets are compile-time declarations.',
    '',
    section('Files outside the diff that reference affected types', classReferences(indexes, changes), fileLimit),
    '',
    section('Calls to affected method and constructor declarations', callReferences(indexes, changes), callLimit),
    '',
  ].join('\n');
}

export async function generateJavaNavigation(input: Input): Promise<string> {
  const changes = await readChanges(input);
  if (changes.size === 0) {
    return `${heading}\n\nNo changes to core Java source or tests.\n`;
  }
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'lrb-java-'));
  const baseDirectory = path.join(directory, 'base');
  let baseAdded = false;
  try {
    const classpath = await writeClasspath(input.options.classpathFile, directory);
    await worktreeAdd(input.repo, baseDirectory, input.base);
    baseAdded = true;
    const [headResult, baseResult] = await Promise.allSettled([
      indexSnapshot(input.snapshot, directory, 'head', classpath),
      indexSnapshot(baseDirectory, directory, 'base', classpath),
    ]);
    if (headResult.status === 'rejected') {
      throw headResult.reason;
    }
    if (baseResult.status === 'rejected') {
      throw baseResult.reason;
    }
    return renderNavigation(input, { head: headResult.value, base: baseResult.value }, changes);
  } finally {
    try {
      if (baseAdded) {
        await worktreeRemove(input.repo, baseDirectory);
      }
    } finally {
      await removePath(directory);
    }
  }
}

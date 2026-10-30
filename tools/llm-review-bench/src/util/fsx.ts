import { createHash, randomUUID } from 'node:crypto';
import { constants as fsConstants } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { ImmutableWriteError, NotFoundError, ValidationError, errorCode } from './errors.js';
import { parseStrictJson, stringifyJson } from './json.js';

export async function ensureDir(dir: string): Promise<void> {
  await fs.mkdir(dir, { recursive: true });
}

export async function pathExists(target: string): Promise<boolean> {
  try {
    await fs.access(target);
    return true;
  } catch (error) {
    if (errorCode(error) === 'ENOENT') {
      return false;
    }
    throw error;
  }
}

async function fsyncDir(dir: string): Promise<void> {
  let handle: fs.FileHandle | undefined;
  try {
    handle = await fs.open(dir, 'r');
    await handle.sync();
  } catch {
    return;
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

export async function atomicWriteFile(
  file: string,
  data: string | Buffer,
  options: { overwrite?: boolean; mode?: number } = {},
): Promise<void> {
  const overwrite = options.overwrite ?? true;
  const dir = path.dirname(file);
  await ensureDir(dir);
  const temporary = path.join(dir, `.tmp-${process.pid}-${randomUUID()}`);
  let handle: fs.FileHandle | undefined;
  try {
    handle = await fs.open(temporary, 'wx', options.mode ?? 0o644);
    await handle.writeFile(data);
    await handle.sync();
    await handle.close();
    handle = undefined;
    if (overwrite) {
      await fs.rename(temporary, file);
    } else {
      try {
        await fs.link(temporary, file);
      } catch (error) {
        if (errorCode(error) === 'EEXIST') {
          throw new ImmutableWriteError(file);
        }
        try {
          await fs.copyFile(temporary, file, fsConstants.COPYFILE_EXCL);
        } catch (copyError) {
          if (errorCode(copyError) === 'EEXIST') {
            throw new ImmutableWriteError(file);
          }
          throw copyError;
        }
      }
      await fs.unlink(temporary);
    }
    await fsyncDir(dir);
  } catch (error) {
    await handle?.close().catch(() => undefined);
    await fs.unlink(temporary).catch(() => undefined);
    throw error;
  }
}

export async function writeImmutable(file: string, data: string | Buffer): Promise<void> {
  await atomicWriteFile(file, data, { overwrite: false });
}

export async function atomicWriteJson(file: string, value: unknown, overwrite = true): Promise<void> {
  await atomicWriteFile(file, stringifyJson(value), { overwrite });
}

export async function writeJsonImmutable(file: string, value: unknown): Promise<void> {
  await atomicWriteJson(file, value, false);
}

export async function readTextFile(file: string, label = file): Promise<string> {
  try {
    return await fs.readFile(file, 'utf8');
  } catch (error) {
    if (errorCode(error) === 'ENOENT') {
      throw new NotFoundError(`${label} does not exist at ${file}`);
    }
    throw error;
  }
}

export async function readJsonFile<T>(file: string, label = file): Promise<T> {
  const text = await readTextFile(file, label);
  return parseStrictJson(text, label) as T;
}

export async function sha256File(file: string): Promise<string> {
  const hash = createHash('sha256');
  hash.update(await fs.readFile(file));
  return hash.digest('hex');
}

export function sha256Text(text: string): string {
  return createHash('sha256').update(text).digest('hex');
}

export interface VersionedFile {
  version: number;
  path: string;
}

export async function listVersions(dir: string): Promise<VersionedFile[]> {
  let names: string[];
  try {
    names = await fs.readdir(dir);
  } catch (error) {
    if (errorCode(error) === 'ENOENT') {
      return [];
    }
    throw error;
  }
  const versions: VersionedFile[] = [];
  for (const name of names) {
    const match = /^v(\d+)\.json$/.exec(name);
    if (match?.[1] !== undefined) {
      versions.push({ version: Number(match[1]), path: path.join(dir, name) });
    }
  }
  versions.sort((left, right) => left.version - right.version);
  return versions;
}

export async function nextVersionNumber(dir: string): Promise<number> {
  const versions = await listVersions(dir);
  return (versions[versions.length - 1]?.version ?? 0) + 1;
}

export async function writeNextVersion(dir: string, value: unknown): Promise<VersionedFile> {
  await ensureDir(dir);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const version = await nextVersionNumber(dir);
    const file = path.join(dir, `v${version}.json`);
    try {
      await writeJsonImmutable(file, value);
      return { version, path: file };
    } catch (error) {
      if (!(error instanceof ImmutableWriteError)) {
        throw error;
      }
    }
  }
  throw new ValidationError(`could not allocate a new version in ${dir} after 5 attempts`);
}

export async function appendLine(file: string, line: string): Promise<void> {
  await ensureDir(path.dirname(file));
  const handle = await fs.open(file, 'a', 0o644);
  try {
    await handle.writeFile(line.endsWith('\n') ? line : `${line}\n`);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

export async function readLines(file: string): Promise<string[]> {
  let text: string;
  try {
    text = await fs.readFile(file, 'utf8');
  } catch (error) {
    if (errorCode(error) === 'ENOENT') {
      return [];
    }
    throw error;
  }
  return text.split('\n').filter((line) => line.length > 0);
}

export async function removePath(target: string): Promise<void> {
  await fs.rm(target, { recursive: true, force: true });
}

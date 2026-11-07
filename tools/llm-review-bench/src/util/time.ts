export function nowIso(): string {
  return new Date().toISOString();
}

export function monotonicMs(): number {
  return Number(process.hrtime.bigint() / 1_000_000n);
}

export function utcStamp(date = new Date()): string {
  const pad = (value: number): string => value.toString().padStart(2, '0');
  return [
    date.getUTCFullYear(),
    pad(date.getUTCMonth() + 1),
    pad(date.getUTCDate()),
    '-',
    pad(date.getUTCHours()),
    pad(date.getUTCMinutes()),
    pad(date.getUTCSeconds()),
  ].join('');
}

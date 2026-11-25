"""Report, per PIT run, its wall-clock span and how long the host slept during it.

Usage: pit_sleep.py <sleep-wake.txt> <run-meta.txt>...

The sleep/wake file holds `pmset -g log` events as "<date> <time> <Sleep|Wake|DarkWake>" in the
host's local time (UTC-8 here). A sleep interval runs from an "Entering Sleep" event to the next
wake event. Each run-meta file supplies "started:" and "finished:" UTC timestamps.
"""
import datetime
import sys
from pathlib import Path

UTC_OFFSET = datetime.timedelta(hours=8)
events = []
for line in open(sys.argv[1]):
    day, time, kind = line.split()
    events.append((datetime.datetime.strptime(f"{day} {time}", "%Y-%m-%d %H:%M:%S") + UTC_OFFSET, kind))


def asleep(start, end):
    total, count, began = 0.0, 0, None
    for stamp, kind in events:
        if kind == "Sleep":
            began = stamp
        elif began is not None:
            low, high = max(began, start), min(stamp, end)
            if high > low:
                total += (high - low).total_seconds()
                count += 1
            began = None
    return count, int(total)


for meta in sys.argv[2:]:
    fields = dict(line.split(": ", 1) for line in open(meta).read().splitlines() if ": " in line)
    if "finished" not in fields:
        print(f"{Path(meta).stem}: unfinished")
        continue
    parse = lambda key: datetime.datetime.strptime(fields[key].split()[0], "%Y-%m-%dT%H:%M:%SZ")
    start, end = parse("started"), parse("finished")
    count, total = asleep(start, end)
    print(f"{Path(meta).stem}: {start:%Y-%m-%dT%H:%M:%S}Z..{end:%H:%M:%S}Z "
          f"span={int((end - start).total_seconds())}s sleeps={count} asleep={total}s")

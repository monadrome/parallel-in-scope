"""Compare PIT results of base and head by (class, method, signature, mutator, description, ordinal).

Lines shift when code is removed, so mutants are matched by their identity within a method and by
their order of appearance (line, then index) among identical identities.
"""
import collections, sys, xml.etree.ElementTree as ET

def load(paths):
    groups = collections.defaultdict(list)
    for path in paths:
        for m in ET.parse(path).getroot().iter("mutation"):
            key = (m.findtext("mutatedClass"), m.findtext("mutatedMethod"), m.findtext("methodDescription"),
                   m.findtext("mutator"), m.findtext("description"))
            groups[key].append((int(m.findtext("lineNumber")), m.findtext("indexes/index") or "", m.get("status"),
                                (m.findtext("killingTest") or "")))
    out = {}
    for key, items in groups.items():
        for ordinal, item in enumerate(sorted(items)):
            out[key + (ordinal,)] = item
    return out

sep = sys.argv.index("--")
base, head = load(sys.argv[1:sep]), load(sys.argv[sep + 1:])
short = lambda k: f"{k[0].rsplit('.', 1)[-1]}.{k[1]} {k[3].rsplit('.', 1)[-1]} #{k[5]} '{k[4][:70]}'"
regressions, changed, removed, added = [], [], [], []
for key, (line, _, status, _) in base.items():
    if key not in head:
        removed.append((key, line, status))
        continue
    hline, _, hstatus, _ = head[key]
    if status != hstatus:
        (regressions if status == "KILLED" else changed).append((key, line, status, hline, hstatus))
for key, (line, _, status, _) in head.items():
    if key not in base:
        added.append((key, line, status))
print(f"base={len(base)} head={len(head)} matched={len(base) - len(removed)} removed={len(removed)} added={len(added)}")
print(f"KILLED at base, not KILLED at head: {len(regressions)}")
for key, line, status, hline, hstatus in regressions:
    print(f"  REGRESSION {short(key)} base:{line}:{status} head:{hline}:{hstatus}")
print(f"other status changes: {len(changed)}")
for key, line, status, hline, hstatus in changed:
    print(f"  CHANGE {short(key)} base:{line}:{status} head:{hline}:{hstatus}")
print("removed-at-head by class:", dict(collections.Counter(k[0].rsplit('.', 1)[-1].split('$')[0] for k, _, _ in removed)))
print("removed non-KILLED at base:", [f"{short(k)}:{s}" for k, _, s in removed if s != "KILLED"][:40])
print("added-at-head by class:", dict(collections.Counter(k[0].rsplit('.', 1)[-1].split('$')[0] for k, _, _ in added)))
for key, line, status in added:
    if status != "KILLED":
        print(f"  ADDED-NONKILLED {short(key)} head:{line}:{status}")

"""Print each listed method's non-KILLED mutants with their status in every given PIT report.

Usage: pit_status_table.py <Class.method>[,<Class.method>...] <label>=<mutations.xml[.gz]>...

Mutants are matched by class, method, mutator, description, and order of appearance, so the
table lines up the same mutant across revisions whose line numbers differ.
"""
import collections
import gzip
import sys
import xml.etree.ElementTree as ET

wanted = set(sys.argv[1].split(","))
reports = [arg.split("=", 1) for arg in sys.argv[2:]]
table = collections.defaultdict(dict)
for label, path in reports:
    source = gzip.open(path) if path.endswith(".gz") else open(path, "rb")
    seen = collections.Counter()
    for mutant in ET.parse(source).getroot():
        cls = mutant.findtext("mutatedClass").rsplit(".", 1)[1]
        method = mutant.findtext("mutatedMethod")
        if f"{cls.split('$')[0]}.{method}" not in wanted and f"{cls}.{method}" not in wanted:
            continue
        key = (cls, method, mutant.findtext("mutator").rsplit(".", 1)[1], mutant.findtext("description"))
        ordinal = seen[key]
        seen[key] += 1
        killer = (mutant.findtext("killingTest") or "").split("[method:")[-1].split("[test-template:")[-1]
        table[key + (ordinal,)][label] = (mutant.findtext("lineNumber"), mutant.get("status"),
                                          mutant.get("numberOfTestsRun"), killer.rstrip("()]"))
for key in sorted(table):
    row = table[key]
    if all(cell[1] == "KILLED" for cell in row.values()):
        continue
    print(f"{key[0]}.{key[1]} {key[2]} #{key[4]} '{key[3]}'")
    for label, _ in reports:
        cell = row.get(label)
        if cell is None:
            print(f"    {label}: absent")
        else:
            line, status, tests, killer = cell
            print(f"    {label}: line {line} {status} after {tests} test(s){' by ' + killer if killer else ''}")

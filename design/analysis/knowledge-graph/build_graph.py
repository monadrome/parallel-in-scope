#!/usr/bin/env python3
"""Build a knowledge graph of the core library's classes.

Three complementary edge sets:

1. Doc pointers (class level): {@link}/@see/mentions in a type's own JavaDoc.
2. Doc pointers (member level): same extraction applied to method/field JavaDoc,
   aggregated onto the owning class pair with the member names recorded.
3. Code dependencies: identifier references in the type's code (comments and
   string literals masked out), weighted by occurrence count. Comparing (1)+(2)
   against (3) exposes doc/code drift.

Nodes carry SCC id and condensation-DAG level (longest path to a sink) so the
HTML can lay the graph out as a layered reading order.

Each doc edge carries "reason" (the javadoc sentence the pointer appears in)
and, when edge-taxonomy.json is present, "rel" (hand-classified relationship;
class-level edges only).

Output: graph.json consumed by index.html.
"""

import json
import os
import re
from collections import defaultdict

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, "..", "..", ".."))
SRC = os.path.join(REPO, "core", "src", "main", "java")

JAVADOC_RE = re.compile(r"/\*\*.*?\*/", re.DOTALL)
TYPE_DECL_RE = re.compile(
    r"\b(?:(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)|(@interface)\s+([A-Za-z_$][\w$]*))"
)
MODIFIERS_BETWEEN_RE = re.compile(r"^[\s\w@(),.\[\]\"]*$")
MENTION_STOPWORDS = {"Kind", "Outcome", "State", "Status"}


# ---------------------------------------------------------------- masking


def mask_source(text):
    """Replace comments and string/char literals with spaces, keep positions.

    Returns (masked_text, javadocs) where javadocs is [(start, end, raw)].
    """
    javadocs = [(m.start(), m.end(), m.group(0)) for m in JAVADOC_RE.finditer(text)]
    chars = list(text)
    i, n = 0, len(chars)
    while i < n:
        c = chars[i]
        if c == '"' or c == "'":
            quote = c
            chars[i] = " "
            i += 1
            while i < n:
                if chars[i] == "\\":
                    chars[i] = chars[i + 1] = " "
                    i += 2
                    continue
                if chars[i] == quote:
                    chars[i] = " "
                    i += 1
                    break
                if chars[i] != "\n":
                    chars[i] = " "
                i += 1
            continue
        if c == "/" and i + 1 < n and chars[i + 1] == "/":
            chars[i] = chars[i + 1] = " "
            i += 2
            while i < n and chars[i] != "\n":
                chars[i] = " "
                i += 1
            continue
        if c == "/" and i + 1 < n and chars[i + 1] == "*":
            start = i
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            for j in range(start, end):
                if chars[j] != "\n":
                    chars[j] = " "
            i = end
            continue
        i += 1
    return "".join(chars), javadocs


def match_brace(masked, open_pos):
    depth = 0
    for i in range(open_pos, len(masked)):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(masked) - 1


# ---------------------------------------------------------------- parsing


MEMBER_METHOD_RE = re.compile(r"(?:[\w$<>\[\],.?@~]+\s+)+?(\w+)\s*\(")
MEMBER_FIELD_RE = re.compile(r"(?:[\w$<>\[\],.?@~]+\s+)+?(\w+)\s*[=;]")


def member_name(masked, jd_end, own_simple):
    after = masked[jd_end : jd_end + 500]
    m = MEMBER_METHOD_RE.search(after)
    if m:
        return m.group(1)
    m = MEMBER_FIELD_RE.search(after)
    if m:
        return m.group(1)
    return None


def parse_file(path):
    text = open(path, encoding="utf-8").read()
    masked, javadocs = mask_source(text)
    pkg_m = re.search(r"\bpackage\s+([\w.]+)\s*;", masked)
    package = pkg_m.group(1) if pkg_m else ""

    decls = []
    for m in TYPE_DECL_RE.finditer(masked):
        kind = m.group(1) or "annotation"
        name = m.group(2) or m.group(4)
        brace = masked.find("{", m.end())
        semi = masked.find(";", m.end())
        if brace == -1 or (semi != -1 and semi < brace) or brace > m.end() + 2000:
            continue
        decls.append(
            {
                "name": name,
                "kind": kind,
                "pos": m.start(),
                "body_start": brace,
                "body_end": match_brace(masked, brace),
            }
        )

    for d in decls:
        d["parent"] = None
        for other in decls:
            if other is d:
                continue
            if other["body_start"] < d["pos"] < other["body_end"]:
                if d["parent"] is None or (
                    other["body_end"] - other["body_start"]
                    < d["parent"]["body_end"] - d["parent"]["body_start"]
                ):
                    d["parent"] = other

    def fq(d):
        parts = []
        cur = d
        while cur is not None:
            parts.append(cur["name"])
            cur = cur["parent"]
        return package + "." + ".".join(reversed(parts))

    types = []
    for d in decls:
        jd = None
        for s, e, raw in javadocs:
            if e <= d["pos"] and MODIFIERS_BETWEEN_RE.match(masked[e : d["pos"]]):
                if jd is None or e > jd[1]:
                    jd = (s, e, raw)
        types.append(
            {
                "fq": fq(d),
                "simple": fq(d)[len(package) + 1 :] if package else fq(d),
                "kind": d["kind"],
                "file": os.path.relpath(path, REPO),
                "nested": d["parent"] is not None,
                "javadoc": jd[2] if jd else "",
                "jd_span": (jd[0], jd[1]) if jd else None,
                "body_start": d["body_start"],
                "body_end": d["body_end"],
                "decl": d,
            }
        )

    # member javadocs: inside a type's body, not inside any nested type's body,
    # and not a nested type's own attached javadoc
    for t in types:
        d = t["decl"]
        nested = [o for o in types if o["decl"]["parent"] is d]
        members = []
        for s, e, raw in javadocs:
            if not (d["body_start"] < s and e < d["body_end"]):
                continue
            if any(
                nd["decl"]["body_start"] < s and e < nd["decl"]["body_end"]
                for nd in nested
            ):
                continue
            if any(nd["jd_span"] and nd["jd_span"][0] == s for nd in nested):
                continue
            name = member_name(masked, e, t["simple"])
            if name:
                members.append({"name": name, "javadoc": raw})
        t["members"] = members

    return package, types, javadocs, masked


# ---------------------------------------------------------------- javadoc text


def clean_javadoc(raw):
    text = re.sub(r"^/\*\*|\*/$", "", raw.strip(), flags=re.DOTALL)
    lines = []
    for line in text.splitlines():
        line = re.sub(r"^\s*\* ?", "", line)
        lines.append(line)
    return "\n".join(lines).strip()


def sentence_around(text, start, end, limit=260):
    left = 0
    for sep in (". ", "! ", "? ", "。", "<p>"):
        i = text.rfind(sep, 0, start)
        if i != -1:
            left = max(left, i + len(sep))
    right = len(text)
    for sep in (". ", "! ", "? ", "。"):
        i = text.find(sep, end)
        if i != -1:
            right = min(right, i + 1)
    s = text[left:right]
    s = re.sub(r"\{@link\s+([\w#.]+)[^}]*\}", r"\1", s)
    s = re.sub(r"<[^>]*>", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    if len(s) > limit:
        s = s[:limit].rstrip() + "…"
    return s


def extract_pointers(javadoc_raw, self_simple, resolver):
    """target_simple -> (kind, reason); priority link > see > mention."""
    found = {}
    if not javadoc_raw:
        return found
    doc = clean_javadoc(javadoc_raw).replace("\n", " ")

    def resolve(target):
        target = target.split("#")[0].strip()
        return resolver(target) if target else None

    for m in re.finditer(r"\{@link\s+([^}\s]+)", doc):
        t = resolve(m.group(1))
        if t and t != self_simple and t not in found:
            found[t] = ("link", sentence_around(doc, m.start(), m.end()))
    for m in re.finditer(r"@see\s+([^\s<{]+)", doc):
        t = resolve(m.group(1))
        if t and t != self_simple and t not in found:
            found[t] = ("see", sentence_around(doc, m.start(), m.end()))
    prose = re.sub(r"\{@link[^}]*\}", " ", doc)
    prose = re.sub(r"@see[^\n]*", " ", prose)
    prose = re.sub(r"<[^>]*>", " ", prose)
    for m in re.finditer(r"\b([A-Z][\w$]*)\b", prose):
        if m.group(1) in MENTION_STOPWORDS:
            continue
        t = resolver(m.group(1))
        if t and t != self_simple and t not in found:
            found[t] = ("mention", sentence_around(prose, m.start(), m.end()))
    return found


# ---------------------------------------------------------------- SCC / levels


def scc_levels(node_ids, edges):
    """Tarjan SCC (iterative) + condensation-DAG longest-path-to-sink level."""
    adj = defaultdict(list)
    for e in edges:
        adj[e["from"]].append(e["to"])

    index = {}
    low = {}
    on_stack = set()
    stack = []
    counter = [0]
    comp = {}

    for root in node_ids:
        if root in index:
            continue
        work = [(root, iter(adj.get(root, ())))]
        index[root] = low[root] = counter[0]
        counter[0] += 1
        stack.append(root)
        on_stack.add(root)
        while work:
            v, it = work[-1]
            advanced = False
            for w in it:
                if w not in index:
                    index[w] = low[w] = counter[0]
                    counter[0] += 1
                    stack.append(w)
                    on_stack.add(w)
                    work.append((w, iter(adj.get(w, ()))))
                    advanced = True
                    break
                elif w in on_stack:
                    low[v] = min(low[v], index[w])
            if advanced:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[v])
            if low[v] == index[v]:
                cid = len(set(comp.values()))
                while True:
                    w = stack.pop()
                    on_stack.discard(w)
                    comp[w] = cid
                    if w == v:
                        break

    # remap comp ids to stable 0..k
    remap = {}
    for v, c in comp.items():
        if c not in remap:
            remap[c] = len(remap)
        comp[v] = remap[c]

    dag = defaultdict(set)
    for e in edges:
        a, b = comp[e["from"]], comp[e["to"]]
        if a != b:
            dag[a].add(b)

    level = {}

    def lev(c):
        if c not in level:
            succ = dag.get(c)
            level[c] = 0 if not succ else 1 + max(lev(s) for s in succ)
        return level[c]

    for c in set(comp.values()):
        lev(c)
    return comp, {v: level[c] for v, c in comp.items()}


# ---------------------------------------------------------------- main


def main():
    files = []
    for dirpath, _, names in os.walk(SRC):
        for n in sorted(names):
            if n.endswith(".java"):
                files.append(os.path.join(dirpath, n))

    all_types = []
    package_infos = []
    masked_by_file = {}
    for path in sorted(files):
        package, types, javadocs, masked = parse_file(path)
        masked_by_file[path] = masked
        if os.path.basename(path) == "package-info.java":
            package_infos.append((package, javadocs))
        all_types.extend(types)

    # resolvers
    by_simple = defaultdict(list)
    by_suffix = {}
    for t in all_types:
        by_simple[t["simple"].split(".")[-1]].append(t["simple"])
        by_suffix[t["simple"]] = t["simple"]

    def resolver(target):
        target = target.strip().rstrip(".,;)")
        if target.startswith(("java.", "javax.", "com.google.", "org.")):
            return None
        if target in by_suffix:
            return target
        if target in by_simple:
            return by_simple[target][0]
        last = target.split(".")[-1]
        if last in by_simple:
            return by_simple[last][0]
        for s in by_suffix:
            if s.endswith("." + target):
                return s
        return None

    # ---- doc edges (class level + member level)
    nodes = []
    edge_map = {}  # (from,to) -> edge dict
    for t in all_types:
        nodes.append(
            {
                "id": t["simple"],
                "kind": t["kind"],
                "nested": t["nested"],
                "file": t["file"],
                "summary": clean_javadoc(t["javadoc"]).split("\n")[0][:160]
                if t["javadoc"]
                else "",
            }
        )

        def add(target, kind, reason, level, member):
            key = (t["simple"], target)
            e = edge_map.get(key)
            if e is None:
                e = edge_map[key] = {
                    "from": t["simple"],
                    "to": target,
                    "kind": kind,
                    "reason": reason,
                    "level": level,
                    "members": [],
                }
            elif e["level"] == "member" and level == "class":
                e["level"] = "class"
                e["kind"] = kind
                e["reason"] = reason
            if member and member not in e["members"] and len(e["members"]) < 8:
                e["members"].append(member)

        for target, (kind, reason) in extract_pointers(
            t["javadoc"], t["simple"], resolver
        ).items():
            add(target, kind, reason, "class", None)
        for mem in t["members"]:
            for target, (kind, reason) in extract_pointers(
                mem["javadoc"], t["simple"], resolver
            ).items():
                add(target, kind, reason, "member", mem["name"])

    # package-info node
    for package, javadocs in package_infos:
        if not javadocs:
            continue
        raw = javadocs[0][2]
        nodes.append(
            {
                "id": "(package-info)",
                "kind": "package",
                "nested": False,
                "file": "package-info.java",
                "summary": clean_javadoc(raw).split("\n")[0][:160],
            }
        )
        for target, (kind, reason) in extract_pointers(
            raw, "(package-info)", resolver
        ).items():
            key = ("(package-info)", target)
            if key not in edge_map:
                edge_map[key] = {
                    "from": "(package-info)",
                    "to": target,
                    "kind": kind,
                    "reason": reason,
                    "level": "class",
                    "members": [],
                }

    edges = list(edge_map.values())
    class_edges = [e for e in edges if e["level"] == "class"]
    member_edges = [e for e in edges if e["level"] == "member"]

    # ---- taxonomy annotation (class-level edges)
    taxonomy = {}
    tax_path = os.path.join(ROOT, "edge-taxonomy.json")
    if os.path.exists(tax_path):
        taxonomy = json.load(open(tax_path, encoding="utf-8"))
        unmatched = set(taxonomy.get("edges", {}))
        for e in edges:
            key = e["from"] + "||" + e["to"]
            if key in taxonomy.get("edges", {}):
                e["rel"] = taxonomy["edges"][key]["rel"]
                unmatched.discard(key)
            else:
                e["rel"] = None
                if e["level"] == "class":
                    print(f"WARN: class edge without taxonomy entry: {key}")
        for key in sorted(unmatched):
            print(f"WARN: taxonomy entry without edge: {key}")

    # ---- code dependency edges
    simple_ids = [t["simple"] for t in all_types]
    two_seg = sorted((s for s in simple_ids if "." in s), key=len, reverse=True)
    last_seg = defaultdict(set)
    for s in simple_ids:
        last_seg[s.split(".")[-1]].add(s)
    unique_last = sorted(k for k, v in last_seg.items() if len(v) == 1)

    two_re = (
        re.compile(
            r"(?<![\w.])(" + "|".join(re.escape(s) for s in two_seg) + r")(?![\w(])"
        )
        if two_seg
        else None
    )
    one_re = (
        re.compile(r"\b(" + "|".join(re.escape(s) for s in unique_last) + r")\b")
        if unique_last
        else None
    )

    # for attribution: innermost type containing a position, per file
    types_by_file = defaultdict(list)
    for t in all_types:
        types_by_file[t["file"]].append(t)

    code_w = defaultdict(int)
    for path, masked in masked_by_file.items():
        rel = os.path.relpath(path, REPO)
        ftypes = types_by_file[rel]

        def owner(pos):
            best = None
            for t in ftypes:
                if t["body_start"] < pos < t["body_end"]:
                    if best is None or t["body_end"] - t["body_start"] < best[
                        "body_end"
                    ] - best["body_start"]:
                        best = t
            return best

        claimed = []  # spans matched by two-segment names
        if two_re:
            for m in two_re.finditer(masked):
                target = by_suffix.get(m.group(1))
                src = owner(m.start())
                if target and src and src["simple"] != target:
                    code_w[(src["simple"], target)] += 1
                claimed.append((m.start(), m.end()))
        if one_re:
            for m in one_re.finditer(masked):
                if any(a <= m.start() < b for a, b in claimed):
                    continue
                target = next(iter(last_seg[m.group(1)]))
                src = owner(m.start())
                if src and src["simple"] != target:
                    code_w[(src["simple"], target)] += 1

    code_edges = [
        {"from": f, "to": t, "weight": w}
        for (f, t), w in sorted(code_w.items(), key=lambda kv: -kv[1])
    ]

    # ---- components + SCC levels (on all doc edges)
    adj = defaultdict(set)
    for e in edges:
        adj[e["from"]].add(e["to"])
        adj[e["to"]].add(e["from"])
    comp = {}
    cid = 0
    for n in [t["simple"] for t in all_types] + ["(package-info)"]:
        if n in comp:
            continue
        stack = [n]
        comp[n] = cid
        while stack:
            cur = stack.pop()
            for nb in adj.get(cur, ()):
                if nb not in comp:
                    comp[nb] = cid
                    stack.append(nb)
        cid += 1
    sizes = defaultdict(int)
    for c in comp.values():
        sizes[c] += 1

    scc, levels = scc_levels([n["id"] for n in nodes], edges)
    for n in nodes:
        n["component"] = comp[n["id"]]
        n["scc"] = scc[n["id"]]
        n["level"] = levels[n["id"]]

    out = {
        "nodes": nodes,
        "edges": edges,
        "codeEdges": code_edges,
        "componentSizes": dict(sizes),
        "categories": {
            k: v["label"] for k, v in taxonomy.get("categories", {}).items()
        },
    }
    with open(os.path.join(ROOT, "graph.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    indeg = defaultdict(int)
    for e in edges:
        indeg[e["to"]] += 1
    print(
        f"types: {len(nodes)}  doc edges: {len(edges)} "
        f"(class {len(class_edges)}, member {len(member_edges)})  "
        f"code edges: {len(code_edges)}  components: {cid}"
    )
    print("component sizes:", sorted(sizes.values(), reverse=True))
    print("top referenced (doc):")
    for name, d in sorted(indeg.items(), key=lambda x: -x[1])[:10]:
        print(f"  {d:3d} <- {name}")
    cdeg = defaultdict(int)
    for e in code_edges:
        cdeg[e["to"]] += 1
    print("top depended-on (code):")
    for name, d in sorted(cdeg.items(), key=lambda x: -x[1])[:10]:
        print(f"  {d:3d} <- {name}")


if __name__ == "__main__":
    main()

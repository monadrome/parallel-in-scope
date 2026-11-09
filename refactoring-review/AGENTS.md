# AGENTS.md

Rule router for refactoring-review: the behavior-preserving restructuring pass that runs on every
code change after its behavior commit and before code review (`/multi-review`). The orchestration
steps live in `.agents/skills/refactoring-review/SKILL.md`; this directory owns the rules, and
`scripts/refactoring-review.py` owns the deterministic parts (gate, plan, detect, merge, verify).

## Position In The Workflow

```
implement -> local verification -> behavior commit -> refactoring-review -> refactor: commit(s)
          -> code review (/multi-review over base..HEAD) -> delivery
```

Refactoring is a review-stage duty, not an implementation-stage one: the implementing session has
the most context pressure and the strongest bias toward its own structure, so detection runs in
fresh read-only contexts and the implementing agent only triages and applies.

## Route Table

Read only the rows your role needs.

| File | Owns | Read by |
|---|---|---|
| [principles.md](principles.md) | R1-R10, refactoring tiers T0-T2, scaling and stop rules, rejected alternatives | everyone, first |
| [config.json](config.json) | every number and pattern: path classes, lens triggers, size thresholds, budgets, document routes | the script; humans tuning the mechanism |
| [lenses/structure.md](lenses/structure.md) | lens `S`: names, functions, local duplication, speculative generality, dead code | the detector assigned `S` |
| [lenses/modules.md](lenses/modules.md) | lens `M`: cross-file smells, seams, module depth; always reviews the whole diff | the detector assigned `M` |
| [lenses/concurrency.md](lenses/concurrency.md) | lens `C`: the concurrency-mechanism ladder and its review rules | the detector assigned `C`; implementers choosing a mechanism |
| [lenses/conventions.md](lenses/conventions.md) | lens `V`: repository conventions a reviewer applies by judgement | the detector assigned `V` |
| [lenses/tests.md](lenses/tests.md) | lens `T`: test structure, oracles, timing | the detector assigned `T` |
| [lenses/defensive.md](lenses/defensive.md) | lens `D`: checks, catches, fallbacks, guards, and switches for failures nothing can cause, with the firing question that separates them from trust-boundary checks | the detector assigned `D`; implementers deciding whether to add a check |
| [detector-brief.md](detector-brief.md) | the prompt template every detector receives, with its output schema | the script (fills it); detectors (receive it) |
| [report-template.md](report-template.md) | the shape of the run report and its dispositions | the main agent writing the report |

## Precedence

When rules disagree, the earlier source wins:

1. Root `AGENTS.md`, the live contracts under `design/`, accepted ADRs under `adr/`, and the scoped
   `AGENTS.md` files (`queue/`, `demo/`, `integrations/`).
2. [principles.md](principles.md).
3. The lens files.

A lens rule that contradicts a higher source is a defect in the lens file: suppress the finding and
fix the lens file in a separate change.

## Maintenance Rules

- Numbers and patterns live only in [config.json](config.json); prose names the key
  (`limits.maxAgents`) instead of repeating its value.
- Rule IDs (`S01`, `C04`, ...) are stable. Never reuse an ID; retire a rule by replacing its body
  with one line naming the reason and, when it became a check, the check that replaced it.
- A rule whose violations are a fixed syntactic pattern (a banned API, an import shape, a naming
  form) belongs in a deterministic check, not here. Mark such rules `mechanizable: yes`; once the
  check exists, retire the rule.
- Each run reads these files from the review base, not from the reviewed head, so a change cannot
  relax the rules that review it. A change to this directory takes effect on the next change. The
  one exception: config keys the base lacks are taken from the head and listed in the plan's
  `configKeysFromHead`, so a change can add a setting the script needs without changing any
  existing value.
- Changing [config.json](config.json), the templates, or the script requires
  `python3 -m unittest discover -s scripts -t scripts`; changing the Markdown requires
  `python3 scripts/check-doc-refs.py` and `python3 scripts/check-local-links.py`.
- Cite code by symbol, never by line number, and never by commit hash.

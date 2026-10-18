---
name: dependency-audit
description: Triage the monthly dependency audit. Use when the user asks about outdated dependencies, wants to review the dependency-audit issue, or plans a Maven, GitHub Actions, or docs dependency upgrade.
---

# Dependency audit triage

## Context

- `.github/workflows/dependency-audit.yml` runs
  `scripts/check-dependency-updates.py` on the first of each month and updates the
  rolling `dependency-audit` issue.
- The script is the source of truth for current and latest versions. It is
  deterministic and never edits files.
- The schedule lives on GitHub Actions, not on the local machine. The workflow
  file must be on `main` to be scheduled; the job checks out `dev/v0.4.0`.
- The build JDK is 25 (current LTS) and the runtime target is Java 8. Java 8 is a
  hard constraint from `AGENTS.md`; never propose a dependency that requires a
  newer Java without an explicit migration plan.

## Steps

1. Read the latest report, in this order:
   - `gh issue list --label dependency-audit --state open --json number,body`
   - or `python3 scripts/check-dependency-updates.py --format json`
2. Do not re-check items with `status: current`, `managed`, `not_used`, or
   `unpinned`. They are already classified.
3. For each `status: needs_review` or `status: unknown` item:
   - Find the release notes and the minimum Java version.
   - Check for removed or changed APIs, deprecations, and behavior changes that
     affect this repository.
   - Record the source URL and a one-line conclusion.
4. Render the triage table in the output format below. Keep the script's
   `current`, `latest`, and `delta` values; add an `assessment` column.
5. Group upgrade proposals by patch, minor, and major. Major upgrades require a
   user decision before any edit.
6. Do not edit `pom.xml`, workflows, or `mkdocs/requirements.txt`, and do not open
   a PR, without explicit user approval.
7. If a lookup fails twice, mark the item `unknown`. Never guess a version.

## Output format

```markdown
# Dependency triage — YYYY-MM-DD

| Component | Current | Latest | Delta | Assessment | Source |
|---|---|---|---|---|---|
| com.google.guava:guava | 33.6.0-jre | 33.7.2-jre | minor | Java 8 compatible; patch-level risk | https://github.com/google/guava/releases |
```

End with three short sections:

- **Safe to upgrade now**: patch/minor with no relevant breaking changes.
- **Needs a decision**: major upgrades, minimum-Java changes, or behavior changes.
- **Unknown**: anything the script or the lookup could not resolve.

## Guardrails

- Stable releases only; ignore SNAPSHOT, RC, alpha, beta, M, EA, and preview.
- Do not change the build JDK 25 or the runtime JDK 8.
- Do not upgrade a major dependency just because it is newer; check the Java 8
  contract and the CI `java8-runtime` job.
- The audit reports; it does not authorize a change.

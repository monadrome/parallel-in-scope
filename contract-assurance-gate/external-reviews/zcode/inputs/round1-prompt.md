You are the independent reviewer for a workflow-contract change. Review the complete attached diff from 8e6d3f3e7b374fd1cbb1840026377c45d2b012c3 to 0b390361, using the current detached clone for surrounding context. Read the root AGENTS.md and every changed file; do not review only the diff hunks. Stay read-only: do not modify files, run tests, install anything, access remotes, or read other reviewers' outputs.

Attack surfaces: assurance threshold and skip-audit semantics, post-commit sequencing, contract versus implementation drift, adapter/manifest gate completeness, final-gate parsing and error paths, test effectiveness, and complete diff coverage. Report only defects introduced or materially exposed by this change, each with a concrete trigger and observable consequence. Reproduce findings by source inspection only; do not execute the implementation.

Return exactly one JSON object and no Markdown or prose:
{
  "summary": "short paragraph naming the files reviewed and the resulting assessment",
  "findings": [
    {
      "file": "repository-relative/path",
      "lineStart": 1,
      "lineEnd": 2,
      "title": "short defect title",
      "trigger": "concrete condition",
      "consequence": "observable failure",
      "evidence": "specific source path and mechanism",
      "severity": "P1|P2|P3",
      "confidence": 0.0,
      "suggestedFix": "actionable fix"
    }
  ]
}
Return an empty findings array when no defect is found. Also state explicitly in the summary whether the agent-workflow-only assurance skip is justified for this change.
# Independent review evidence

Three fresh Kiro `gpt-5.6-terra` reviews covered the complete changed-path set:

1. `hidden-contract-regression-a6214b44-20261123-152612-6444e8`: C01–C02 confirmed; lexical execution evidence and missing archive linkage fixed and reverse-verified.
2. `hidden-contract-regression-2cf9df18-20261124-012349-829543`: C03–C06 confirmed; FQCN matching, conditional promotion commands/gate, canonical paths, and sealed candidate origins fixed with failing-first tests.
3. `hidden-contract-regression-7df3a3b2-20261124-015503-c455b8`: C07–C09 confirmed; canonical per-class reports, byte-identical frozen target snapshots, and unique sealed candidate IDs fixed with failing-first tests.

The documented three-round stop rule was applied after round three. No fourth full review round was opened. The final follow-up commit contains only the third-round fixes and their tests/docs; 460 Python tests, hidden clean Maven tests, integrity, package, and documentation checks pass on the final head.

Earlier Codex attempts were blocked or failed and were never counted as clean reviews: `hidden-contract-regression-cad34059-20261123-095358-311496` timed out twice; `hidden-contract-regression-a6214b44-20261123-150107-16afa3` hit `max_output_tokens`.

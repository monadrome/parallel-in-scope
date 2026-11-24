# Execution instructions

Generate candidates only; do not run them against the real implementation. The main assurance agent will run:

```bash
ASSURANCE_REPO=<verification-checkout> python3 -m unittest -v candidate_tests.py
```

Use temporary fixture directories that clean themselves up. Keep candidates deterministic and independent of test order.

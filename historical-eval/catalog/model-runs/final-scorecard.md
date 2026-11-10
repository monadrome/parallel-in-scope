# Historical model evaluation

- Benchmark: `historical-v1`
- State: **complete-with-unresolved-verdicts**
- Behavioural denominator: **90**
- Model timeout: **1800s**; timeout retries: **2**; evaluator timeout: **180s**

| Provider | Model | Effort | Service tier | Observed | Resolved | Resolved coverage | Behavior pass | Pass / resolved | Pass / denominator | Unresolved | p50 (s) | p90 (s) | State |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `codex` | `gpt-5.6-luna` | `max` | `fast` | 90 | 84/90 | 93.33% | 8 | 9.52% | 8.89% | 6 | 729.323 | 1469.322 | `complete-with-unresolved-verdicts` |
| `codex` | `gpt-6.1-sol` | `medium` | `fast` | 90 | 86/90 | 95.56% | 5 | 5.81% | 5.56% | 4 | 250.895 | 489.92 | `complete-with-unresolved-verdicts` |
| `codex` | `gpt-5.6-terra` | `medium` | `fast` | 90 | 88/90 | 97.78% | 8 | 9.09% | 8.89% | 2 | 244.055 | 425.944 | `complete-with-unresolved-verdicts` |
| `codex` | `gpt-6.1-terra` | `medium` | `fast` | 90 | 85/90 | 94.44% | 7 | 8.24% | 7.78% | 5 | 223.406 | 328.835 | `complete-with-unresolved-verdicts` |
| `zcode` | `account:bigmodel-individual-coding-plan/GLM-5.3` | `max` | `n/a` | 90 | 81/90 | 90.00% | 1 | 1.23% | 1.11% | 9 | 27.511 | 887.826 | `complete-with-unresolved-verdicts` |

Timeouts, evaluator timeouts, and runner errors remain separate from capability scoring; resolved coverage counts only definitive verdicts.


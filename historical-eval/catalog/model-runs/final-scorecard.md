# Historical model evaluation

- Benchmark: `historical-v1`
- State: **complete-with-unresolved-timeouts**
- Behavioural denominator: **90**
- Model timeout: **1800s**; timeout retries: **2**

| Provider | Model | Effort | Service tier | Completed | Behavior pass | Pass / completed | Pass / denominator | Rescue fail | Timeout | p50 (s) | p90 (s) | State |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `codex` | `gpt-5.6-luna` | `max` | `fast` | 90/90 | 8 | 8.89% | 8.89% | 1 | 4 | 726.316 | 1427.906 | `complete-with-unresolved-timeouts` |
| `codex` | `gpt-6.1-sol` | `medium` | `fast` | 90/90 | 5 | 5.56% | 5.56% | 1 | 2 | 250.895 | 489.92 | `complete-with-unresolved-timeouts` |
| `codex` | `gpt-5.6-terra` | `medium` | `fast` | 90/90 | 8 | 8.89% | 8.89% | 1 | 0 | 244.055 | 425.944 | `complete` |
| `codex` | `gpt-6.1-terra` | `medium` | `fast` | 90/90 | 7 | 7.78% | 7.78% | 1 | 0 | 223.406 | 328.835 | `complete` |
| `zcode` | `account:bigmodel-individual-coding-plan/GLM-5.3` | `max` | `n/a` | 90/90 | 1 | 1.11% | 1.11% | 2 | 9 | 27.511 | 887.826 | `complete-with-unresolved-timeouts` |

Timeouts remain separate from capability scoring until the configured retry policy resolves them.


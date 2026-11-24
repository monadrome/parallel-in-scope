# Reverse verification

All nine candidates passed their initial real-implementation run (`logs/010-candidate-initial-run.stderr.log`). No adaptation was needed.

## CONTRACT-TDD-001 / non-contract helper

Fault injection rejected `NonContractHelper`. The focused candidate failed (`logs/011-reverse-candidate-tdd-001.stderr.log`, exit 1), exact source restoration passed (`logs/012-reverse-restored-tdd-001.stderr.log`, exit 0).

## CONTRACT-TDD-008 / malformed canonical XML

The first focused command used a nonexistent method name and produced a loader error; logs 13–14 are invalid evidence and are not counted. The corrected fault injection returned success on malformed XML and the actual candidate failed (`logs/015-reverse-candidate-tdd-008-corrected.stderr.log`, exit 1). Exact restoration passed (`logs/016-reverse-restored-tdd-008-corrected.stderr.log`, exit 0).

## CONTRACT-TDD-009 / mixed passing and nonpassing testcases

Fault injection disabled all direct outcome checks. The focused candidate failed (`logs/017-reverse-candidate-tdd-009.stderr.log`, exit 1). Exact restoration passed (`logs/018-reverse-restored-tdd-009.stderr.log`, exit 0). `git diff -- scripts/check-contract-tests.py` was empty after restoration.

No production Java source was changed. The TaskId seed behavior has a separate fresh final-head PIT run and equivalent-survivor disposition.

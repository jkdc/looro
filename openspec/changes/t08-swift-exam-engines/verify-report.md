# T08 Verification Report

## Status

The T08 implementation checks below are available and remain tracked. Per user
decision L19, defer all Swift test execution until the final verification batch
after the T01–T14 implementation phases. Do not claim Swift verification before
that batch, and do not close the overall feature while its deferred Swift gates
remain unrun.

## Observed checks

Fresh Docker rerun on 2026-10-17 after the user moved Swift execution to the final
verification batch:

| Check | Result |
|---|---|
| Placement/level-test exporters `--check`, `check_cross_platform.py`, and full Python unittest suite | Passed; exporters current, cross-platform check passed, 138 tests passed. |
| API `tests/level-tests.test.ts` | Passed; 4/4 tests. |
| Android `:app:testDebugUnitTest --tests com.jkdc.app.LevelTestEngineTest` | `BUILD SUCCESSFUL`; Gradle reported tasks up-to-date. |

The Swift parser, isolated runtime smoke, and SwiftPM failures below are historical
observations from before L19; no Swift checks were run in this fresh rerun.

| Check | Result |
|---|---|
| `python3 scripts/export_placement.py --check` (Docker) | Passed; Swift and Kotlin outputs current. |
| `python3 scripts/export_leveltests.py --check` (Docker) | Passed; Swift, Kotlin, and API outputs current. |
| `python3 scripts/check_cross_platform.py` (Docker) | Passed. |
| `python3 -m unittest discover -s scripts/tests -t .` (Docker) | 138 passed. |
| `npm exec -- tsx --test --test-concurrency=1 tests/level-tests.test.ts` (Docker) | 4 passed. |
| `:app:testDebugUnitTest --tests com.jkdc.app.LevelTestEngineTest` (Docker) | BUILD SUCCESSFUL; focused shared-golden test class passed. |
| Isolated Swift 6 source/test typecheck with contract stubs (Docker) | Passed. |
| Isolated Swift runtime smoke with contract/CryptoKit-shaped stubs (Docker) | Passed generated-bank JSON decoding, placement ladder, weekly exam shape/disjoint passages, all-correct grading, and UserDefaults preference persistence. The stub digest does NOT verify real SHA-256 or the 202641 golden order. |
| Swift parser over T08 sources and tests (Docker) | Passed. |
| `swift test --package-path apps/ios` (official Swift Docker image) | Failed before tests ran: Linux lacks CryptoKit required by unchanged Core files, and unchanged `CheckpointsData.swift` reports Swift syntax errors. No full Swift test result is claimed. |

## Deferred final proof

Run `swift test --package-path apps/ios` and the applicable iOS App/UI gates in a
supported environment during the final verification batch. The isolated smoke
used a CryptoKit-shaped stub, so it does not verify real SHA-256 ordering or the
shared golden parity. Whisper-dependent lint/APK checks remain deferred until all
development phases are complete, per user instruction.

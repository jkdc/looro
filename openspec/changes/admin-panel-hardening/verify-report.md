# Verification Report — Admin Panel Hardening

## Scope and baseline

Branch: `feat/admin-panel-hardening`. Final implementation candidate: `2057d25` (`fix(admin): resolve hardening review blockers`), based on T6 commit `60c5113`. This report closes T7; it does not authorize or perform a merge.

Before implementation, the disposable API stack passed `make test-api` with 640/640 tests, and the documented Chromium browser E2E passed 57 checks. T5 later expanded the API suite to 644 checks. The T6 dashboard extraction passed 77 browser checks and its module/CSP checks.

## T7 corrections

The independent review of `30990b0..60c5113` identified five Important findings. They were corrected in `2057d25` and independently rechecked against baseline `60c5113`:

| Finding | Correction and regression coverage | Focused independent result |
|---|---|---|
| Campaign preparation accepted obsolete intent and allowed overlap | Intent-version check discards a late response after edits; preparation is serialized. Browser test holds the prepare response, edits the audience, then confirms no Apply action is created. | PASS; baseline probe reproduced the defect. |
| An uncertain campaign lost its identity/lock on remount | Campaign ID, confirmation, and reason are persisted in session storage before the irreversible request; remount/reload restores locked same-ID retry. Storage failure prevents sending the apply request. | PASS; remount/reload same-ID retry and storage cleanup verified; baseline probe reproduced the defect. |
| Custom Tabulator filters cleared typed values and could send `eq:` | Disabled vendor live-keyup handling for the custom editor and omit empty/undefined filter values. Browser test types actual keystrokes, waits beyond the debounce, clears, and asserts no empty equality query. | PASS; baseline probe reproduced the defect. |
| A late post-mutation refresh could overwrite another route | Policy/plans renderers receive view context; post-write refresh continues without aborting the confirmed read, but stale results, warnings, retries, and toasts cannot paint a different route. | PASS; baseline probe reproduced the defect. |
| More than 25 run IDs were sent to a server endpoint capped at 25 | Exact watch IDs are polled in sequential chunks of at most 25 and combined without losing IDs. Browser regression verifies 26 IDs as 25+1. | PASS; baseline probe reproduced the defect. |

The reviewer used a scratch directory created with `mktemp` under `/tmp` and did not modify the repository. Its focused probe command passed 6/6 against the candidate and failed 0/6 against baseline, reproducing the targeted defects. It also passed `git diff --check 60c5113..2057d25` and JS syntax checks. This was a focused correction recheck, **not a fresh whole-branch review**; the earlier broad review was on `30990b0..60c5113`.

## Final verification

| Check | Result | Scope / limit |
|---|---|---|
| `E2E_EVIDENCE_DIR=.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t7-final node services/api/tests/browser/admin-e2e.mjs` | `T11 browser E2E: all checks passed.` | Clean disposable PostgreSQL/API/Caddy stack, Chromium, local stub LLM, seeded test accounts/exams; no production data or real provider. Includes all five T7 browser regressions. |
| `cd services/api && npm run check` | Passed (`tsc --noEmit`). | No server implementation or API contract changed in T7; this is an additional backend type-check. |
| `node --check services/api/admin-ui/app.js` | Passed. | Browser client syntax. |
| `node --check services/api/tests/browser/admin-e2e.mjs` | Passed. | Browser regression harness syntax. |
| `git diff --check` | Passed before code commit. | Whitespace/conflict-marker check. |
| `make test-api` | 644 passed, 0 failed, 0 skipped. | Full isolated API integration suite; disposable stack and volume were removed by the runner. |
| Independent focused blocker recheck | 5/5 original Important findings passed; baseline reproduced all 5. | Probes use DOM/network doubles, not a second full browser or PostgreSQL run. |

The final browser screenshots and run artifacts are in the ignored workspace `.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t7-final/`. The clean final run passed all checks. Earlier attempts are not used as green evidence: the first run encountered a previously-mutated disposable database; later test iterations exposed setup defects in the new regression harness (route cleanup, incomplete intercepted status response, and view-state assumptions). The harness/environment was corrected, the disposable stack reset, and the complete final run passed. No known automated check remains failing.

## Remaining limits and deferred item

- Firefox and WebKit were not exercised; automated browser coverage used Chromium.
- Manual screen-reader/assistive-technology testing was not performed. The earlier report's WCAG caveat remains; no full conformance certification is claimed.
- No real LLM provider, production data, or live service was used.
- The independent focused recheck did not rerun full API integration, browser E2E, or TypeScript; those were run by the parent and are reported separately above.
- **Deferred non-blocking Minor:** cancelling the reason dialog from the exam-review page can still lose focus because that view disables the trigger before opening the dialog (`services/api/admin-ui/app.js`, review moderation handler). It was explicitly excluded from the single bounded blocker-correction pass and is not a blocker for the five Important findings. It remains a separate follow-up if the project chooses to close this accessibility detail.

## Delivery record

- T7 implementation commit: `2057d25` (`fix(admin): resolve hardening review blockers`).
- T7 report/tracker documentation closeout commit: `c2a161c` (`docs(admin): record hardening verification`).
- No push, pull request, merge, or worktree was used.
- Risk: item 4 (async state ordering and mutation/refresh lifecycle).

# Verify report: level-tests-llm

Date: 2026-10-08 · Branch `feature/learning-platform` · Backend-only change
(app consumption is follow-up per proposal).

## What ran green

- **Server suite: 464 tests, 0 failures** (Node 20.19.4, PG 17 ephemeral —
  `TEST_DATABASE_URL`). Includes 5 new `level-tests-llm.test.ts` tests:
  AES-256-GCM round-trip + tamper rejection + mask; provider resolution
  precedence (DB active → env → none); gate-1 validator fixtures (accepts a
  well-formed pool; rejects two-correct-answers, duplicated options, answer
  outside options, missing explain, section minimums); full integration
  (generation → approved → `generated` 200 with 25 items and pinned variant →
  ETag/304 per variant → hybrid `llm` → hybrid deterministic fallback for a
  level with pool but no generation → 404 for a language without pools);
  strict-judge removal path with `force` regeneration.
- **Type-check clean** (`tsc --noEmit`), `export_leveltests.py --check` green
  (Kotlin + TS content emission with exam models).

## Defects caught and fixed during verification

1. **IV never stored** in `encryptSecret` — the AES output omitted the IV, so
   every decrypt failed. Now `iv || ciphertext || tag`.
2. **Composer ordering**: listening passages were filtered before the reading
   picks were finalized — a week's exam could pair the same passage in both
   blocks.
3. **Ledger-id case** (`levelcheck-en-A1`) — lowercased for the server slug.
4. Test-side: Buffer compound assignment under `noUncheckedIndexedAccess`,
   fixture passages shorter than the gate minimums, fixture passages reusing
   items (correctly caught by the in-generation dedupe), a fallback case that
   assumed a French pool that does not exist, and a non-idempotent
   integration test over a persistent DB (now retires its rows first).

## Known gaps / open gates

- **Real-provider generation run** (GLM/OpenAI with a live key) — the suite
  uses a stub transport; one manual run against the real provider is the
  remaining smoke (needs a funded key).
- **Competent-speaker review** of LLM-generated items when the first real
  week is approved (mode `review` makes this part of the workflow).
- **Admin panel web page** for provider/model/key management — backend admin
  API (`GET/PUT /v1/admin/llm-config`, `POST …/test`) shipped; the page is
  follow-up. Until then: `npm run leveltests-generate` + env vars + CLI.
- **App consumption** of `generated`/`hybrid` — the bundled composition is
  identical by golden tests; wire-up when the backend deploys.
- `llm_provider_configs` single-active is enforced by deactivate-then-
  activate inside one transaction (the partial unique index admits a window
  only across statements — acceptable, single writer).


## Real-provider run (2026-10-08, same session)

- **Generation against GLM 5.3 Flash** (z.ai coding endpoint, the app's own
  key): all 6 weeks (EN/ES × A1/A2/B1) generated, validated and **approved**
  (`LEVELTEST_PUBLISH_MODE=auto`, judge flag mode). Token cost per exam:
  5–8k in, 22–54k out. All 6 served by `/v1/level-tests/generated` with 25
  items and 3 variants each (verified per level).
- **Judge quality**: 19/~450 items flagged (≈4%) — mostly *incomplete answer
  keys in open gaps* (alternative correct answers accepted) and two
  distractor-uniqueness issues, which are precisely the target failure
  class. Annotated in `review` for the human pass.
- **Defect found and fixed in the run**: GLM 5.3 (reasoning on) exceeded
  undici's default 5-minute headers timeout on large pools, crashing the
  CLI — `chatCompletion` now uses a 15-minute undici dispatcher and retries
  transient network errors once, and the CLI catches per-level errors and
  continues (exit 1 if any level failed).
- **Content first-pass review (this model, not a substitute for the
  competent-speaker gate)**: ES/B1 writing items correctly anchor second/
  first conditional and opinion phrases with 2–3 accepted variants each;
  EN/B1 reading passages are coherent, engaging and level-plausible. The
  19 flagged items need human disposition before treating the weeks as
  final — that is the designed workflow, not a defect.

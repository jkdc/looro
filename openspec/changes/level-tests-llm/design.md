# Design: level-tests-llm

## Generation flow (per language × level × ISO week)

```
CLI leveltests-generate [--week auto] [--force] [--levels A1,B1] [--langs en,es]
  1. load exam model (template) + 6 few-shot harvested items + weekly theme
  2. LLM call (active provider) → oversized pool (2× the exam)
  3. GATE 1 validator (deterministic) → section retries (1 directed each)
  4. GATE 2 judge LLM (LEVELTEST_VERIFY=flag|strict; off when no provider)
     flag: annotate draft · strict: remove rejects, retry flagged sections
  5. compose K variants (default 3) with the stable-hash block rotation
     (variant index in the hash)
  6. persist level_test_weeks (draft; publish_mode decides approval)
```

Idempotent per (language, level, week): UNIQUE constraint; `--force`
regenerates a draft. Approval: `leveltests-generate --approve` (or admin
endpoint) flips draft→approved when `publish_mode=auto` already approved.

## Shared composition contract

`LevelTestEngine` (Kotlin) and `composeWeekly`/`composeVariant` (TypeScript)
share the rotation: stable order by sha256 hex of `version|SECTION|id`,
ISO week selects the block, trailing remainder falls back to the stable
order head, reading passages picked before listening (excluded afterwards).
Variant index joins the hash (`variant|SECTION|id`) — variant 0 with no
salt must equal the plain composition. Golden tests pin (EN, B1, 202641) on
both sides for the deterministic composition; variant tests pin reproducibility.

## Endpoints

| Route | Behaviour |
|---|---|
| `GET /v1/level-tests/current?language&level` | unchanged (deterministic) |
| `GET /v1/level-tests/generated?language&level[&variant]` | approved LLM week; **404 `level_test_not_generated`** when absent; 400 on bad params |
| `GET /v1/level-tests/hybrid?…` | generated → else deterministic |
| `GET /v1/admin/llm-config` | provider presets + active config (key masked) + publish mode |
| `PUT /v1/admin/llm-config` | upsert active provider config (key encrypted), publish mode |
| `POST /v1/admin/llm-config/test` | minimal completion call, returns ok/error (never the key) |

All level-test content routes are proxy-exempt public content (no user
data; grading is on device). Admin routes use the panel's auth + CSRF.
Responses carry `variant` (1..K) and `source` (`llm`|`deterministic` on
hybrid); ETag includes the variant so revalidation is per-variant.

## Providers & encryption

- Presets: `openai` (api.openai.com/v1), `glm` (api.z.ai/api/paas/v4,
  models glm-4.5-flash / glm-5.3-flash), `opencode` (gateway URL from
  config; OpenAI-compatible).
- `llm_provider_configs`: base_url, model, `api_key_encrypted`
  (AES-256-GCM, IV prefixed, master key `LEVELTEST_LLM_MASTER_KEY` 64-hex),
  `is_active` (single active enforced by partial unique index).
- Resolution: active DB row (decrypt) → env `LEVELTEST_LLM_*` → undefined
  (generation/gates answer 503; deterministic endpoints unaffected).
  Decrypt failure ⇒ treated as unusable (never plaintext fallback).
- Publish mode: `level_test_settings` singleton (env default `review`).

## Judge (gate 2)

Rubric prompt (versioned `judge_version`): per item, is the marked answer
uniquely correct, are distractors clearly wrong, is the level plausible, is
`explain` accurate? Output JSON `{"items":[{"id","verdict","reason"}]}`;
unmentioned items count as `ok`. Modes: `flag` (default under review —
verdicts stored in the draft's `review` field), `strict` (rejects removed,
flagged sections retried once, then dropped; exam proceeds only if minimums
still hold). Judge failures degrade to gate-1-only (annotated in metadata).

## Validation rules (gate 1)

Mirror the exporter's: unique ids `llm-…`; choice ≥3 options, no dupes,
exactly one correct among them; written/gap 1–3 non-empty answers; explain
non-empty; reading passages 40–150 words with ≥3 questions each, listening
≥2 questions each, reading/listening disjoint; recognition ≥12, writing ≥6,
≥2 reading + ≥2 listening passages; in-generation prompt dedupe
(normalized); language heuristic (target-language stopwords vs instruction
language); ids assigned by the generator (`llm-<lang>-<level>-w<week>-<n>`).

## Storage

`level_test_weeks(language_id, level, week_index)` unique; `pool` (validated
LLM output), `variants` (K composed exams), `status draft/approved/retired`,
`generator/model/prompt_version/judge_version/judge_mode/tokens_in/out/
review`, timestamps, approval actor. Immutable after approval (trigger);
`retired` removes it from serving without deleting. `level_test_settings`
singleton: `publish_mode`, `verify_mode`, `variants`.

## Testing

- Unit: crypto round-trip + tamper; provider resolution precedence; gate-1
  validator fixtures (good/bad); variant reproducibility; judge parsing.
- Orchestration: stub transport (deterministic fixture) → draft saved;
  strict retry path; flag annotation; fallback when validation fails.
- HTTP: generated 404 → after CLI-run generation (against the test DB) 200
  with variant, hybrid fallback, ETag/304; admin endpoints auth-gated.
- Server suite green (PG 17 ephemeral, Node 20); type-check clean.

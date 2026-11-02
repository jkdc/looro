# Design: proxy-model-from-panel

## Context

The token proxy today has three configuration layers (env provider, purpose
route table, conversation-policy endpoint). All three describe one decision:
which provider and model serve the call. The operator manages the real
provider relationship in the admin panel's LLM config (`llm_provider_configs`,
AES-256-GCM keys, presets for openai / glm / opencode). Decisions taken with
the product owner:

- Provider, model and key come **entirely from the panel config** — not just
  the model string.
- The purpose header is **removed at the root**, not kept as metadata.
- `GET /v1/conversation-policy` and the app-side policy store are **deleted**.

## Decisions

### D1 — Per-call provider resolution via `resolveLlmConfig`

`TokenProxy` currently holds `providerURL`/`providerKey` from env at boot.
Instead, each `relay()` call resolves `resolveLlmConfig(db, env)`:

- Active `llm_provider_configs` row (panel: provider, baseUrl, model, key),
  decrypted with `LEVELTEST_LLM_MASTER_KEY`; undecryptable rows are skipped,
  never falling back to plaintext (existing `resolveLlmConfig` semantics).
- Env fallback `LEVELTEST_LLM_*` when no DB config (useful for tests and the
  lab before the panel is set up).
- Nothing resolvable → `503 proxy_unavailable`, the same disabled state as
  today's missing env vars.

Per-call (not per-boot) resolution means a panel edit applies to the next
call with no restart — one extra indexed query per call is acceptable at lab
scale. The relayed URL becomes `${baseUrl}/chat/completions` and the model
goes into the canonical body; `PROXY_PROVIDER_URL` / `PROXY_PROVIDER_KEY`
disappear from `.env.example`, compose files and docs.

### D2 — Purpose routing removed at the root

`PROXY_PURPOSES`, `isProxyPurpose`, `purposeRoute`, `purposeRouteTable`, the
`x-palique-purpose` parse/validation in `app.ts` (and `PROXY_REQUIRE_PURPOSE`,
`unknown_purpose`, `purpose_required`), and the `purpose` label on
`palique.proxy.provider.duration` are removed. `relay()` loses its `purpose`
parameter. `proxyPolicyRejected` keeps the `field` label (request-policy
rejections are orthogonal). The metrics contract in
`docs/observability.md` is updated accordingly — closed label set, no
cardinality risk.

Rationale: with a single model, purpose routing is dead weight; keeping it
"as metadata" would preserve three moving parts to explain a field that
controls nothing. No DB change is needed — `proxy_usage`/`proxy_attempts`
(055/056, applied in the lab volume) carry no `purpose` column.

### D3 — Migration 057 amended in place

057 is unmerged branch work, never applied to any persistent environment
(the lab DB does not have it; CI stacks are ephemeral). It is amended to
create nothing: the `proxy_purpose_routes` table and seed are dropped from
the file, and the `token_admin_audit` action enum widening keeps only the
pre-existing actions (no `set_purpose_route`). No create-then-drop noise.

### D4 — Shared provider-header helper

`chatCompletion` (llm-config.ts) adds `x-opencode-session` + user-agent for
OpenCode. That rule is extracted into an exported helper (e.g.
`providerHeaders(provider: LlmProvider, sessionID?: string)`) used by both
`chatCompletion` and the proxy relay. The transports are NOT merged: the
proxy keeps its own 30 s timeout, response byte cap, concurrency limits and
strict `usage` settlement. Reusing the panel provider for conversation
therefore gets OpenCode free models working with no extra env or header
plumbing.

### D5 — Canonical body without routes

`canonicalProxyRequest(input, model, outputCap)`: allowlist unchanged
(`messages`, optional `response_format: json_object`, `temperature`), server
overrides `model` (panel config) and `max_tokens` (`PROXY_MAX_TOKENS`,
default 4000), `stream` forced off. A client-supplied `model` stays an
accepted-and-replaced field — audit stance "the client never picks what it
is charged for" is preserved.

### D6 — Android: the `api` engine sends no model

Removed: `ConversationPolicyStore.kt` (with `ConversationPolicyState` — its
quota fields were parsed but never displayed), `MinuteCommerceClient
.conversationPolicy()`, `AppViewModel.refreshConversationPolicy` /
`ensureConversationPolicy` and their retry/revision plumbing, and the
policy-based `modelOverride` wiring for the `api` engine. The `api` engine
builds its body without a `model` field and no longer throws "no model
resolved". The `x-palique-purpose` header emission is removed; BYOK engines
keep their internal `HelperPurpose` use (opencode per-engine model pick) and
their own `model` field. `hosted-proxy` is untouched.

### D7 — Errors and states (preserved)

| Condition | Response |
| --- | --- |
| No resolvable LLM config | `503 proxy_unavailable` |
| Provider HTTP >= 400 | `502 provider_error` (attempt finished `rejected`) |
| Missing/invalid `usage` | `502 provider_error`, nothing settled |
| No unlock / no balance | `403 unlock_required` / `402 token_balance_exhausted` |
| Client `stream:true` or unknown body field | `400 request_policy_violation` |

## Test strategy

- Pure Node tests (no Docker): canonical body building (model override,
  cap, `stream:false`, allowlist), provider resolution edge cases (no rows,
  undecryptable key, env fallback), provider-header helper (opencode vs others).
- Integration (`make test-api`, ephemeral PG): full relay flow with a stub
  provider, 503 without config, panel-config change picked up per call,
  metering and quota regression, removal of purpose/`conversation-policy`
  endpoints (404, no 400 purpose errors).
- Android unit tests: `api` engine body has no `model` field and no purpose
  header; no policy fetch precedes a call; BYOK engines unchanged.
- Lab E2E (manual, documented): `make lab-up` + panel config with an OpenCode
  free model → conversation from the phone through the proxy.

## File inventory

Backend: `token-proxy.ts`, `proxy-policy.ts`, `app.ts`, `llm-config.ts`,
`tokens-admin.ts`, `metrics.ts` (label only), `migrations/057*.sql`,
`.env.example`, `compose*.yaml`, `docs/token-billing.md`,
`docs/observability.md`, `tests/token-proxy.test.ts`, `tests/proxy-quota.test.ts`,
conversation-policy tests (removed). Delete `src/conversation-policy.ts`.

Android: `OpenAICompatibleEngine.kt`, `ConversationEngineFactory.kt`,
`ConversationPolicyStore.kt` (deleted), `AppViewModel.kt`,
`MinuteCommerceClient.kt`, plus unit tests.

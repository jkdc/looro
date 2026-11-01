# Proposal: proxy-api-provider

## Intent

Make the backend proxy the production path for **every** LLM call the mobile
app makes: a new first-party provider **"api"** in the app that talks to
`/v1/proxy/responses`, with the server owning provider/model choice per call
purpose, metering consumption, and enforcing a daily quota that later backs
the paid subscription. Local/dev providers (personal OpenAI key, DeepSeek,
GLM, OpenCode) are **retained for development, benchmarking and response-time
analysis**, and hidden from release builds.

Absorbs the remediation scope of `audit-token-proxy-safety` (findings F05–F07:
funds reservation, request policy, model binding) because production routing
and quota control depend on them; that change is archived when this one lands.

## Problem

- The token proxy exists and is tested (848/848), but the client still pins
  the model (`MiniMax-M2`) and the server forwards the body verbatim — the
  server cannot choose a cheaper/free model per purpose, cannot enforce what
  it prices, and cannot express "voice on a strong model, meanings on a free
  one".
- There is no subscription seam: only prepaid packs (manual unlock). The
  product direction is a subscription whose quota the backend controls.
- Production builds must not expose provider selection: keys for paid
  providers can never ship in an app, and per-user consumption must be
  enforced where the key lives.

## Approach

- **F1 — Reservation (backend):** admit a proxy call by atomically reserving
  its conservative estimate under the wallet lock (`ledger` kinds `reserve`
  and `release` and the `wallets.reserved_nano` column already exist); settle
  actual usage and release the remainder exactly once; uncertain outcomes
  keep the hold for reconciliation. Closes the concurrent/multi-replica
  overspend gap (audit F05).
- **F2 — Request policy + purpose routing (backend):** the proxy validates a
  strict body allowlist (`messages`, optional `response_format: json_object`,
  `stream:false`), overrides model and output cap server-side, and routes by
  a required `x-palique-purpose` header (the wire values the app already
  sends: `typed_reply`, `meaning`, `assessment`, `lookup`, `topic`, `help`,
  `scenario_review`, `delegation`). Routes live in a server table seeded
  with `typed_reply → strong paid model`, everything else → free/flash
  model; legacy clients without the header keep today's behavior. Closes
  audit F06–F07.
- **F3 — Daily quota (backend):** `token_subscriptions` (plan, daily token grant,
  active window) + an idempotent daily grant written to the immutable ledger
  (`subscription-grant:<date>:<account>`); a small configurable free daily
  allowance keeps the funnel open. Exhausted quota surfaces through the
  existing balance admission as `402 token_balance_exhausted` — one money
  path, no parallel error contract. Admin CLI grants for pilots; recurring
  Play/Stripe subscription purchases are the follow-up change.
- **F4 — Policy endpoint (backend):** `GET /v1/conversation-policy` returns
  the quota state and the model label per purpose — display only; the proxy
  remains the single decision point.
- **F5 — App provider "api" (both platforms):** a new engine in the factory
  that reuses the OpenAI-compatible adapter against the proxy origin with the
  session bearer, sends the purpose header on every call, and takes its model
  from the policy (no client-side model pin, no paywall gate). The learning
  loop is already engine-agnostic, so meanings/assessments/topics flow
  through the proxy by selecting this provider.
- **F6 — Build gating (apps):** release builds force the `api` provider and
  hide the provider selector; debug builds keep the full list (OpenAI, 
  DeepSeek, GLM, OpenCode, tokens) for latency benchmarking and provider
  testing.

## Risks

- Purpose header is client-supplied: it is **advice for routing/cost**, never
  a security boundary — auth, quota and metering stay account-scoped.
- Old app versions without the header: legacy route preserves today's
  contract; the header becomes required only behind a server flag once
  fleet coverage allows it.
- Free models may lack reliable JSON mode for structured purposes
  (meanings/assessments): routing table ships per-purpose and can pin those
  purposes to a paid model until validated.
- Streaming remains out of scope (v1 non-streaming contract unchanged);
  metering-with-final-usage streaming is the designated follow-up.

## Delivery strategy

Backend first (F1–F4, docker suite + PG-backed tests), then Android (F5–F6),
then iOS parity (F5–F6 mirrored in Swift; macOS compile/test gate), operator
docs last. No store or live-purchase dependency: pilots use `tokens-admin`
grants.

## Affected areas

| Area | Impact |
|---|---|
| `services/api/src/token-proxy.ts`, `proxy-attempts.ts`, `ledger.ts` | Reservation lifecycle, request policy, purpose routing |
| `services/api/migrations/` | `proxy_purpose_routes`, `subscriptions`; ledger already supports reserve/release |
| `services/api/src/app.ts`, new `conversation-policy.ts` | Policy endpoint, purpose validation |
| `services/api/src/tokens-admin.ts` | Route + subscription grant commands |
| `apps/android/.../engines/` | `api` engine, purpose header, policy model |
| `apps/android/.../SettingsScreen.kt` | Release gating of provider selector |
| `apps/ios/` | Swift mirror of F5–F6 |
| `docs/token-billing.md`, `services/api/docs/api-capacity.md` | Contract updates |

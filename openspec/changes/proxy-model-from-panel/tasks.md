# Tasks: proxy-model-from-panel

## 1. Backend — provider from the panel (F1)

- [ ] 1.1 Test-first: pure test that `TokenProxy` resolves the provider per
  call from `resolveLlmConfig` (stub DB + env): active DB row wins, env
  fallback, none → `503 proxy_unavailable`.
- [ ] 1.2 Test-first: relayed request targets `${baseUrl}/chat/completions`
  with `Authorization: Bearer <config.apiKey>` and body `model` =
  config model; `PROXY_MAX_TOKENS` as `max_tokens`; `stream:false`.
- [ ] 1.3 Extract `providerHeaders(provider, sessionID?)` in `llm-config.ts`;
  test-first for opencode (`x-opencode-session`, user-agent) vs others.
- [ ] 1.4 Rewrite `TokenProxy` provider plumbing; remove
  `PROXY_PROVIDER_URL`/`PROXY_PROVIDER_KEY` from `.env.example` and compose
  files.
- [ ] 1.5 Test: a panel config change between two calls is picked up
  (per-call resolution), no restart.

## 2. Backend — remove purpose routing (F2)

- [ ] 2.1 Amend `migrations/057_proxy_purpose_routes.sql`: no table, no seed,
  audit enum widening keeps only pre-existing actions; adjust `check_db_schema`
  / schema fixtures if they reference 057 artifacts.
- [ ] 2.2 Remove `PROXY_PURPOSES`, `isProxyPurpose`, `purposeRoute`,
  `purposeRouteTable` from `proxy-policy.ts`; `canonicalProxyRequest` takes
  `(input, model, outputCap)` — update pure tests first.
- [ ] 2.3 Remove `x-palique-purpose` parsing, `PROXY_REQUIRE_PURPOSE`,
  `unknown_purpose`, `purpose_required` from `app.ts`; `relay()` loses the
  purpose parameter; update `tests/token-proxy.test.ts` and
  `tests/proxy-quota.test.ts`.
- [ ] 2.4 Remove `routes`/`set-route` from `tokens-admin.ts` and its usage
  text; audit action enum constant list updated.
- [ ] 2.5 Drop the `purpose` label from `palique.proxy.provider.duration`
  (`metrics.ts` usage sites); update `docs/observability.md` label contract
  and alerts referencing it (run `make obs-check`).

## 3. Backend — drop conversation-policy (F4)

- [ ] 3.1 Remove `GET /v1/conversation-policy` route and delete
  `src/conversation-policy.ts`; its tests are removed with it.
- [ ] 3.2 Update `docs/token-billing.md`: purpose header/errors/env rows
  removed, provider documented as panel-managed.
- [ ] 3.3 Update `docs/local-android-lab.md`: the `api` engine section now
  points at the admin panel config instead of `PROXY_PROVIDER_URL`/
  `PROXY_PROVIDER_KEY`, and drops the purpose-route/token-admin steps.

## 4. Android — engine without policy (F4) 

- [ ] 4.1 Test-first (JVM): `api` engine builds a body without `model` and
  without `x-palique-purpose`; call proceeds with no prior policy fetch.
- [ ] 4.2 Remove policy wiring: `ConversationPolicyStore`,
  `refreshConversationPolicy`/`ensureConversationPolicy` + retry plumbing,
  `MinuteCommerceClient.conversationPolicy`, policy-based `modelOverride`
  for the `api` engine; BYOK engines unchanged (unit tests still green).
- [ ] 4.3 `./gradlew :app:testDebugUnitTest :app:lintDebug` green; no ES/EN
  string changes needed (verify no policy strings remain in `strings.xml`).

## 5. Verification

- [ ] 5.1 `cd services/api && npx tsc --noEmit`
- [ ] 5.2 `make test-api` (ephemeral PG) green, incl. updated proxy suites
- [ ] 5.3 `make check` (exporters, cross-platform, db schema)
- [ ] 5.4 Lab E2E (manual, `docs/local-android-lab.md` flow): panel config
  with an OpenCode free model → phone conversation via the `api` engine;
  record outcomes and screenshots in `verify-report.md`.
- [ ] 5.5 Update `verify-report.md` (what ran, what did not) and reconcile
  the `proxy-api-provider` spec deltas touched by this change.

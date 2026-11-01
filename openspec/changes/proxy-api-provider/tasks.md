# Tasks: proxy-api-provider

## Task 1 — Reservation lifecycle (backend, audit F05)

- [x] 1.1 Test-first: concurrent admission against one balance cannot double-
      spend (two connections, real PG); released hold restores available;
      unknown keeps the hold; settle+release exactly-once per attempt id.
- [x] 1.2 Implemented on `proxy-attempts.ts` + `ledger.ts`: reserve at
      prepare (attempt + `reservations` + ledger `reserve` in one wallet-
      locked transaction), combined settle+release entry, hold release on
      rejection, `releaseProxyHold` for reconciliation. Uses the existing
      `reserve`/`release` ledger kinds and `wallets.reserved_nano`.
- [x] 1.3 Unlocked balance read removed from admission; estimate bound comes
      from the routed model's output cap.
- [x] 1.4 `tokens-admin release-hold` (stdin JSON, audited as
      `release_hold`).
- [x] 1.5 Docker suite green; token-proxy tests rewritten to reservation
      semantics.

## Task 2 — Request policy + purpose routing (backend, audit F06–F07)

- [x] 2.1 Test-first: disallowed fields → `400 request_policy_violation`
      (closed metric label, nothing reserved/dispatched); client
      `model`/`max_tokens` accepted but overridden; `stream:true` still `400
      streaming_unsupported_v1` and checked first.
- [x] 2.2 Canonical request builder (`src/proxy-policy.ts`): allowlist
      `messages`, `response_format` json_object, `temperature`; server sets
      `model`, `max_tokens`, `stream:false`.
- [x] 2.3 Migration `057_proxy_purpose_routes` + behavior-preserving seed
      (all purposes → MiniMax-M2) + `tokens-admin routes` / `set-route`
      (audited).
- [x] 2.4 `x-palique-purpose` validation (enum; unknown → `400
      unknown_purpose`; missing → legacy route; `PROXY_REQUIRE_PURPOSE`
      flag). Route decision before reservation.
- [x] 2.5 Docker suite green.

## Task 3 — Daily quota (backend)

- [x] 3.1 Migration `058_subscriptions` (`token_subscriptions` — the hosted-
      minutes `subscriptions` name was taken); daily UTC grant job writing
      idempotent ledger entries `subscription-grant:<date>:<account>` at the
      active rate version; free allowance `PROXY_FREE_DAILY_*_TOKENS`
      (default 0 = off).
- [x] 3.2 Test-first: grant idempotency; guest exclusion; exhaustion via the
      balance admission; policy endpoint limits per plan.
- [x] 3.3 `tokens-admin grant-quota` (audited) + `setSubscription`.
- [x] 3.4 Docker suite green.

## Task 4 — Policy endpoint + observability (backend)

- [x] 4.1 `GET /v1/conversation-policy` (session auth, in `accountPaths`;
      quota state + model labels per purpose; display-only). Tested at the
      function level (`proxy-quota.test.ts`); the HTTP route is a thin
      authenticate+call wrapper.
- [x] 4.2 `palique.proxy.provider.duration{purpose}` label (closed enum +
      `legacy`) + `palique_proxy_request_policy_rejected{field}` (closed
      labels); alerts `ProxyUnknownAttemptsGrowing` and
      `ProxyPolicyViolationsSpike` in `telemetry/alerts.yml`; `make
      obs-check` green.
- [x] 4.3 `docs/token-billing.md` rewritten (reservation, policy, routing,
      quota, error matrix, CLI) and `services/api/docs/api-capacity.md`
      updated; duplicated "Metering" heading removed.

## Task 5 — Android `api` provider

- [x] 5.1 JVM tests: purpose header (incl. null → `typed_reply`), policy
      model per purpose, clear failure with empty policy, session bearer,
      `keyEnvVar == null`, no env key.
- [x] 5.2 `RemoteEngineConfig.API` + factory row `api` ("Palique API") +
      `ConversationPolicyStore` (policy snapshot with quota) + refresh via
      `MinuteCommerceClient.conversationPolicy` on account changes.
- [x] 5.3 Purpose header plumbing in `OpenAICompatibleEngine.respond`
      (config-gated so deepseek/glm/opencode stay header-free).
- [x] 5.4 No new strings needed: the engine label comes from the factory and
      no quota UI was added in this change (display deferred; the policy
      state already carries `quotaRemaining`).

## Task 6 — Android release gating

- [x] 6.1 `defaultEngineId` (release falls back from the BYOK build default
      to `api`) + `localEngines` filters to proxy-backed providers on
      release. Pinned by the debug-path unit tests; the release branch
      itself is a BuildConfig flag (verified by code review, not a JVM test).
- [x] 6.2 Selector gating by build type; `validConversationEngines` gains
      `api`; `docs/conversation-engines.md` documents the benchmarking
      workflow.
- [x] 6.3 `:app:testDebugUnitTest` + `:app:lintDebug` + `assembleDebug`
      green.

## Task 7 — iOS parity (macOS gate)

- [x] 7.1 `ApiProxyEngine.swift` mirror (purpose enum, purpose header,
      policy-resolved model, `missingModel` failure). The policy cache and
      release gating live in the App target, which does not compile on
      Linux — App-side parity is pending the macOS gate.
- [ ] 7.2 `swift test --package-path apps/ios` + `make ios-test` on macOS
      (Linux cannot compile the Core package's engines gate — record
      honestly in verify-report.md).

## Task 8 — Close-out

- [ ] 8.1 Load profile: add purpose-labeled proxy traffic to the Android
      session route profile (`loadtest.mjs`); `make load-api-smoke` green.
- [x] 8.2 verify-report.md written (see below).
- [x] 8.3 Pilot readiness documented in `docs/token-billing.md` (grant-quota
      flow, rollback = remove `PROXY_PROVIDER_URL`, `unknown`-hold runbook
      in `api-capacity.md`).

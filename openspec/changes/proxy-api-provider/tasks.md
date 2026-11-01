# Tasks: proxy-api-provider

## Task 1 — Reservation lifecycle (backend, audit F05)

- [ ] 1.1 Test-first: concurrent admission against one balance cannot double-
      spend (two connections, real PG); released hold restores available;
      unknown keeps the hold; settle+release exactly-once per attempt id.
- [ ] 1.2 Implement `reserveProxyAttempt` / `settleProxyAttempt` (settle and
      release in one transaction) / `releaseProxyHold` on
      `proxy-attempts.ts` + `ledger.ts` using `reserve`/`release` ledger
      kinds and `wallets.reserved_nano`.
- [ ] 1.3 Remove the unlocked balance read from admission; estimate bound
      comes from the routed model's output cap.
- [ ] 1.4 `tokens-admin release-hold <attemptID> --actor --reason` (audited).
- [ ] 1.5 Docker suite green; existing token-proxy tests updated to the
      reservation admission (bounded-grace tests rewritten to reservation
      semantics).

## Task 2 — Request policy + purpose routing (backend, audit F06–F07)

- [ ] 2.1 Test-first: disallowed fields → `400 request_policy_violation`
      (field named, nothing reserved/dispatched); client `model`/`max_tokens`
      never reach the provider; `stream:true` still `400
      streaming_unsupported_v1` and checked first.
- [ ] 2.2 Canonical request builder (allowlist: `messages`, `response_format`
      json_object, `temperature`; server sets `model`, `max_tokens`,
      `stream:false`).
- [ ] 2.3 Migration `057_proxy_purpose_routes` + seed (`typed_reply` →
      primary model; rest → free/flash model) + `tokens-admin routes
      list/set` (audited).
- [ ] 2.4 `x-palique-purpose` header validation (enum; unknown → `400
      unknown_purpose`; missing → legacy route; `PROXY_REQUIRE_PURPOSE`
      flag). Route decision before reservation.
- [ ] 2.5 Docker suite green.

## Task 3 — Daily quota (backend)

- [ ] 3.1 Migration `058_subscriptions`; daily UTC grant job writing
      idempotent ledger entries `subscription-grant:<date>:<account>` at the
      active rate version; free-allowance constants in config.
- [ ] 3.2 Test-first: grant idempotency across restarts; exhaustion → 402
      next call; guest rejection unchanged; rate change affects only future
      grants.
- [ ] 3.3 `tokens-admin grant-quota <account> --days N --plan pilot`
      (audited) + cancel.
- [ ] 3.4 Docker suite green.

## Task 4 — Policy endpoint + observability (backend)

- [ ] 4.1 `GET /v1/conversation-policy` (session auth; quota state + model
      labels per purpose; display-only) + tests.
- [ ] 4.2 `palique.proxy.provider.duration{purpose}` label (closed enum) +
      `palique_proxy_request_policy_rejected_total{field}`; alerts in
      `telemetry/alerts.yml` (unknown attempts growth, proxy 5xx ratio);
      `make obs-check` green.
- [ ] 4.3 Update `docs/token-billing.md` (reservation, policy, routing,
      quota) and `services/api/docs/api-capacity.md`; dedupe the duplicated
      "Metering" heading.

## Task 5 — Android `api` provider

- [ ] 5.1 Test-first (JVM): engine sends `x-palique-purpose` on every call
      (purpose mapping incl. null → `typed_reply`), uses the policy model,
      fails clearly with empty policy, 402 → quota surface mapping, session
      bearer like hosted-proxy, no `keyEnvVar`.
- [ ] 5.2 `RemoteEngineConfig.API` + factory row `api` ("Palique API") +
      `ConversationPolicyStore` (cache of `GET /v1/conversation-policy`,
      refresh on start and on 402) + `ConversationEngineDeps` wiring.
- [ ] 5.3 Purpose header plumbing in `OpenAICompatibleEngine.respond`
      (config-gated so deepseek/glm/opencode stay header-free).
- [ ] 5.4 Strings EN/ES (`provider_api_label`, quota hint) with
      `check_cross_platform.py` parity.

## Task 6 — Android release gating

- [ ] 6.1 Test-first: release configuration exposes only proxy-backed
      providers in `localEngines` and defaults the build engine to `api`;
      debug keeps the full list (regression: existing engine tests pinned to
      debug behavior still pass).
- [ ] 6.2 Settings selector gating by build type; `validConversationEngines`
      gains `api`; docs (`conversation-engines.md`) updated with the
      benchmarking workflow.
- [ ] 6.3 `make android-build` (tests + lint + APK) green.

## Task 7 — iOS parity (macOS gate)

- [ ] 7.1 Swift Core mirror of 5.1–5.3 and 6.1 (engine `api`, purpose
      header, policy cache, release gating).
- [ ] 7.2 `swift test --package-path apps/ios` + `make ios-test` on macOS;
      record the gate honestly in verify-report.md if run on Linux.

## Task 8 — Close-out

- [ ] 8.1 Load profile: add purpose-labeled proxy traffic to the Android
      session route profile; `make load-api-smoke` green.
- [ ] 8.2 verify-report.md: what ran where (docker suite, JVM, emulator,
      macOS/iOS gates), and the audit-token-proxy-safety absorption note
      (archive that change).
- [ ] 8.3 Pilot readiness checklist: `tokens-admin grant-quota` pilot flow,
      rollback (remove `PROXY_PROVIDER_URL`), runbook for `unknown` holds.

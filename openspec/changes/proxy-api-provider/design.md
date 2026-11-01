# Design: proxy-api-provider

## 1. Reservation before dispatch (audit F05)

The wallet already carries `reserved_nano` and the ledger already defines the
kinds `reserve` and `release` (migration 001) — unused until now. The proxy
uses them:

1. After admission checks (concurrency, unlocked, rate version), the proxy
   computes the conservative estimate it already uses (`inputEstimate` +
   `PROXY_MAX_TOKENS`) and, **under the wallet lock in one transaction**,
   checks `balance − reserved ≥ estimate` and writes a `reserve` ledger entry
   with `reserved_delta_nano = estimate` (reference
   `proxy-reserve:<attemptID>`, idempotent). Estimate exceeds available →
   `402 token_balance_exhausted`, nothing dispatched.
2. On settled success: `settle` actual usage **and** `release` the remaining
   reservation in the same transaction (both keyed to the attempt id, so the
   sequence is replay-safe).
3. On rejected upstream (400/401/403/429 proven not dispatched work):
   `release` the full hold.
4. On `unknown` (timeout, disconnect, oversize, invalid usage): the hold is
   **kept**. It is released only by the reconciliation procedure (operator
   CLI `tokens-admin release-hold <attemptID> --actor --reason`, writing
   `token_admin_audit`). This keeps "money possibly spent" funded instead of
   fabricating or erasing cost.

The plain unlocked balance read disappears from admission; `availableBalance`
stays only for the wallet endpoint display. Cross-process correctness comes
from the wallet row lock; the in-memory per-account cap remains a fairness
mechanism, not a financial invariant.

Legacy grace note: the old "single call may settle beyond balance" grace is
replaced by the reservation bound — a call is never dispatched unless its
estimate is funded, and settled cost can only exceed the estimate if the
provider returns usage above `PROXY_MAX_TOKENS` output, which the request
policy now makes impossible (server-set cap forwarded to the provider).

## 2. Request policy (audit F06–F07)

`#meteredRelay` replaces verbatim forwarding with a canonical request built
server-side:

- Allowed input fields: `messages` (required, array), `response_format`
  (optional, `{type:"json_object"}` only), `temperature` (optional, 0–2).
  Anything else → `400 request_policy_violation` naming the field (no
  silent reinterpretation). `stream:true` keeps its dedicated
  `400 streaming_unsupported_v1` (checked first, unchanged).
- `model`: always overridden by the routing decision; a client-supplied
  `model` is ignored (logged at debug for contract drift detection).
- `max_tokens`: server-set to the route's output cap (replaces the client
  trust in `PROXY_MAX_TOKENS`; the config value becomes the default cap).
- `stream`: forced `false`.

This is what makes "what we price" and "what we forward" the same request.

## 3. Purpose routing

- New header `x-palique-purpose`, values fixed to the `HelperPurpose` wire
  enum (`typed_reply`, `meaning`, `assessment`, `lookup`, `topic`, `help`,
  `scenario_review`, `delegation`). Unknown value → `400 unknown_purpose`.
- Missing header → **legacy route** (today's behavior: provider default
  model). A server flag (`PROXY_REQUIRE_PURPOSE=1`) flips it to required
  once fleet coverage allows.
- New table (migration `057_proxy_purpose_routes`):

```sql
CREATE TABLE proxy_purpose_routes (
  purpose text PRIMARY KEY CHECK (purpose IN ('typed_reply','meaning','assessment','lookup','topic','help','scenario_review','delegation')),
  model text NOT NULL,
  output_cap integer NOT NULL CHECK (output_cap BETWEEN 64 AND 65536),
  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

Seeded: `typed_reply → <primary strong model>`; every other purpose →
`<free/flash model>`. The provider URL/key stay the single configured
upstream (`PROXY_PROVIDER_URL`); per-purpose *providers* are a later
extension (the route row already isolates the model choice, which is the
cost lever). Editing is operator-only via
`tokens-admin routes set <purpose> --model --cap` (audited) — no HTTP route
mutates routing.

The route decision happens after auth, before reservation: the reservation
estimate uses **the routed model's** output cap and the active rate version.

## 4. Daily quota (subscription seam)

```sql
CREATE TABLE subscriptions (
  account_id bigint PRIMARY KEY REFERENCES accounts(id),
  plan text NOT NULL,
  daily_input_tokens bigint NOT NULL CHECK (daily_input_tokens > 0),
  daily_output_tokens bigint NOT NULL CHECK (daily_output_tokens > 0),
  active_until timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

- A daily job (server boot-strapped interval, UTC midnight, same pattern as
  the funding resets) grants each active subscription its allowance as a
  ledger `purchase` entry with reference
  `subscription-grant:<YYYY-MM-DD>:<accountID>` (idempotent by the unique
  ledger reference; retried safely). Tokens ≤ the free daily allowance come
  from the same mechanism with the allowance constants in config — one code
  path, two plan sizes.
- Quota is **nanoUSD-agnostic on purpose?** No: the grant is expressed in
  tokens and converted at the active rate version into nanoUSD credit at
  grant time, so the wallet/ledger/admission machinery is reused unchanged
  and a rate change only affects grants from that day on.
- Exhaustion surfaces as the existing `402 token_balance_exhausted` — no new
  error code is needed for the daily case (the client already routes 402 to
  the commerce/subscription surface). The policy endpoint distinguishes
  balance vs daily usage for display.
- Pilot grants: `tokens-admin grant-quota <account> --days N --plan pilot`
  (audited); cancel = clear `active_until`.
- Guest accounts keep today's rejection (no quota, no proxy).

## 5. Policy endpoint

`GET /v1/conversation-policy` (session auth):

```json
{ "quota": { "limitTokens": 460000, "usedTokensToday": 81234, "resetsAtUtc": "…",
             "availableNanoUSD": "…" },
  "models": { "typed_reply": "GLM-5.3", "meaning": "Beam Free", "…": "…" } }
```

Display only. `models` labels come from `proxy_purpose_routes` joined with a
display-name column; they never authorize anything. The Android settings UI
uses it for the "remaining today" hint; the `api` engine does not need it to
function (server sets the model per call).

## 6. App provider "api"

- `RemoteEngineConfig.API = RemoteEngineConfig("api", "", "", "")` — model
  and output cap are server-owned; the engine sends whatever the policy says
  (defaulting to omitting `model`, which the legacy route would reject, so
  the engine always resolves a model from the policy cache; empty policy →
  engine error, never a guessed model).
- Construction mirrors `hostedProxy` (same origin, `v1/proxy/responses`,
  session bearer credential) **minus** `proxyErrorMessages` commerce gating —
  a 402 from `api` means quota, surfaced as the subscription/upsell surface
  when it exists.
- Every call adds `x-palique-purpose: <purpose.wireValue>` — `respond()`
  already receives `HelperPurpose`; the adapter maps it, null purpose maps
  to `typed_reply` (the dominant case) so the header is always present.
- Factory registry gains `"api"` (`EngineInfo("api", "Palique API")`),
  `available` keeps every existing row, and `ConversationEngineDeps` gains
  the policy lookup (a small `ConversationPolicyStore` mirroring
  `EngineModelsStore`: cached `GET /v1/conversation-policy` refreshed on app
  start and on 402, persisted in SharedPreferences for offline display).
- The provider selector stays fully functional in **debug** builds (this is
  the benchmarking surface: direct deepseek/glm/opencode vs api round-trip
  timings). Release builds: `localEngines` filters to `api` (+ hosted-proxy
  while the tokens flow remains), the build-time engine defaults to `api`,
  and the selector row for BYOK engines is hidden.

## 7. Observability

- `palique.proxy.provider.duration` gains a `purpose` label (closed enum);
  new counter `palique.proxy.request_policy_rejected_total{field}` (closed
  cardinality: field names from the allowlist, never raw input).
- Alerts (same change): `proxy_unknown_attempts_total` growth and 5xx ratio
  on the proxy route.
- No PII: purpose and field names only.

## 8. Security

- Keys stay in `PROXY_PROVIDER_*` (server-only); the app provider carries no
  key material and `keyEnvVar` is null.
- Purpose header and client `model` are untrusted: routing and pricing are
  resolved server-side from tables the client cannot write.
- Reservation closes the concurrent-overspend gap; reconciliation keeps
  uncertain outcomes auditable without touching the immutable ledger.
- The route/policy/admin mutations are CLI-only with `token_admin_audit`.

## 9. Test strategy (test-first per repo policy)

- Reservation: concurrent settles from two connections cannot both pass
  admission against the same available funds; released hold restores
  available; unknown keeps the hold; exactly-once per attempt id.
- Policy: each disallowed field → 400 with field name; `model`/`max_tokens`
  client values never reach the provider (asserted on the stub); purpose
  validation; legacy no-header path unchanged.
- Routing: per-purpose model selection asserted on the stub provider;
  routes CLI audited.
- Quota: grant idempotency across restarts; exhaustion → 402; free
  allowance; guest rejection unchanged.
- App (JVM): `api` engine sends purpose header + policy model, 402 mapping,
  offline policy cache; factory/gating tests; strings parity via
  `check_cross_platform.py`.

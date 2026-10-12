## Exploration: Audit token proxy safety

### Current State
`POST /v1/proxy/responses` has a 32,768-byte inbound body limit, requires an authenticated account and an unlock entitlement, and is disabled unless both provider URL and key are configured. The route passes the parsed object to `TokenProxy.relay`.

`relay` rejects only `stream: true`, then takes an in-memory per-account slot (default two, scoped to one API process). It reads `balance_nano - reserved_nano`, loads the latest global token rate, and admits when an estimate fits the observed balance. The estimate uses `JSON.stringify(body).length / 4 + 256` input tokens plus configured `PROXY_MAX_TOKENS`, but the provider receives the original body unchanged. Consequently `model`, `max_tokens`, `n`, and other provider work controls are neither normalized nor tied to the estimate or rate. The native clients currently send the narrower shape `{ model: "MiniMax-M2", messages, response_format?, stream: false }`, which provides a compatible allowlist baseline but does not protect the public route from other authenticated clients.

After admission, `fetch` has no abort signal. Provider error bodies are drained with unbounded `text()` and successful bodies with unbounded `json()`. Fastify's 15-second request timeout is not an outbound cancellation deadline. A hung provider therefore retains the slot, and a large response can consume unbounded memory before JSON parsing.

On success, provider token counts are validated and charged at the rate captured before the call. Settlement uses `min(actual cost, pre-call balance)` and then appends a ledger delta inside a wallet-locking transaction. The row lock serializes writes but does not revisit or reserve the admission funds. Two calls (or processes) can therefore pass on the same balance and each settle from that stale snapshot. This is a proxy admission/accounting race; it does **not** establish that balance debt is forbidden throughout the ledger domain. The documented single-call floor-to-zero grace is separate from the concurrent stale-admission defect.

Existing counterevidence is meaningful but incomplete: authentication, unlock gating, the body limit, non-streaming gate, positive-balance estimate check, per-process concurrency cap, strict usage parsing, durable successful `proxy_usage`, and wallet row locks all reduce exposure. Existing reservation primitives and the hosted paid-provider flows demonstrate reserve/settle/release and uncertain-attempt patterns. However, the generic legacy `reserve()` pins a different rate constant and is not directly suitable for the token proxy without adapting the rate and lifecycle.

The audit's mock probes confirmed unchanged forwarding and two stale-balance admissions, and observed that no `AbortSignal` was supplied. They did not exercise PostgreSQL concurrency/cross-process behavior, a live provider, real tariffs, provider cancellation semantics, or deployment configuration. Those claims remain acceptance-test targets, not completed proof.

### Affected Areas
- `services/api/src/token-proxy.ts` — request policy, estimate, admission, provider transport, usage validation, and settlement all meet here.
- `services/api/src/app.ts` — proxy endpoint admission and client-disconnect signal propagation; its request timeout does not cancel provider work.
- `services/api/src/ledger.ts` — existing wallet locking and reservation operations are reusable patterns, but proxy reservations need the active token rate and explicit attempt lifecycle.
- `services/api/migrations/001_billing.sql` — wallet and reservation invariants permit debt generally; this change should not redefine that domain policy.
- `services/api/migrations/025_billing_tokens.sql` — token rate versions currently price input/output globally and do not identify an allowed provider model.
- `services/api/migrations/040_proxy_usage.sql` — records only successful metered relays; uncertain/aborted attempts need durable evidence elsewhere or an additive lifecycle extension.
- `services/api/tests/token-proxy.test.ts` — existing PostgreSQL-backed coverage proves metering, single-call grace, streaming rejection, and an in-process cap, but lacks request-policy, cross-process reservation, timeout, oversized-body, and uncertainty cases.
- `services/api/src/main.ts`, `services/api/.env.example`, `docs/token-billing.md` — proxy construction, new bounded transport configuration, operator contract, containment, and rollout documentation.
- `apps/android/app/src/main/java/com/jkdc/app/core/engines/OpenAICompatibleEngine.kt` and `apps/ios/Core/HostedProxyEngine.swift` — current request shapes are compatibility evidence; source changes are not presently required if the allowlist preserves their fields.

### Approaches
1. **Validation and reservation only** — Reject unsupported request fields/models, inject a hard output cap, reserve the estimate transactionally, and use a fixed fetch deadline and bounded reader.
   - Pros: Smallest change; closes the direct forwarding and concurrent stale-admission paths.
   - Cons: A timeout, disconnect, oversized success, malformed success, or usage above the hold still lacks a durable state that distinguishes safe release from possible provider spend; operational reconciliation remains guesswork.
   - Effort: Medium

2. **Normalized request plus durable attempt lifecycle** — Build one canonical provider request, calculate and reserve its conservative maximum cost atomically, create a durable attempt record before outbound work, then settle, release, or mark uncertain exactly once. Apply a deadline and byte-bounded reader, with client disconnect shortening work but never silently releasing an ambiguous hold.
   - Pros: Addresses F05/F06/F07 together; works across processes; preserves audit lineage; makes retry and operator reconciliation explicit; reuses established hosted-provider accounting patterns.
   - Cons: Requires an additive migration, lifecycle/idempotency rules, operational handling for uncertain holds, and more PostgreSQL integration coverage.
   - Effort: High

3. **Serialize or lower the in-process cap** — Set concurrency to one and rely on current settlement plus a fetch timeout.
   - Pros: Minimal operational change.
   - Cons: Does not protect multiple API processes, does not bind forwarded work to pricing, and still cannot account honestly for ambiguous provider outcomes. It mitigates symptoms rather than fixing the invariant.
   - Effort: Low

### Recommendation
Use approach 2. Define a server-owned v1 request policy that accepts the native-client shape only: canonical model, bounded messages/input, optional supported `response_format`, `stream: false`, one completion, and a server-injected output maximum. Reject rather than silently reinterpret unsupported model/work-multiplicity fields so callers can correct requests and the priced request is byte-for-byte the forwarded policy result.

Reserve the full conservative bound under the wallet lock before calling the provider. The hold must use the same model/rate version and normalized limits as forwarding; do not add a global nonnegative-balance constraint or claim that ledger debt is universally invalid. If observed usage is within the hold, settle actual usage and release the remainder atomically. Explicit provider rejection before accepted work may release. Deadline, disconnect, truncated/oversized success, malformed success, or usage outside the admitted bound should retain funding and mark the attempt uncertain for reconciliation instead of assuming no charge or double-charging a retry.

Read provider bodies incrementally with a configured maximum, abort on the configured deadline, and always free the in-process slot in `finally`. Propagate client disconnect as an additional abort cause, while keeping the durable accounting result based on whether provider spend is knowable. Configuration values must be validated positive integers with safe defaults.

Safety and rollout: add schema first, deploy policy/transport/accounting together, and test with a local stub plus real PostgreSQL concurrency. The immediate containment switch remains removal of `PROXY_PROVIDER_URL` or `PROXY_PROVIDER_KEY`, which disables the route. Rollback may restore prior application code while leaving additive attempt data in place; migrations and uncertain holds must not be deleted or auto-released. Document an operator reconciliation path before enabling the hardened proxy.

Product/domain gaps for the proposal to resolve:
- Confirm `MiniMax-M2` as the sole v1 model and bind it to a rate-version policy, or define an explicit model-to-rate allowlist.
- Confirm the exact accepted request fields and limits; current first-party clients need `model`, `messages`, optional `response_format: {type: "json_object"}`, and `stream: false`, but do not send an output-limit field.
- Choose deadline and maximum response bytes from provider characteristics; repository evidence supports the need for bounds, not their numeric values.
- Define who reconciles uncertain attempts, the evidence used, and when a hold may be settled or released. Automatic expiry alone is unsafe because abort does not prove the provider performed no work.
- Decide whether request idempotency is client-supplied or server-owned. A retry after an ambiguous attempt must not create a second provider charge without an explicit policy.

### Risks
- A too-narrow allowlist can break non-native clients; current Android/iOS payloads provide evidence, not a complete inventory of external consumers.
- A token estimate is not a safe reservation unless its input bound is conservative for the chosen model and the server-enforced output/multiplicity bounds are included.
- Releasing a reservation on abort can serve paid work for free; retaining every abort forever can strand balances without reconciliation operations.
- Provider usage can contradict the admitted bound. Treat that as an accounting anomaly, not normal grace.
- In-memory concurrency remains useful for resource fairness but is not a financial invariant and cannot replace a database reservation.
- Existing mock evidence cannot validate PostgreSQL locking, cross-process behavior, or provider billing after cancellation.

### Ready for Proposal
Yes. The proposal can define the safety invariant and phased scope now, while carrying the five product/domain gaps above as explicit decisions to settle before the spec/design fixes numeric limits and uncertain-attempt operations.

# Proposal: Audit Token Proxy Safety

## Intent

Harden `POST /v1/proxy/responses` so that admitted, forwarded, and charged provider work share one server-owned policy. The current implementation forwards caller-controlled provider parameters unchanged, admits concurrent work without reserving funds, and has no abortable provider deadline or bounded response reader. Audit findings F05–F07 establish these defects through source analysis and mock probes, but do not prove production exposure, PostgreSQL cross-process behavior, live-provider cancellation semantics, or real tariffs.

## Scope

### In Scope

- Define and enforce a versioned request policy that normalizes the accepted proxy payload, rejects unsupported work controls, and binds the forwarded model and limits to the rate used for admission.
- Atomically reserve a conservative maximum charge under the wallet lock before provider work, then settle, release, or retain the hold exactly once according to a durable proxy-attempt lifecycle.
- Add an abortable provider deadline, client-disconnect cancellation, bounded incremental response reading, and reliable in-process slot release.
- Persist enough bounded operational evidence to reconcile uncertain attempts without storing credentials, transcripts, or raw provider payloads.
- Add focused policy and transport tests, plus PostgreSQL-backed concurrent and cross-process accounting tests; document configuration, containment, rollout, and reconciliation responsibilities.

### Out of Scope

- Redefining the ledger domain to prohibit negative balances globally or changing the existing single-call floor-to-zero grace outside this proxy workflow.
- Supporting streaming responses, arbitrary models, caller-selected multiplicity, or other unrestricted provider controls.
- Changing Android or iOS request code unless the finalized server policy proves incompatible with their current request shapes.
- Selecting numerical deadline, response-body, message, token, or other resource limits without provider and operational evidence.
- Claiming live-provider billing, deployment exposure, PostgreSQL concurrency, or cancellation behavior until the corresponding integration checks run.

## Capabilities

### New Capabilities

- `token-proxy-safety`: Defines normalized paid-proxy admission, rate-bound reservation, durable attempt outcomes, bounded provider transport, reconciliation, and operational containment.

### Modified Capabilities

None. No existing OpenSpec capability currently covers this behavior.

## Approach

Adopt the exploration's normalized-request plus durable-attempt-lifecycle approach. Construct one canonical provider request from an explicit v1 allowlist, reject unsupported fields instead of silently reinterpreting them, and calculate a conservative reservation from the same normalized model, rate version, input bound, output bound, and multiplicity that will be forwarded.

Create the attempt and reserve its maximum charge atomically under the wallet lock before outbound work. Successful metered work settles actual usage and releases the remainder exactly once. A provider rejection that proves work was not accepted may release its hold. Outcomes where provider spend cannot be known—including relevant deadlines, disconnects, truncated or oversized bodies, malformed success bodies, or usage outside the admitted bound—remain funded and enter an explicit uncertain state for reconciliation. The design and specification must settle idempotency and reconciliation policy before implementation.

Read provider responses incrementally with configured bounds and use a composed abort signal for the outbound deadline and client disconnect. Always release the in-process concurrency slot in `finally`; retain that mechanism for resource fairness, not as a financial invariant. Add schema first, then deploy request policy, transport bounds, accounting lifecycle, configuration, and operator documentation as one coordinated behavior change.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `services/api/src/token-proxy.ts` | Modified | Normalize requests, price admitted work, manage bounded transport, and drive attempt outcomes. |
| `services/api/src/app.ts` | Modified | Validate route input and propagate client disconnect cancellation. |
| `services/api/src/ledger.ts` | Modified | Add rate-aware proxy reservation and idempotent settlement/release operations without changing global debt policy. |
| `services/api/migrations/` | New/Modified | Add an additive durable proxy-attempt lifecycle and the indexes or constraints required for exactly-once transitions. |
| `services/api/tests/token-proxy.test.ts` | Modified | Cover request policy, bounds, cancellation, uncertainty, and PostgreSQL concurrency. |
| `services/api/src/main.ts` | Modified | Validate and inject bounded proxy transport and policy configuration. |
| `services/api/.env.example` | Modified | Document required proxy safety settings without choosing unsupported values in this proposal. |
| `docs/token-billing.md` | Modified | Document invariants, uncertain-attempt reconciliation, containment, rollout, and rollback. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| A narrow allowlist breaks an unobserved client. | Medium | Inventory consumers, preserve the proven native-client fields, version the policy, and reject unsupported requests with actionable errors. |
| Reservation underestimates provider work. | Medium | Derive reservation and forwarding from the same canonical request and treat usage beyond the admitted bound as an accounting anomaly. |
| Abort handling releases money for provider work that may have been charged. | High | Retain the hold and mark the attempt uncertain whenever provider spend is not provably absent. |
| Uncertain holds strand customer funds. | Medium | Define owner, evidence, audit trail, and explicit idempotent settlement/release operations before rollout; do not rely on automatic expiry alone. |
| Database transitions race across API processes or retries. | Medium | Enforce atomic wallet locking and exactly-once state transitions, verified with real PostgreSQL concurrent and cross-process tests. |
| Bounds are either ineffective or disruptive. | Medium | Select numerical values from provider characteristics and observed payloads during spec/design; validate configuration as positive integers and test boundary behavior. |

## Unresolved Decisions

- Confirm whether v1 supports only `MiniMax-M2` or an explicit model-to-rate allowlist.
- Confirm the complete accepted request-field policy and resource limits while preserving the current first-party fields: `model`, `messages`, optional `response_format: { "type": "json_object" }`, and `stream: false`.
- Select the outbound deadline and maximum response bytes from provider and operational evidence; no numerical values are authorized by the audit.
- Define who reconciles uncertain attempts, what evidence is sufficient, and when a hold may be settled or released.
- Decide whether idempotency is client-supplied or server-owned and how retries after ambiguous attempts avoid duplicate provider charges.

These decisions block final behavioral specifications for the affected details, but do not block the architectural design of policy normalization, atomic reservation, bounded transport, or durable attempt states.

## Rollback Plan

Immediate containment remains removal of `PROXY_PROVIDER_URL` or `PROXY_PROVIDER_KEY`, which disables the route. If rollout fails, disable the route first, then revert application behavior while leaving the additive attempt schema and historical attempt records intact. Do not delete uncertain attempts or automatically release their holds during rollback; reconcile them under the documented operator procedure before re-enabling the proxy or removing later-compatible schema.

## Dependencies

- Product confirmation of the supported model/rate mapping and accepted request fields.
- Provider and operational evidence for transport and body-size bounds.
- A defined reconciliation owner and idempotency policy for uncertain attempts.
- A real PostgreSQL environment for concurrency, retry, and cross-process acceptance tests.
- A controllable local provider stub for deadline, disconnect, oversized, malformed, and contradictory-usage fixtures.

## Success Criteria

- [ ] Unsupported models, fields, multiplicity, streaming, and excessive caller work controls are rejected before any provider request.
- [ ] The request used for estimation, reservation, rate selection, and provider forwarding has identical server-owned policy parameters.
- [ ] Concurrent and cross-process PostgreSQL tests prove that admitted proxy work cannot reuse the same available funds.
- [ ] Every attempt reaches one durable, idempotent outcome: settled, safely released, or uncertain with its hold retained for reconciliation.
- [ ] Hung, disconnected, malformed, truncated, oversized, and contradictory-usage provider fixtures reach bounded terminal states and always release the in-process slot.
- [ ] Retries cannot double-charge or create duplicate provider work under the finalized idempotency policy.
- [ ] Configuration validation, containment, rollout, rollback, and uncertain-attempt reconciliation are documented and exercised where locally possible.
- [ ] Verification reports distinguish mock or local-stub evidence from real PostgreSQL, live-provider, deployment, native-client, and remote-CI proof.

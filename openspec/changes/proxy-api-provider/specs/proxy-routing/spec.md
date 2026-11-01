# Delta Spec: proxy-routing

## ADDED Requirements

### Requirement: Server-owned model per purpose

The proxy SHALL select the forwarded model (and output cap) server-side from
a per-purpose routing table, using the client-supplied purpose header as
routing advice only. Client-supplied `model` and `max_tokens` SHALL be
ignored and replaced by the server decision.

#### Scenario: voice route uses the strong model

- **WHEN** a call arrives with purpose `typed_reply`
- **THEN** the provider receives the model configured for that purpose and
  the server-set output cap.

#### Scenario: learning-loop route uses the free model

- **WHEN** a call arrives with purpose `meaning`, `assessment`, `lookup`,
  `topic`, `help`, `scenario_review` or `delegation`
- **THEN** the provider receives the model configured for that purpose.

#### Scenario: unknown purpose is rejected before dispatch

- **WHEN** a call arrives with a purpose value outside the known enum
- **THEN** the proxy responds `400 unknown_purpose` and no provider call is
  made.

#### Scenario: legacy clients keep today's behavior

- **WHEN** a call arrives without a purpose header
- **THEN** the proxy uses the legacy default route (today's provider model)
  until the require-purpose flag is enabled.

### Requirement: Request policy allowlist

The proxy SHALL build the forwarded request from an explicit server-owned
allowlist of fields and SHALL reject requests carrying disallowed fields
with `400 request_policy_violation` naming the field, before any reservation
or provider work.

#### Scenario: disallowed field rejected

- **WHEN** a request body carries a field outside the allowlist
- **THEN** the response is `400 request_policy_violation`, the attempt never
  dispatches, and no funds are reserved.

#### Scenario: what is priced is what is forwarded

- **WHEN** a request is admitted
- **THEN** the model and output cap used for the reservation estimate are
  the same values forwarded to the provider.

### Requirement: Reservation before dispatch

The proxy SHALL atomically reserve the conservative cost estimate under the
wallet lock before any provider work, and SHALL settle actual usage and
release the unused reservation exactly once. A call whose estimate exceeds
the available balance SHALL be refused without dispatching. Concurrent or
multi-process calls SHALL NOT spend the same available funds twice.

#### Scenario: concurrent calls cannot overspend

- **WHEN** two calls are admitted simultaneously against one balance
- **THEN** each reserves its own estimate and combined reservations never
  exceed the balance at reservation time.

#### Scenario: uncertain outcome keeps the hold

- **WHEN** a dispatched attempt ends unknown (timeout, disconnect, oversize,
  unmeasurable usage)
- **THEN** its reservation remains held until an authorized operator
  releases or settles it through the audited CLI.

### Requirement: Daily subscription quota

The system SHALL grant active subscriptions (and the free daily allowance) a
token allowance as an idempotent daily ledger entry, so quota exhaustion
surfaces through the existing balance admission as `402
token_balance_exhausted`. Grants SHALL be expressible by operators through
the audited admin CLI for pilots.

#### Scenario: quota resets daily

- **WHEN** the UTC daily job runs for an active subscription
- **THEN** exactly one grant entry for that date lands in the ledger, even
  across restarts or retries.

#### Scenario: exhausted quota refuses the next call

- **WHEN** an account consumes its granted allowance within the day
- **THEN** the next proxied call is refused with `402` and no provider call
  is made until the next grant.

### Requirement: Policy endpoint is display-only

`GET /v1/conversation-policy` SHALL report the account's quota state and the
configured model label per purpose, authenticated by session. It SHALL NOT
grant, authorize, or alter any routing or billing decision.

#### Scenario: policy reflects routing configuration

- **WHEN** an operator changes the model for a purpose
- **THEN** the policy endpoint reports the new label without any client
  update.

### Requirement: First-party api provider in the app

The app SHALL offer an `api` conversation provider that reaches the backend
proxy with the session bearer, sends the purpose of every call, and takes
its model from the server — with no provider key material and no client-side
model pinning. All learning-loop calls (meanings, assessments, topics, help)
SHOULD flow through it when selected.

#### Scenario: every purpose rides the proxy

- **WHEN** the `api` provider is selected and a meanings or assessment call
  runs
- **THEN** the request carries its purpose header and receives the
  purpose-routed model response.

#### Scenario: no key material in the client

- **WHEN** the `api` provider is inspected or its traffic captured
- **THEN** no provider API key is present in the app or on the wire.

### Requirement: Dev providers retained, gated from release

Debug builds SHALL retain the existing local providers (personal OpenAI key,
DeepSeek, GLM, OpenCode, Palique tokens) for testing and latency
benchmarking. Release builds SHALL default to the `api` provider and SHALL
NOT offer BYOK provider selection.

#### Scenario: release build hides provider selection

- **WHEN** a release build opens the provider settings
- **THEN** only the first-party proxy-backed providers are offered.

#### Scenario: debug build benchmarks providers

- **WHEN** a debug build switches between direct providers and `api`
- **THEN** each provider remains fully functional with its own key source.

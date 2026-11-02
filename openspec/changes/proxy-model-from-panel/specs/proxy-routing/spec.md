# Delta Spec: proxy-routing

## ADDED Requirements

### Requirement: Server-owned model

The proxy SHALL select the forwarded provider, model and credentials
server-side from the operator-managed LLM configuration in the database
(`llm_provider_configs`, resolved with the documented env fallback), applied
per call. Client-supplied `model` and `max_tokens` SHALL be ignored and
replaced by the server decision. `max_tokens` SHALL come from
`PROXY_MAX_TOKENS`.

#### Scenario: panel config drives the relay

- **WHEN** an active `llm_provider_configs` row exists (provider, base URL,
  model, decryptable key)
- **THEN** the provider call targets that base URL's chat-completions
  endpoint with that key and that model, regardless of any client-supplied
  `model`.

#### Scenario: provider and model are coherent

- **WHEN** the operator changes the provider or model in the admin panel
- **THEN** the next proxy call uses the new configuration without a server
  restart, and no env var is involved in the decision.

#### Scenario: no resolvable configuration

- **WHEN** no decryptable DB config and no valid env fallback exist
- **THEN** the proxy responds `503 proxy_unavailable` before any wallet
  reservation or provider call.

#### Scenario: opencode provider keeps its session contract

- **WHEN** the resolved provider is `opencode`
- **THEN** the relayed request carries `x-opencode-session` and the
  provider user agent, by the same rule the exam-generation transport
  applies.

### Requirement: Output cap

The proxy SHALL bound the forwarded `max_tokens` with a single server-side
cap (`PROXY_MAX_TOKENS`).

#### Scenario: cap applies uniformly

- **WHEN** any proxy call is relayed
- **THEN** the forwarded `max_tokens` equals the configured cap, independent
  of the caller or request content.

### Requirement: No purpose contract

The proxy SHALL NOT require, validate or record a call-purpose header.

#### Scenario: calls without purpose headers are first-class

- **WHEN** a proxy call arrives with no purpose header
- **THEN** it is relayed exactly like any other call, with no
  `purpose_required` or `unknown_purpose` error possible.

#### Scenario: provider duration metric keeps a closed label set

- **WHEN** relay outcomes are recorded
- **THEN** `palique.proxy.provider.duration` carries only outcome labels
  (no purpose label), matching the observability contract.

## MODIFIED Requirements

### Requirement: First-party api provider in the app

The app SHALL offer an `api` conversation provider that reaches the backend
proxy with the session bearer and no request body model — the server decides
provider, model and credentials. The call SHALL NOT depend on any prior
policy fetch, and no provider key material SHALL exist client-side. All
learning-loop calls (meanings, assessments, topics, help) SHOULD flow
through it when selected.

#### Scenario: the engine needs no policy round-trip

- **WHEN** the `api` provider is selected and a call runs without any prior
  policy response
- **THEN** the request is sent immediately and the server's model decision
  is applied.

#### Scenario: no key material in the client

- **WHEN** the `api` provider is inspected or its traffic captured
- **THEN** no provider API key is present in the app or on the wire, and the
  request body carries no model field.

## REMOVED Requirements

### Requirement: Server-owned model per purpose

**Reason**: a single provider/model from the panel replaces per-purpose
routing; purpose advice added three configuration layers for one decision.
Replaced by "Server-owned model" and "Output cap".

**Migration**: purpose fields carried no DB state; the purpose header,
routing table, CLI subcommands and conversation-policy endpoint are removed
without data migration (migration 057 is amended before it ships).

### Requirement: Policy endpoint is display-only

**Reason**: with a single panel-managed model there is no per-purpose label
to report, and no client consumer of the endpoint's quota fields (verified
unused by any UI). The endpoint is deleted.

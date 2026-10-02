# Token billing (proxy v1)

The token proxy lets an unlocked account call a provider-hosted LLM with the
**server's** provider key, paid from a pre-purchased nanoUSD wallet balance
instead of a customer BYOK key. It backs the apps' "Palique (tokens)"
conversation engine. Money units are integer nanoUSD: 1 USD = 1,000,000,000
nanoUSD; wallet, ledger and reconciliation machinery are shared with
[conversation minutes](conversation-minutes.md).

## v1 contract: non-streaming only

`POST /v1/proxy/responses` accepts an OpenAI-compatible chat-completion body
and returns the provider's JSON completion with one added field:

```json
{ "...provider reply...",
  "metering": { "rateVersion": "2026-09-minimax-m", "nanoUSDSettled": "1500000" } }
```

v1 is **non-streaming by contract**: the design's sanctioned alternative
(`SSE pass-through ... non-streaming fallback switch`,
`openspec/changes/token-billing/design.md`) is deferred because SSE usage
arrives in the final chunk, which the JSON metering path cannot parse. A
request with `"stream": true` is rejected before admission or any provider
call:

```
400 {"error":{"code":"streaming_unsupported_v1"}}
```

**Operator requirement: clients send `stream: false`.** The app engines'
`OpenAICompatibleEngine` (the `hosted-proxy` engine) already does; any other
client must too. A later change may add SSE pass-through.

## Auth and unlock

- Bearer session token (same `authorization` header as every account route).
- Without a verified BYOK unlock the route answers
  `403 {"error":{"code":"unlock_required"}}`. The unlock is set once by a
  **live-environment** purchase of the `byok_unlock` product (sandbox receipts
  record state but never unlock); a campaign grant can also set it.
- Without `PROXY_PROVIDER_URL`/`PROXY_PROVIDER_KEY` configured the route
  answers `503 proxy_unavailable` — the proxy is disabled, not degraded.

## Admission

- The route is a normal client route: it fails closed with
  `trusted_proxy_required` (503) without trusted-proxy headers, and shares the
  in-memory limiter of 120 requests per minute per verified client network.
- Body limit: 32,768 bytes.
- A per-account concurrent-call cap (design default 2) remains documented
  follow-up; v1 bounds one account through the shared limiter plus its wallet.

## Metering

Order of checks, all before the provider is called:

1. Available balance = `balance_nano - reserved_nano`; `<= 0` →
   `402 token_balance_exhausted`.
2. Pre-call estimate = input estimate (body size / 4 chars + 256 tokens) at the
   input rate, plus `PROXY_MAX_TOKENS` (default 4,000) output tokens at the
   output rate; estimate above balance → `402 token_balance_exhausted`.

After the provider replies, usage is settled at the **active rate version**
(`rate_versions`, latest `valid_from`): `prompt_tokens × input rate +
completion_tokens × output rate`, debited through the ledger (`settle` entry,
`rate_version` column) so every balance stays auditable. Grace is bounded: a
single call whose real cost exceeds the balance still returns the reply and
settles at the balance (never negative) — the reply is never wasted, but a
zero balance refuses the next call.

Rate pinning: a pack purchase pins the then-active rate version on its ledger
entries, so a later rate change never reinterprets an already-paid balance.
Seeded rate `2026-09-minimax-m`: 300,000 nano per million input tokens
($0.30/M), 1,200,000 per million output ($1.20/M).

## Error matrix

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `streaming_unsupported_v1` | `"stream": true` — v1 is non-streaming |
| 401 | auth codes | Missing/invalid session token |
| 402 | `token_balance_exhausted` | Zero balance, or estimate above balance |
| 403 | `unlock_required` | Account has no verified BYOK unlock |
| 502 | `provider_error` | Provider returned 4xx/5xx (key and body stay server-side) |
| 503 | `proxy_unavailable` | `PROXY_PROVIDER_URL`/`KEY` not configured |

Error bodies carry a safe `error.code` only.

## Configuration

| Variable | Meaning |
| --- | --- |
| `PROXY_PROVIDER_URL` | Absolute URL of an OpenAI-compatible completions endpoint (MiniMax or an aggregator) |
| `PROXY_PROVIDER_KEY` | Server-side provider key; never sent to clients |
| `PROXY_MAX_TOKENS` | Output-token estimate cap for admission (default 4,000) |

Both URL and key must be set or the proxy stays off. Compose forwards both
from the environment; keep the key out of the repository.

## Packs and pricing

Approved launch prices (Joaquín, 2026-09) are **server constants** in
`services/api/src/pack-products.ts`; SKU strings are operator catalog config
validated against them, so a tampered catalog cannot sell a different product:

- `byok_unlock` — **$6.99 one-time**, unlocks the proxy (live environment only).
- `token_pack` — **$4.99**, credits **2,160,000,000 nanoUSD ($2.16)** of
  spendable wallet balance; refundable proportionally like AI value.

Both flow through the same server-verified purchase reconciliation as minutes
(Stripe/Play evidence, idempotency, reversal); pack credit requires
reconciled cash provenance before it can be ordered.

## Load signal

`services/api/load/loadtest.mjs` has a `proxy-completions` phase: paced
authorized non-streaming calls recording latency percentiles plus separate
402/403 counters. It must target a **stub provider** (start the API with
`PROXY_PROVIDER_URL` pointed at a local stub) — never a real provider.

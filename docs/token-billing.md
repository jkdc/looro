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
arrives in the final chunk, which the JSON metering path cannot parse.
`"stream": true` is rejected before wallet admission, the provider call, or any debit:

```
400 {"error":{"code":"streaming_unsupported_v1"}}
```

**Operator requirement: clients send `stream: false`.** The app engines'
`OpenAICompatibleEngine` (the `hosted-proxy` engine) already does; any other
client must too. A later change may add SSE pass-through.

## Metering

After the stream gate and the cap above, admission **reserves** the call's
conservative estimate under the wallet lock (see [Reservation](#reservation-and-funded-admission)):
available balance = `balance_nano - reserved_nano`; below the estimate →
`402 token_balance_exhausted` before the provider is called.

After the provider replies, its reported usage is **validated strictly** — both
counts present, integers within the safe range, non-negative — and then settled
at the **active rate version** (`rate_versions`, latest `valid_from`): usage ×
rate, debited through the ledger (`settle` entry, `rate_version` column) so
every balance stays auditable. A success reply whose usage fails validation is
refused as `502 provider_error` **before settlement**: nothing debits, the
reply is not returned, and a misreporting provider can never become an
unbilled usage channel. Every successful relay — including a zero-cost report —
also writes a durable `proxy_usage` row (token counts, the admission estimate,
computed cost, settled amount) tied to its settle ledger entry when there was
one; the admin timeseries set `tokens` and the `proxy_usage` browse table
aggregate it per day and per account.

Rate versions: settlement always converts usage at the rate version **active at
call time**, so a new `rate_versions` row changes what existing balances buy
going forward. The rate version pinned on a pack's quote and ledger entries is
purchase provenance for audit; it does not freeze the tariff for the balance.

## Auth and unlock

- Bearer session token (same `authorization` header as every account route).
- Without a verified BYOK unlock: `403 unlock_required`. The unlock is set
  once by a **live-environment** `byok_unlock` purchase (sandbox receipts
  record state but never unlock); a campaign grant can also set it (see
  [Operator grants](#operator-grants-no-store-purchase)). Clients read the
  current state from `GET /v1/wallet` (`unlocked`, `unlockSource`).
- Without a resolvable provider configuration (admin panel
  `llm_provider_configs`, env fallback `LEVELTEST_LLM_PROVIDER` /
  `LEVELTEST_LLM_API_KEY` with optional `LEVELTEST_LLM_URL`) the route
  answers `503 proxy_unavailable` — the proxy is disabled, not degraded.

## Admission

- The route is a normal client route: it fails closed with
  `trusted_proxy_required` (503) without trusted-proxy headers, and shares the
  in-memory limiter of 120 requests per minute per verified client network.
- Body limit: 32,768 bytes.
- Per-account concurrency cap: at most `PROXY_MAX_CONCURRENT` (default 2)
  in-flight relays per account; excess → `429 account_busy`. The slot frees on
  settle or error, so one account cannot monopolise the shared server key.

## Reservation and funded admission

Admission no longer trusts an unlocked balance snapshot: the attempt row and a
**wallet hold** are written in one transaction under the wallet lock
(`reservations` row + immutable ledger `reserve` entry, `wallets.reserved_nano`).
A call whose estimate exceeds the available balance is refused before any
provider work, so concurrent calls — or two API processes — cannot spend the
same funds twice.

- **Settled success**: one combined ledger entry debits the actual usage and
  releases the hold. An overrun above the estimate is still bounded by what
  the wallet holds today (never negative).
- **Rejected upstream** (provider 400/401/403/429 — proven not-dispatched
  work): the hold is released.
- **Unknown outcome** (timeout, client disconnect, oversize, unmeasurable
  usage): the hold is **kept** and reconciled by the operator with
  `tokens-admin release-hold` (audited); nothing is ever guessed or
  auto-released.

Estimates use the resolved model's output cap (`PROXY_MAX_TOKENS`). The
estimate covers the forwarded request, so what is priced is what is forwarded.

Rate pinning: a pack purchase pins the then-active rate version on its ledger
entries, so a later rate change never reinterprets an already-paid balance.
Seeded rate `2026-09-minimax-m`: 300,000 nano per million input tokens
($0.30/M), 1,200,000 per million output ($1.20/M).

## Request policy

The proxy builds the forwarded request server-side: the model, the output cap
and `stream: false` are server decisions — the provider/model/key come from
the admin panel's LLM configuration (`llm_provider_configs`), resolved per
call. The policy only decides which caller fields may ride along:

- Allowed caller fields: `messages` (required), `response_format`
  (`{type:"json_object"}` only), `temperature` (0–2).
- `model`, `max_tokens` and `stream` are accepted but always overridden by
  the server decision; any other field → `400 request_policy_violation`.

## Subscription quota (daily grants)

`token_subscriptions` holds each account's plan (`free`/`pilot`/`standard`)
and daily token allowance. A daily UTC job converts the allowance at the
**active rate version** into one idempotent ledger `purchase` entry
(`subscription-grant:<date>:<accountID>`), so quota exhaustion surfaces
through the same balance admission as any other refusal (`402
token_balance_exhausted`) and the immutable ledger stays the single money
path. The free daily allowance (`PROXY_FREE_DAILY_*_TOKENS`, default 0 =
off) covers every non-guest account without a plan; grants are also
assignable per pilot with `tokens-admin grant-quota`.

## Error matrix

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `streaming_unsupported_v1` | `"stream": true` — v1 is non-streaming |
| 400 | `request_policy_violation` | Body field outside the allowlist |
| 401 | auth codes | Missing/invalid session token |
| 402 | `token_balance_exhausted` | Balance below the reservation estimate (incl. exhausted daily quota) |
| 403 | `unlock_required` | Account has no verified BYOK unlock |
| 502 | `provider_error` | Provider returned 4xx/5xx, or a success reply whose usage cannot be metered (missing/malformed/negative counts — refused before settlement, nothing debits) |
| 429 | `account_busy` | Per-account in-flight cap (`PROXY_MAX_CONCURRENT`) exceeded |
| 503 | `proxy_unavailable` | No resolvable provider configuration (panel LLM config + `LEVELTEST_LLM_*` fallback), or `rate_versions` empty |

Error bodies carry a safe `error.code` only.

## Configuration

| Variable | Meaning |
| --- | --- |
| `PROXY_MAX_TOKENS` | Output-token estimate cap for admission (default 4,000) |
| `PROXY_MAX_CONCURRENT` | Per-account in-flight relay cap (default 2) |
| `PROXY_FREE_DAILY_INPUT_TOKENS` | Free daily input-token allowance for every member account (default 0 = off) |
| `PROXY_FREE_DAILY_OUTPUT_TOKENS` | Free daily output-token allowance (default 0 = off) |

Provider, model and API key come from the admin panel's LLM configuration
(`llm_provider_configs`, edited in the panel, keys AES-256-GCM-encrypted);
without an active panel row the proxy falls back to `LEVELTEST_LLM_PROVIDER`
/ `LEVELTEST_LLM_API_KEY` (optional `LEVELTEST_LLM_URL`). Without either the
proxy stays off. The provider key is server-side only; never send it to
clients or commit it.

**Client model contract:** the proxy takes provider, model and key from the
panel LLM config per call; clients send no purpose header and no model that
is honored — any client-supplied `model`/`max_tokens` are overridden
server-side, so a panel re-route never needs a client release. A configured
model the provider does not actually serve surfaces to clients as `502
provider_error`. Billing is unaffected either way: provider failures are
rejected before settlement, so nothing ever debits.

## Packs and pricing

Approved launch prices (Joaquín, 2026-09) are **server constants** in
`services/api/src/pack-products.ts`; SKU strings are operator catalog config
validated against them, so a tampered catalog cannot sell a different product:

- `byok_unlock` — **$6.99 one-time**, unlocks the proxy (live environment only).
- `token_pack` — **$4.99**, credits **2,160,000,000 nanoUSD ($2.16)** of
  spendable wallet balance; refundable proportionally like AI value.

Both flow through the same server-verified purchase reconciliation as minutes
(Stripe/Play evidence, idempotency, reversal); pack credit requires reconciled
cash provenance before ordering.

## Operator grants (no store purchase)

Access and balance can be assigned by the local operator with the `tokens-admin`
CLI — same standing as `minutes-admin`, no HTTP route creates unlock or journal
state. Every mutation reads JSON on stdin, carries an `actor` and a `reason`,
and writes the immutable `token_admin_audit` table (browsable in the panel).

```sh
npm run tokens-admin -- show <accountID>
echo '{"accountID":"<uuid>","actor":"joaquin","reason":"campaign pilot"}' \
  | npm run tokens-admin -- grant-unlock
echo '{"accountID":"<uuid>","actor":"joaquin","reason":"campaign pilot","grantID":"pilot-1","creditNanoUSD":"2160000000"}' \
  | npm run tokens-admin -- grant-balance
```

- **`grant-unlock`** sets the unlock with `unlock_source='campaign'`, the slot
  the schema reserved for it. Set-once like the purchase path: an active unlock
  (paid or campaign) is never overwritten; a row cleared by a refund can be
  re-granted. `unlock_order_id` stays NULL — no store order owns it.
- **`revoke-unlock`** clears only a campaign unlock. A paid unlock clears
  exclusively through its own refund/void machinery.
- **`grant-balance`** credits the wallet through an immutable ledger entry
  (`purchase` kind, sandbox delta zero, active rate version as provenance),
  referenced `campaign-grant:<grantID>`: a replay of the same `grantID` is a
  no-op and a conflicting reuse is a 409. The credit is spendable at the token
  proxy; it is not store cash, so it does not by itself satisfy the
  cash-provenance gates of the paid voice and helper paths.
- Grants target signed-in member accounts only — guest installation identities
  are refused.
- **`show`** prints the wallet, unlock state and 30-day proxy usage for one
  account.
- **`grant-quota`** assigns a pilot subscription window
  (`{"accountID","actor","reason","plan","days","dailyInputTokens","dailyOutputTokens"}`);
  the daily job converts the allowance to ledger credit.
- **`release-hold`** reconciliation: releases the reservation of an attempt
  whose outcome stayed unknown
  (`{"attemptID","actor","reason"}`; audited as `release_hold`). Review the
  provider evidence first — the hold is the only record that provider work
  may have been spent.

## Load signal

`services/api/load/loadtest.mjs` has a `proxy-completions` phase: paced
authorized non-streaming calls recording latency percentiles plus separate
402/403/429 counters. It must target a **stub provider** (point the API's
LLM configuration at a local stub — panel row or `LEVELTEST_LLM_URL`
fallback) — never a real provider.

# Design: token-billing

## Data model (migrations 025+)

```sql
CREATE TABLE entitlements (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  unlocked_at timestamptz,
  unlock_source text CHECK (unlock_source IN ('play','stripe','campaign')),
  created_at timestamptz NOT NULL DEFAULT now()
);
```

Token packs reuse `wallets.balance_nano` (nanoUSD already the ledger unit) —
no second balance. The `ledger` entry for a pack purchase carries
`rate_version` so historical balances stay interpretable:

```
rateVersion = { id, nanoUSDPerMillionInput, nanoUSDPerMillionOutput, validFrom }
```

`rate_versions` table + `meter` writes the applied `rate_version` id per
proxied call into the existing metering rows. NanoUSD math already exists
(`conversation-balance.ts`, hosted metering) — proxy metering is a consumer
of it, not a new ledger type.

## Proxy endpoint

`POST /v1/proxy/responses` (name follows the hosted-responses-transport
pattern): OpenAI-compatible body in/out so both cores can reuse their existing
`OpenAICompatibleEngine` with only a base-URL + key swap.

- Auth: app bearer session; require `unlocked_at` (403 `unlock_required`).
- Admission: shared trusted-proxy limiter + per-account concurrent-call cap
  (config, default 2) so one account cannot monopolise the server key.
- Metering: pre-call reserve (pattern of `minute_purchases` reservation),
  post-call settle with provider-reported usage × `rateVersion`; insufficient
  balance → 402 `token_balance_exhausted` after the reply is still returned
  when the shortfall is small (grace, config-bounded).
- Provider: `PROXY_PROVIDER_URL`/`PROXY_PROVIDER_KEY` env (MiniMax or an
  OpenRouter-style aggregator), mapped to the OpenAI-compatible schema.
- Streamed replies: SSE pass-through with usage captured from the final chunk
  (provider-dependent; non-streaming fallback switch).

## Apps

- `ConversationEngineFactory` gains a `hosted-proxy` engine: same
  `OpenAICompatibleEngine`, base URL `/v1/proxy/responses`, bearer session as
  key. Whisper/local TTS path unchanged (voice stays on-device; only the LLM
  call is proxied) — consistent with "on-device path only" advice.
- Settings: provider picker gains "Palique (tokens)" row with balance chip;
  locked state opens the paywall; purchase sheet reuses minute-sheet UI with
  pack SKUs.
- New strings in EN/ES parity; no new capabilities in the archive (balance is
  server-side by design — no offline balance caching beyond a display hint).

## Pricing implementation

Pack SKUs ship as constants + remote `rateVersion`; the pack's nanoUSD credit
is fixed at purchase (rateVersion pinned), so a later rate change only affects
future purchases. Admin: `browse` exposes entitlements/metering rows without
new dashboards.

## Tests

- Backend: entitlement delivery via webhook test paths; proxy auth/403/402
  matrix; metering math with pinned rateVersion; concurrency cap; admission.
- Apps: engine factory selection, paywall gating, balance display (JVM;
  iOS mirrored under the macOS gate).
- Load: `services/api/load/loadtest.mjs` extended with a proxy phase once F2
  lands.

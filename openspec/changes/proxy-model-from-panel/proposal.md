# Proposal: proxy-model-from-panel

## Intent

Simplify the token proxy to a single source of configuration: the provider,
model and API key that the operator already manages from the admin panel's
"Configuración de IA" screen (`llm_provider_configs`, the level-test
generation config). One provider, one model, for every proxy call.

## Problem

The current design (change `proxy-api-provider`) carries three configuration
layers for one decision:

1. `PROXY_PROVIDER_URL` / `PROXY_PROVIDER_KEY` env vars pick the provider.
2. The `proxy_purpose_routes` table picks a model per purpose (8 rows, CLI
   `tokens-admin set-route` only — the panel cannot even display them).
3. `GET /v1/conversation-policy` ships the route table to the app, which
   echoes a (display-name, not model-ID) string back, which the server then
   overrides anyway.

For the lab goal — point the proxy at OpenCode free models chosen from the
panel — this adds friction with no benefit: the env provider and the panel
model can disagree, OpenCode needs an extra session header the raw proxy
transport does not send, and route edits require CLI JSON payloads.

## Approach

- **F1 — Provider from the panel (backend).** `TokenProxy` resolves its
  provider per call via `resolveLlmConfig(db, env)` (active `llm_provider_configs`
  row, env fallback unchanged). No resolvable config → `503 proxy_unavailable`.
  `PROXY_PROVIDER_URL` / `PROXY_PROVIDER_KEY` are removed. Changing the
  provider/model in the panel takes effect without restarting.
- **F2 — One model, no purposes (backend).** Purpose routing is removed
  entirely: the `proxy_purpose_routes` table (migration 057 amended in place —
  never applied to any persistent environment), the `x-palique-purpose`
  header contract, `unknown_purpose` / `purpose_required` errors,
  `PROXY_REQUIRE_PURPOSE`, the `purpose` label on `palique.proxy.provider.duration`,
  and the `set-route` / `routes` CLI subcommands. `max_tokens` comes from
  `PROXY_MAX_TOKENS`.
- **F3 — Shared provider headers (backend).** The OpenCode session-header
  rule is extracted from `chatCompletion` into a helper in `llm-config.ts`
  used by both transports. The proxy keeps its own timeout, byte caps and
  strict `usage` metering — billing is untouched.
- **F4 — Drop the conversation-policy flow (both ends).** `GET
  /v1/conversation-policy`, `ConversationPolicyStore`, the bounded-retry
  fetch in `AppViewModel` and the client parsing go away. The `api` engine
  sends a body without `model`; the server fills it. No UI consumed the
  policy's quota fields (verified dead).

## Non-goals

- Different models for exams vs conversation (they share the panel config).
- Any change to `rate_versions`, metering, entitlements or balances.
- Touching the `hosted-proxy` engine (its pinned model is overridden anyway).
- New admin-panel UI (the existing "Configuración de IA" screen already
  manages everything the proxy now needs).

## Risks

- The client no longer influences cost — it never really did; the override
  is retained, so the audit stance "the client never picks what it is
  charged for" is preserved and simplified.
- `proxy-api-provider`'s spec deltas must be updated (this change modifies
  and removes requirements that change introduced; that change is not
  archived because its reservation/policy/quota features remain).

# Outbound trust boundary (server-side fetch targets)

The API performs server-side `fetch` calls to a small, fixed set of provider
endpoints. Every one of them carries a server-side credential in its
`Authorization` header, so the configuration is fail-closed:

| Target | Source | Rule |
|---|---|---|
| Token-billing provider | panel `llm_provider_configs` base URL (`assertOutboundUrl` applied inside `resolveLlmConfig`); env fallback `LEVELTEST_LLM_URL` | `assertOutboundUrl`: https required; plain http only for loopback transports (`127.0.0.1`, `::1`, `localhost`) |
| Level-test LLM | `LEVELTEST_LLM_URL` (env) or `llm_provider_configs.base_url` (operator row) | same rule; an operator row that violates it is skipped, an env value that violates it fails startup |
| Voice sessions | `LiveProvider` origin | production origin is fixed to https (`api.openai.com`); test transports must be loopback http |
| Identity providers | `googleapis.com` / `appleid.apple.com` | hardcoded JWKS endpoints |

## Known, accepted boundary

When a student holds a hosted voice or helper session, the conversation content
they speak (including any URL they mention) is relayed to the voice provider,
which may itself perform web requests (search/tools) as part of answering.
That is inherent to the product design, not a server-side SSRF: the server
never fetches a client-supplied URL itself. Providers are fixed, trusted
endpoints configured by the operator; if the deployment restricts egress, the
provider domains above are the only required destinations.

## Rules for new outbound calls

1. Route the URL through `assertOutboundUrl` (or a hardcoded https origin).
2. Never fetch a URL that arrived in a request body.
3. Document the target in the table above when adding one.

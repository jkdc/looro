# Proxy model from panel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use zuperpowers:subagent-driven-development (recommended) or zuperpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The token proxy takes its provider, model and API key from the admin panel's LLM configuration (`llm_provider_configs`) per call; purpose routing, the purpose header and the conversation-policy endpoint are removed.

**Architecture:** `TokenProxy` resolves `resolveLlmConfig(db, env)` on every relay (DB-active panel row → env fallback → `503 proxy_unavailable`), builds the canonical body with the panel model and `PROXY_MAX_TOKENS`, and reuses a shared `providerExtraHeaders` helper so OpenCode's session header works. Android's `api` engine stops fetching a policy and sends no `model`.

**Tech Stack:** Node 22 + TypeScript + Fastify + node:test (server); Kotlin + JUnit4 + MockWebServer (Android); Docker Compose for integration tests.

**Spec:** `openspec/changes/proxy-model-from-panel/` (proposal, design D1–D7, tasks, spec delta).

## Global Constraints

- Commits: conventional, small, English, present tense (`feat(proxy): ...`). Never add AI attribution or `Co-Authored-By`.
- Work on the current branch (`develop`). No worktrees, no push.
- Code and comments in English; product docs (`docs/api-capacity.md`, `docs/local-android-lab.md`, informes) in Spanish.
- Test-first: every behavior change lands with a test that fails before it.
- Never edit applied migrations; migration 057 is amended in place only because it was never applied to any persistent environment.
- Telemetry: closed label sets only — dropping the `purpose` label must not introduce new dynamic labels. No PII.
- No secrets in git: `.env`, `api-keys.properties` stay gitignored.
- KDoc: no `/*` nested inside `/**`.
- Server test runner: `npx tsx --test tests/<file>.test.ts` (pure tests run without DB; integration tests need `TEST_DATABASE_URL` and skip otherwise — full suite via `make test-api`).

---

### Task 1: Shared provider-headers helper (`llm-config.ts`)

**Files:**
- Modify: `services/api/src/llm-config.ts` (add export near `chatCompletion`, refactor its headers)
- Test: `services/api/tests/provider-headers.test.ts` (new)

**Interfaces:**
- Produces: `providerExtraHeaders(provider: LlmProvider, sessionID?: string): Record<string, string>` — Task 3 (proxy relay) and the refactored `chatCompletion` consume it.

- [ ] **Step 1: Write the failing test**

Create `services/api/tests/provider-headers.test.ts`:

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { providerExtraHeaders } from '../src/llm-config.js';

test('opencode transport sends the session header the gateway requires', () => {
  const headers = providerExtraHeaders('opencode');
  assert.match(headers['x-opencode-session'] ?? '', /^[0-9a-f-]{36}$/);
  assert.equal(headers['user-agent'], 'palique-api/1.0');
});

test('an explicit session id is honored', () => {
  assert.equal(providerExtraHeaders('opencode', 'fixed-session')['x-opencode-session'], 'fixed-session');
});

test('non-opencode providers carry no extra headers', () => {
  assert.deepEqual(providerExtraHeaders('glm'), {});
  assert.deepEqual(providerExtraHeaders('openai'), {});
});
```

- [ ] **Step 2: Run it and verify it fails**

Run: `cd services/api && npx tsx --test tests/provider-headers.test.ts`
Expected: FAIL — no export named `providerExtraHeaders`.

- [ ] **Step 3: Implement the helper and refactor `chatCompletion`**

In `src/llm-config.ts`, add above `chatCompletion`:

```ts
/** Provider-specific outbound headers. OpenCode Go routes per conversation
    and rejects session-less requests; other providers take none. */
export const providerExtraHeaders = (provider: LlmProvider, sessionID?: string): Record<string, string> =>
  provider === 'opencode'
    ? { 'x-opencode-session': sessionID ?? randomUUID(), 'user-agent': 'palique-api/1.0' }
    : {};
```

In `chatCompletion`, replace the inline header conditional:

```ts
        headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}`,
          ...providerExtraHeaders(config.provider, config.sessionID) },
```

(deleting the old `...(config.provider === 'opencode' ? { ... } : {})` spread and its comment — the comment now lives on the helper).

- [ ] **Step 4: Run the test and the touched suites**

Run: `npx tsx --test tests/provider-headers.test.ts` → PASS
Run: `npx tsx --test tests/llm-config.test.ts tests/level-tests-llm.test.ts 2>/dev/null || npx tsc --noEmit` → type-check green (the existing LLM suites must stay green if they exist).

- [ ] **Step 5: Commit**

```bash
git add services/api/src/llm-config.ts services/api/tests/provider-headers.test.ts
git commit -m "feat(llm-config): extract providerExtraHeaders for opencode session routing"
```

---

### Task 2: Remove `routes` / `set-route` from `tokens-admin`

**Files:**
- Modify: `services/api/src/tokens-admin.ts` (delete `setPurposeRoute`, `listPurposeRoutes`, `RouteUpdateInput`, CLI branches)
- Test: none new (CLI surface removal; covered by type-check + usage run)

**Interfaces:**
- Consumes: nothing removed elsewhere yet — this task only deletes code, so later tasks can remove `isProxyPurpose`/`purposeRouteTable` from `proxy-policy.ts` without breaking `tokens-admin`.

- [ ] **Step 1: Delete the route functions**

Remove from `src/tokens-admin.ts`: the `RouteUpdateInput` interface, `setPurposeRoute` (and its doc comment), and `listPurposeRoutes`. Keep `auditText`, `audit`, `grantQuota`, `reconcileHold` — they are used by other subcommands.

- [ ] **Step 2: Update the CLI dispatch**

In the `if (process.argv[1] && ...)` block: remove `'routes'` and `'set-route'` from `known`, remove the `command === 'routes'` / `command === 'set-route'` result branches, and shorten `usage` to:

```ts
  const usage = 'Use tokens-admin show <accountID> | grant-unlock | revoke-unlock | grant-balance | grant-quota | release-hold. Mutations read JSON from stdin: {"accountID","actor","reason"} and, for grant-balance, {"grantID","creditNanoUSD"}; grant-quota: {"accountID","actor","reason","plan","days","dailyInputTokens","dailyOutputTokens"}; release-hold: {"attemptID","actor","reason"}.';
```

- [ ] **Step 3: Verify**

Run: `cd services/api && npx tsc --noEmit` → green
Run: `npm run tokens-admin -- routes 2>&1 | head -1` → prints the new usage (unknown command path).

- [ ] **Step 4: Commit**

```bash
git add services/api/src/tokens-admin.ts
git commit -m "refactor(tokens-admin): drop purpose-route subcommands"
```

---

### Task 3: Canonical body without routes + per-call provider resolution (`proxy-policy.ts`, `token-proxy.ts`)

**Files:**
- Modify: `services/api/src/proxy-policy.ts` (new `canonicalProxyRequest` signature; delete purpose exports)
- Modify: `services/api/src/token-proxy.ts` (config, resolver injection, relay, meteredRelay)
- Modify: `services/api/src/main.ts:39,175` (construction + resolver wiring)
- Test: `services/api/tests/token-proxy.test.ts` (rewrite affected parts)

**Interfaces:**
- Consumes: `providerExtraHeaders` (Task 1), `resolveLlmConfig`/`ResolvedLlmConfig`/`LlmProvider` from `src/llm-config.ts` (existing).
- Produces: `tokenProxyConfig(env): TokenProxyConfig | undefined` where `TokenProxyConfig = { maxTokens: number; maxConcurrentPerAccount: number; maxInFlight: number; timeoutMs: number; maxResponseBytes: number }`; `new TokenProxy(db, config, resolveProvider: () => Promise<ResolvedLlmConfig | undefined>)`; `relay(accountID, body, options?)`; `canonicalProxyRequest(input, model: string, outputCap: number)`.

- [ ] **Step 1: Rewrite the pure tests for the new signature**

In `tests/token-proxy.test.ts`, replace the `canonicalProxyRequest` block (currently ~lines 276-288, called with `(input, route)`) with:

```ts
{
  const { body, outputCap } = canonicalProxyRequest(
    { messages: [{ role: 'user' }], model: 'gpt-9', max_tokens: 99, stream: true }, 'panel-model', 128);
  assert.deepEqual(body, { messages: [{ role: 'user' }], model: 'panel-model', max_tokens: 128, stream: false });
  assert.equal(outputCap, 128);
  assert.throws(() => canonicalProxyRequest({ messages: [{ role: 'user' }], tools: [] }, 'm', 128), /request_policy_violation/);
  assert.throws(() => canonicalProxyRequest({ messages: [] }, 'm', 128), /request_policy_violation/);
  assert.throws(() => canonicalProxyRequest({ messages: [{ role: 'user' }], temperature: 9 }, 'm', 128), /request_policy_violation/);
  assert.throws(() => canonicalProxyRequest({ messages: [{ role: 'user' }], response_format: { type: 'json_schema' } }, 'm', 128), /request_policy_violation/);
}
```

- [ ] **Step 2: Rewrite the integration tests for resolver injection**

Changes in `tests/token-proxy.test.ts`:

a) `CONFIG` becomes key-free and the proxy takes a resolver:

```ts
const CONFIG = { maxTokens: 128, maxConcurrentPerAccount: 2 };
```

b) The `tokenProxyConfig requires both URL and key` test is replaced by a knob test:

```ts
integration('tokenProxyConfig keeps only the relay knobs', async () => {
  const config = tokenProxyConfig({});
  assert.equal(config?.maxTokens, 4000);
  assert.equal(config?.maxConcurrentPerAccount, 2);
  assert.throws(() => tokenProxyConfig({ PROXY_MAX_CONCURRENT: '0' }), /positive integer/);
});
```

c) Every `new TokenProxy(db, CONFIG)` call site gains a resolver argument. The existing provider stub server (already built with `createServer`) is addressed by base URL now:

```ts
const providerConfig = { provider: 'openai' as const, baseUrl: `http://127.0.0.1:${serverPort}`, model: 'test-model', apiKey: 'server-key' };
const proxy = new TokenProxy(db, CONFIG, async () => providerConfig);
```

Update the stub-server assertions: the request now arrives at path `/chat/completions` with body `model` equal to `test-model` and `max_tokens` equal to `CONFIG.maxTokens`.

d) Add two integration tests:

```ts
integration('relay without resolvable provider returns proxy_unavailable and reserves nothing', async () => {
  const proxy = new TokenProxy(db, CONFIG, async () => undefined);
  const { accountID } = await seededAccount(db); // reuse the file's existing account-seeding helper
  await assert.rejects(() => proxy.relay(accountID, { messages: [{ role: 'user', content: 'hi' }] }),
    (error: ServiceError) => error.code === 'proxy_unavailable');
  // No attempt row, no wallet reservation:
  const attempts = await db.query('SELECT count(*)::int AS n FROM proxy_attempts');
  assert.equal(attempts.rows[0].n, 0);
});

integration('opencode provider rides the session header helper', async () => {
  // Same stub-server pattern, resolver returns provider:'opencode'.
  // Assert the recorded request carries an x-opencode-session header.
});
```

(If the file's seeding helper has another name, reuse it — the engineer reads the file's existing `integration(...)` cases and mirrors them.)

e) Delete the 7 purpose-related assertions/tests (search `x-palique-purpose`, `unknown_purpose`, `purposeRoute`, `route`). Nothing replaces them: no-purpose is covered by (c)/(d) passing no header.

- [ ] **Step 3: Run and verify failure**

Run: `npx tsx --test tests/token-proxy.test.ts`
Expected: FAIL — `canonicalProxyRequest` still takes a route; `TokenProxy` has no third parameter.

- [ ] **Step 4: Implement `proxy-policy.ts`**

Delete from `src/proxy-policy.ts`: `PROXY_PURPOSES`, `ProxyPurpose`, `isProxyPurpose`, `PurposeRoute`, `purposeRoute`, `purposeRouteTable`, and the now-unused `Database` import. `canonicalProxyRequest` becomes:

```ts
export function canonicalProxyRequest(input: Record<string, unknown>, model: string, outputCap: number): CanonicalProxyRequest {
  for (const key of Object.keys(input)) {
    if (!ALLOWED_FIELDS.has(key) && !OVERRIDDEN_FIELDS.has(key)) rejectField(key);
  }
  const messages = input.messages;
  if (!Array.isArray(messages) || messages.length === 0 ||
      !messages.every(message => message !== null && typeof message === 'object')) rejectField('messages');
  const body: Record<string, unknown> = { messages, model, max_tokens: outputCap, stream: false };
  if (input.response_format !== undefined) {
    const format = input.response_format;
    if (format === null || typeof format !== 'object' || Array.isArray(format) ||
        (format as Record<string, unknown>).type !== 'json_object') rejectField('response_format');
    body.response_format = format;
  }
  if (input.temperature !== undefined) {
    const temperature = input.temperature;
    if (typeof temperature !== 'number' || !Number.isFinite(temperature) || temperature < 0 || temperature > 2) rejectField('temperature');
    body.temperature = temperature;
  }
  return { body, outputCap };
}
```

(`ALLOWED_FIELDS`, `OVERRIDDEN_FIELDS`, `policyFieldLabel`, `policyRejected`, `rejectField` stay as-is; `ServiceError` import stays for `rejectField`.)

- [ ] **Step 5: Implement `token-proxy.ts`**

a) Imports: drop `canonicalProxyRequest, isProxyPurpose, purposeRoute, type PurposeRoute` routing imports; add:

```ts
import { providerExtraHeaders, type ResolvedLlmConfig } from './llm-config.js';
```

b) `tokenProxyConfig(env)` loses the provider fields and the URL assertion:

```ts
export interface TokenProxyConfig { maxTokens: number; maxConcurrentPerAccount: number;
  maxInFlight: number; timeoutMs: number; maxResponseBytes: number }

export const tokenProxyConfig = (env: NodeJS.ProcessEnv): TokenProxyConfig => {
  const maxConcurrentPerAccount = Number(env.PROXY_MAX_CONCURRENT ?? 2);
  if (!Number.isInteger(maxConcurrentPerAccount) || maxConcurrentPerAccount < 1)
    throw new Error('PROXY_MAX_CONCURRENT must be a positive integer.');
  return { maxTokens: boundedInteger(env.PROXY_MAX_TOKENS, 4000, Number.MAX_SAFE_INTEGER, 'PROXY_MAX_TOKENS'),
    maxConcurrentPerAccount,
    maxInFlight: boundedInteger(env.PROXY_MAX_IN_FLIGHT, 10, 1000, 'PROXY_MAX_IN_FLIGHT'),
    timeoutMs: boundedInteger(env.PROXY_TIMEOUT_MS, 30000, 300000, 'PROXY_TIMEOUT_MS'),
    maxResponseBytes: boundedInteger(env.PROXY_MAX_RESPONSE_BYTES, 1048576, 16777216, 'PROXY_MAX_RESPONSE_BYTES', 1024) };
};
```

c) `TokenProxy` takes the resolver and `relay()` resolves per call:

```ts
export class TokenProxy {
  constructor(private db: Database, private config: TokenProxyConfig,
              private resolveProvider: () => Promise<ResolvedLlmConfig | undefined>) { /* keep existing field inits */ }

  async relay(accountID: string, body: Record<string, unknown>, options: { signal?: AbortSignal } = {}): Promise<{ status: number; payload: Record<string, unknown> }> {
    const provider = await this.resolveProvider();
    if (!provider) throw new ServiceError('proxy_unavailable', 503);
    const forward = canonicalProxyRequest(body, provider.model, this.config.maxTokens);
    // ... unchanged concurrency/queue logic, now calling:
    // this.#meteredRelay(accountID, provider, forward.body, controller.signal, () => timeout)
  }
```

d) `#meteredRelay` signature becomes `(accountID: string, provider: ResolvedLlmConfig, body: Record<string, unknown>, signal: AbortSignal, timedOut: () => boolean)`; the fetch becomes:

```ts
      const response = await this.fetchFn(`${provider.baseUrl.replace(/\/$/, '')}/chat/completions`, { method: 'POST', signal,
        headers: { 'content-type': 'application/json', authorization: `Bearer ${provider.apiKey}`,
          ...providerExtraHeaders(provider.provider, provider.sessionID) }, body: JSON.stringify(body) });
```

The `outputCap` used for the admission estimate is `this.config.maxTokens` (passed from relay). The metric record loses `purpose`:

```ts
    } finally {if(began!==undefined)paliqueMetrics.proxyProviderDuration.record((performance.now()-began)/1000,{outcome});}
```

Delete the old `providerURL`/`providerKey` config fields, the `activeRateVersion` is unchanged, and `providerUsage` is unchanged.

- [ ] **Step 6: Wire `main.ts`**

`src/main.ts:39` keeps `let tokenProxy: TokenProxy | undefined;`. Line ~175 becomes (resolver always defined; "disabled" is now the per-call 503):

```ts
    const env = process.env;
    tokenProxy = new TokenProxy(db, tokenProxyConfig(env), () => resolveLlmConfig(db, env));
```

with `import { resolveLlmConfig } from './llm-config.js';`. The old `proxyConfig?...:undefined` conditional disappears (adjust the `services.tokenProxy` assignment accordingly — it can now always be set).

- [ ] **Step 7: Run and verify green**

Run: `npx tsx --test tests/token-proxy.test.ts tests/provider-headers.test.ts` → PASS (integration cases skip without `TEST_DATABASE_URL`; pure cases must pass).

- [ ] **Step 8: Commit**

```bash
git add services/api/src/proxy-policy.ts services/api/src/token-proxy.ts services/api/src/main.ts services/api/tests/token-proxy.test.ts
git commit -m "feat(proxy): resolve provider per call from the panel LLM config, drop purpose routing"
```

---

### Task 4: `app.ts` route removals, delete `conversation-policy.ts`, env/compose/docs

**Files:**
- Modify: `services/api/src/app.ts` (proxy route, accountPaths, imports)
- Delete: `services/api/src/conversation-policy.ts`
- Modify: `services/api/tests/proxy-quota.test.ts` (drop `conversationPolicy` usages)
- Modify: `services/api/compose.yaml:34-35,41`, `services/api/.env.example:47-55`
- Modify: `services/api/docs/token-billing.md`, `services/api/docs/outbound-trust-boundary.md`, `docs/api-capacity.md` (Spanish)

**Interfaces:**
- Consumes: Task 3's `relay(accountID, body, options?)` two-argument form.

- [ ] **Step 1: Update `app.ts`**

- Remove `import { conversationPolicy } from './conversation-policy.js';` (line 17).
- Remove `'/v1/conversation-policy'` from the `accountPaths` set (line 70).
- In the proxy route (lines ~614-620) replace the purpose block:

```ts
      const body = objectBody(request);
      checkClosed();
      const {status,payload}=await proxy.relay(account,body,{signal:controller.signal});
```

(deleting the `rawPurpose`/`purpose` parse, the `PROXY_REQUIRE_PURPOSE` check and the trailing `purpose` argument).

- Delete the `GET /v1/conversation-policy` route (lines ~778-784) and its comment.

- [ ] **Step 2: Delete the module and fix its test consumers**

```bash
git rm services/api/src/conversation-policy.ts
```

In `tests/proxy-quota.test.ts`: remove `import { conversationPolicy } from '../src/conversation-policy.js';` (line 8) and the three blocks using it (~lines 63-70: `subscriberPolicy`/`memberPolicy` assertions; ~line 339: `policy`). Keep every quota assertion that does not touch `conversationPolicy`; where the policy assert was the only check of a quota value, re-express it against the quota function the test already exercises (the allowance helpers) rather than through the policy.

- [ ] **Step 3: Env and compose**

`compose.yaml` — delete lines 34, 35 and 41 (`PROXY_PROVIDER_URL`, `PROXY_PROVIDER_KEY`, `PROXY_REQUIRE_PURPOSE`).

`.env.example` — replace the proxy block (lines 47-55) with:

```bash
# ---- Token billing proxy (docs/token-billing.md). Provider, model and API
# key come from the admin panel LLM config (llm_provider_configs); the env
# fallback is LEVELTEST_LLM_PROVIDER / LEVELTEST_LLM_API_KEY. ----
# Output-token estimate cap for admission (default 4000).
PROXY_MAX_TOKENS=
# Per-account in-flight relay cap (default 2).
PROXY_MAX_CONCURRENT=
```

- [ ] **Step 4: Docs**

- `services/api/docs/token-billing.md`: remove the `x-palique-purpose` paragraph (~line 110), the `400 unknown_purpose` and `purpose_required` error rows (~line 148), the `PROXY_REQUIRE_PURPOSE` env row (~line 168), and rewrite the ~line-180 paragraph: the proxy takes provider/model/key from the panel LLM config per call; the client sends no purpose and no model that is honored.
- `services/api/docs/outbound-trust-boundary.md`: replace the token-proxy row's env source with "panel `llm_provider_configs` base URL (`assertOutboundUrl` applied inside `resolveLlmConfig`); env fallback `LEVELTEST_LLM_URL`".
- `docs/api-capacity.md` (Spanish, ~line 37) — replace the routing paragraph with:

```markdown
**Política de petición**: el modelo, el proveedor y la clave salen de la
configuración LLM del panel (`llm_provider_configs`, resuelta por llamada, con
fallback a `LEVELTEST_LLM_*`); la petición se reconstruye desde una allowlist
(`400 request_policy_violation` en caso contrario) y el cliente nunca elige
modelo ni precio. Sin configuración resoluble, `503 proxy_unavailable`. La
cuota diaria de suscripción se acredita como entrada idempotente de ledger
(`subscription-grant:<fecha>:<cuenta>`), así que el agotamiento de cuota usa
la misma admisión por saldo.
```

- [ ] **Step 5: Observability contract**

`services/api/docs/observability.md`: update the `palique.proxy.provider.duration` entry — its label set is now `{outcome}` only (the `purpose` label is gone with the header). Then run `make obs-check` → green.

- [ ] **Step 6: Verify**

Run: `cd services/api && npx tsc --noEmit` → green
Run: `npx tsx --test tests/proxy-quota.test.ts tests/token-proxy.test.ts` → pure parts PASS.

- [ ] **Step 7: Commit**

```bash
git add -A services/api docs/api-capacity.md
git commit -m "refactor(proxy): remove conversation-policy endpoint and purpose header plumbing"
```

---

### Task 5: Amend migration 057

**Files:**
- Modify: `services/api/migrations/057_proxy_purpose_routes.sql` (rewrite; never applied to any persistent environment)

- [ ] **Step 1: Rewrite the file**

Replace the entire content with:

```sql
-- Widen the operator audit action enum for actions that arrive without an
-- account (release-hold reconciliation). Purpose routing was removed before
-- shipping (change proxy-model-from-panel), so this migration no longer
-- creates proxy_purpose_routes and the enum never gains set_purpose_route.
ALTER TABLE token_admin_audit DROP CONSTRAINT token_admin_audit_action_check;
ALTER TABLE token_admin_audit ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE token_admin_audit ADD CONSTRAINT token_admin_audit_action_check
  CHECK (action IN ('grant_unlock','revoke_unlock','grant_balance','grant_quota','release_hold'));
```

(Rationale: `040_proxy_usage.sql` created the enum with only `grant_unlock,revoke_unlock,grant_balance`; `grant_quota` and `release_hold` are still needed by the CLI. `account_id` stays nullable for anonymous-of-account rows.)

- [ ] **Step 2: Verify a fresh migration pass**

Run: `make test-api` runs migrations in its ephemeral stack (or: `docker compose -f services/api/tests/compose.smoke.yaml up` and watch the migrate step) → migrations apply cleanly, `proxy_purpose_routes` does not exist:

```bash
docker compose --project-directory services/api -f services/api/compose.yaml exec -T database \
  psql -U app -d app -tAc "SELECT to_regclass('proxy_purpose_routes');"
```

Expected output: empty (NULL).

- [ ] **Step 3: Commit**

```bash
git add services/api/migrations/057_proxy_purpose_routes.sql
git commit -m "refactor(db): amend migration 057 — no purpose routes, audit enum without set_purpose_route"
```

---

### Task 6: Android — `api` engine without model, purpose header or policy

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/engines/OpenAICompatibleEngine.kt` (body building, header emission, blank-model rule)
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/engines/RemoteEngineConfig` section of the same file (`purposeHeader` removed, `API` row updated)
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/engines/ConversationEngineFactory.kt` (`apiProxy` loses `modelOverride`)
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/engines/ConversationEngineDeps.kt` (`policyModel` removed; `openai` becomes nullable for JVM testability)
- Delete: `apps/android/app/src/main/java/com/jkdc/app/core/engines/ConversationPolicyStore.kt`
- Delete: `apps/android/app/src/test/java/com/jkdc/app/core/engines/ConversationPolicyStoreTest.kt`
- Test: `apps/android/app/src/test/java/com/jkdc/app/core/engines/OpenAICompatibleEngineApiTest.kt` (new)

**Interfaces:**
- Consumes: nothing from the server (contract is absence of `model`).
- Produces: `RemoteEngineConfig.API` with no `purposeHeader`; `ConversationEngineDeps(appContext, openai: APIClient?, keyLookup, json, readSession, modelLookup)`. The registry row for `openai` must null-check: `OpenAIEngineAdapter(deps.openai ?: error("openai engine needs the OpenAI client"))`.

- [ ] **Step 1: Write the failing JVM test**

Create `apps/android/app/src/test/java/com/jkdc/app/core/engines/OpenAICompatibleEngineApiTest.kt`:

```kotlin
package com.jkdc.app.core.engines

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `api` engine delegates provider/model/credentials to the server: the
    request body carries no model field, no purpose header, and the call does
    not depend on any prior policy fetch. */
class OpenAICompatibleEngineApiTest {
    @Test
    fun `api engine sends no model field and no purpose header`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"hola"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
            )
        )
        val engine = OpenAICompatibleEngine(
            RemoteEngineConfig.API,
            ConversationEngineDeps(
                appContext = null,
                openai = null,
                keyLookup = { null },
                json = Json,
                readSession = { null },
            ),
            baseUrl = server.url("/").toString(),
            completionsPath = "v1/proxy/responses",
            credential = { "session-bearer" },
        )
        engine.respond("instructions", "hola")
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("x-palique-purpose"))
        assertEquals("Bearer session-bearer", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertNull(body["model"])
        assertEquals(false, body["stream"]?.toString()?.toBooleanStrict())
        server.shutdown()
    }
}
```

(If `respond` needs the session-credential lambda's exact type, mirror the factory's `credential = { readSession()?.takeIf { ... }?.accessToken }` shape — `suspend () -> String?`.)

- [ ] **Step 2: Run and verify failure**

Run: `cd apps/android && ./gradlew :app:testDebugUnitTest --tests "com.jkdc.app.core.engines.OpenAICompatibleEngineApiTest"`
Expected: compile FAIL — `purposeHeader`/`model` behavior mismatch (`model` present in body, or `ConversationEngineDeps` has no `openai = null` overload).

- [ ] **Step 3: Implement the engine changes**

In `OpenAICompatibleEngine.kt`:

a) `RemoteEngineConfig`: delete the `purposeHeader` property and update the `API` row:

```kotlin
    // The production first-party path: all calls ride the backend proxy with
    // the session bearer and no client model — the server resolves provider,
    // model and credentials from the panel config (change proxy-model-from-panel).
    val API = RemoteEngineConfig("api", "", "", "")
```

b) The body construction replaces the blank-model throw:

```kotlin
        val model = modelOverride?.invoke(purpose) ?: config.defaultModel
        // Proxy engines (credential != null) leave the model to the server;
        // BYOK engines must resolve one or the request is invalid.
        if (model.isBlank() && credential == null) {
            throw EngineException("${config.id} engine needs the ${config.keyEnvVar} environment variable or system property")
        }
        val body = buildJsonObject {
            if (model.isNotBlank()) put("model", model)
            put("messages", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", systemPrompt) },
                buildJsonObject { put("role", "user"); put("content", input) },
            )))
            if (schema != null) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            config.extraBody?.let { extra -> extra.forEach { (k, v) -> put(k, v) } }
            put("stream", false)
        }.toString()
```

c) Delete the `if (config.purposeHeader) { header("x-palique-purpose", ...) }` block (and the property's doc).

In `ConversationEngineFactory.kt`, `apiProxy` loses the override:

```kotlin
    /** The production first-party path: the same relay as hosted-proxy, but
        the server resolves provider/model/credentials per call from the panel
        LLM config. No key material, no client model, no policy round-trip. */
    private fun apiProxy(deps: ConversationEngineDeps): TeachingClient {
        val readSession = deps.readSession
            ?: throw EngineException("api engine needs the app account session source")
        val origin = HostedConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)?.origin
            ?: throw EngineException("api engine needs a configured Palique API origin")
        return OpenAICompatibleEngine(
            RemoteEngineConfig.API,
            deps,
            baseUrl = origin,
            completionsPath = "v1/proxy/responses",
            credential = { readSession()?.takeIf { it.isValid(System.currentTimeMillis()) }?.accessToken },
            proxyErrorMessages = true,
        )
    }
```

In `ConversationEngineDeps.kt`: delete the `policyModel` property; change `val openai: APIClient` to `val openai: APIClient? = null`; in `ConversationEngineFactory.kt`'s registry, the `openai` row becomes `OpenAIEngineAdapter(deps.openai ?: error("openai engine needs the OpenAI client"))`.

Delete `ConversationPolicyStore.kt` and `ConversationPolicyStoreTest.kt`:

```bash
git rm apps/android/app/src/main/java/com/jkdc/app/core/engines/ConversationPolicyStore.kt \
       apps/android/app/src/test/java/com/jkdc/app/core/engines/ConversationPolicyStoreTest.kt
```

- [ ] **Step 4: Run and verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jkdc.app.core.engines.*"` → PASS (engine tests + `EngineSelectionPolicyTest` + `LocalEngineSwitcherTest`).

- [ ] **Step 5: Commit**

```bash
git add apps/android/app/src/main/java/com/jkdc/app/core/engines apps/android/app/src/test
git commit -m "refactor(android): api engine sends no model or purpose header, drops policy store"
```

---

### Task 7: Android — remove the policy flow from `AppViewModel` and `MinuteCommerceClient`

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` (lines 601-603, 626, 670, 672-706, 2019, 2674, 3287)
- Modify: `apps/android/app/src/main/java/com/jkdc/app/network/MinuteCommerceClient.kt` (lines ~145-170)

- [ ] **Step 1: Remove the fields and methods**

In `AppViewModel.kt`:

- Delete the `conversationPolicyStore` field (line 601), the `policyCommerceClient` field (lines 602-603) and `policyFetchJob` (line 670).
- In the `engineDeps` construction (line 626) delete the `policyModel = { purpose -> conversationPolicyStore.modelFor(purpose) },` line.
- Delete `refreshConversationPolicy` (672-702) and `ensureConversationPolicy` (704-706) including their doc comments.
- Delete the call at line 2019 (`refreshConversationPolicy(force = true)`) and the two guard lines at 2674 and 3287 (`if (resolveEngineSelection() == "api") ensureConversationPolicy()`).

In `MinuteCommerceClient.kt`: delete the `conversationPolicy` function (~145-170) with its doc comment, and any now-unused imports it exclusively owned.

- [ ] **Step 2: Verify no dangling references**

Run: `cd apps/android && grep -rn "conversationPolicy\|ConversationPolicy\|policyFetchJob\|policyCommerceClient\|policyModel" app/src --include="*.kt"` → no matches.
Run: `grep -rn "policy" app/src/main/res/values*/strings.xml` → if any policy-screen strings exist, delete them (both EN and ES variants); expected: none.

- [ ] **Step 3: Build and test**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` → green.

- [ ] **Step 4: Update the lab doc (Spanish)**

In `docs/local-android-lab.md`, rewrite the section "Using the first-party conversation engine (`api` / hosted-proxy)": the proxy now takes its provider/model/key from the admin panel's "Configuración de IA" screen (the same config as exam generation, with OpenCode free models supported); the remaining lab steps are: configure the panel, then `tokens-admin grant-unlock` + `grant-balance` for `lab@palique.test`. Delete the purpose-route inspection step and the `PROXY_PROVIDER_URL`/`PROXY_PROVIDER_KEY` items; update the `proxy_unavailable` troubleshooting row ("no LLM config in the panel and no `LEVELTEST_LLM_*` env fallback").

- [ ] **Step 5: Commit**

```bash
git add apps/android/app/src docs/local-android-lab.md
git commit -m "refactor(android): drop conversation-policy fetch and client parsing"
```

---

### Task 8: Full verification and change reconciliation

**Files:**
- Create: `openspec/changes/proxy-model-from-panel/verify-report.md`

- [ ] **Step 1: Server verification**

```bash
cd services/api && npx tsc --noEmit
make test-api        # full integration suite on the ephemeral PG stack
make check           # exporters --check, cross-platform, db schema
```

All three green. Record the exact numbers (tests passed/skipped) for the report.

- [ ] **Step 2: Android verification**

```bash
cd apps/android && ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Green; record numbers.

- [ ] **Step 3: Lab E2E (manual, real phone or emulator)**

1. Rebuild and relaunch the lab: `docker compose --project-directory services/api -f services/api/compose.yaml -f services/api/compose.lab.yaml build api migrate && make lab-up && make migrate`.
2. In the admin panel (`make admin-open`), configure "Configuración de IA" with provider `opencode` and a free model (e.g. `glm-5.3-flash`).
3. `make lab-account` → adopt the session on the device; `make lab-install`.
4. Select the **Palique API** engine, run a conversation turn.
5. Confirm on the server side: `docker compose ... exec api node dist/src/tokens-admin.js show <LAB_ACCOUNT_ID>` shows the attempt settled; the stub is the panel model.

Record outcomes and screenshots in `E2E_EVIDENCE_DIR` (SDD-ignored directory), not overwriting versioned evidence.

- [ ] **Step 4: Write the verify report**

Create `openspec/changes/proxy-model-from-panel/verify-report.md`: what ran and passed, what did not run (explicitly), the E2E evidence paths, and a reconciliation note that `proxy-api-provider`'s spec deltas "Server-owned model per purpose" and "Policy endpoint is display-only" are superseded by this change (its reservation/policy/quota features remain in force).

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/proxy-model-from-panel/verify-report.md
git commit -m "docs(proxy): verify proxy-model-from-panel — server, android and lab E2E"
```

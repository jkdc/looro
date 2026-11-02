import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertOutboundUrl } from '../src/outbound-url.js';
import { resolveLlmConfig } from '../src/llm-config.js';
import type { Database } from '../src/db.js';

test('outbound URLs require https except loopback transports', () => {
  assert.equal(assertOutboundUrl('https://provider.example/v1', 'TEST_URL'), 'https://provider.example/v1');
  assert.equal(assertOutboundUrl('http://127.0.0.1:9/v1', 'TEST_URL'), 'http://127.0.0.1:9/v1');
  assert.equal(assertOutboundUrl('http://localhost:9/v1', 'TEST_URL'), 'http://localhost:9/v1');
  assert.equal(assertOutboundUrl('http://[::1]:9/v1', 'TEST_URL'), 'http://[::1]:9/v1');
  // Plain http to a public host, other schemes and embedded credentials all
  // fail: these URLs carry the server API key in the Authorization header.
  assert.throws(() => assertOutboundUrl('http://provider.example/v1', 'TEST_URL'), /must be https/);
  assert.throws(() => assertOutboundUrl('ftp://provider.example/v1', 'TEST_URL'), /must be https/);
  assert.throws(() => assertOutboundUrl('https://user:pass@provider.example/v1', 'TEST_URL'), /credentials/);
  assert.throws(() => assertOutboundUrl('not a url', 'TEST_URL'), /absolute/);
});

test('resolved LLM configuration skips or rejects non-https provider URLs', async () => {
  const emptyDB = { query: async () => ({ rows: [] }) } as unknown as Database;
  // Env path: a public plain-http URL is a configuration error at startup.
  await assert.rejects(resolveLlmConfig(emptyDB, {
    LEVELTEST_LLM_PROVIDER: 'glm', LEVELTEST_LLM_API_KEY: 'k', LEVELTEST_LLM_URL: 'http://provider.example/v1' } as NodeJS.ProcessEnv),
  /must be https/);
  // Env path: loopback transports (local lab) stay allowed.
  const loopback = await resolveLlmConfig(emptyDB, {
    LEVELTEST_LLM_PROVIDER: 'glm', LEVELTEST_LLM_API_KEY: 'k', LEVELTEST_LLM_URL: 'http://127.0.0.1:9/v1' } as NodeJS.ProcessEnv);
  assert.equal(loopback?.baseUrl, 'http://127.0.0.1:9/v1');
  // DB path: an operator row pointing at a public plain-http URL is skipped,
  // never silently used; the env path then decides.
  const rows = [{ provider: 'glm', base_url: 'http://provider.example/v1', model: 'm', api_key_encrypted: 'x' }];
  const skipped = await resolveLlmConfig({ query: async () => ({ rows }) } as unknown as Database, {} as NodeJS.ProcessEnv);
  assert.equal(skipped, undefined);
});

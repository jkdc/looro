import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { connectDatabase } from '../src/db.js';

const baseURL = process.env.SMOKE_API_BASE_URL;
const proxyToken = process.env.SMOKE_ADMIN_PROXY_TOKEN;

test('compiled API is ready over HTTP and enforces admin authentication', { skip: !baseURL || !proxyToken }, async () => {
  const deadline = Date.now() + 45_000;
  let ready = false;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseURL}/readyz`, { signal: AbortSignal.timeout(2_000) });
      if (response.ok) {
        const body = await response.json() as { database?: boolean };
        if (body.database === true) { ready = true; break; }
      }
    } catch { /* The API may still be starting; retry only until the fixed deadline. */ }
    await delay(250);
  }
  assert.equal(ready, true, 'compiled API did not become ready within 45 seconds');

  const pricing = await fetch(`${baseURL}/v1/pricing`, { signal: AbortSignal.timeout(2_000) });
  assert.equal(pricing.status, 200);
  const pricingBody = await pricing.json() as { currency?: string; rateVersion?: string };
  assert.equal(pricingBody.currency, 'USD');
  assert.equal(typeof pricingBody.rateVersion, 'string');

  const databaseURL = process.env.TEST_DATABASE_URL;
  assert.ok(databaseURL, 'smoke API test requires the isolated PostgreSQL test database');
  const db = connectDatabase(databaseURL);
  const admissionHits = async () => {
    const result = await db.query<{ hits: string }>(`SELECT COALESCE(SUM(hits), 0)::text AS hits FROM auth_rate_limits
      WHERE operation='admin-api' AND scope='network'`);
    return BigInt(result.rows[0]!.hits);
  };
  try {
    const before = await admissionHits();
    const admin = await fetch(`${baseURL}/v1/admin/level-tests/status`, {
      headers: { 'x-app-client-ip': '198.51.100.23', 'x-app-proxy-token': proxyToken! },
      signal: AbortSignal.timeout(2_000),
    });
    assert.equal(admin.status, 401);
    assert.deepEqual(await admin.json(), { error: { code: 'admin_sign_in_required' } });
    assert.equal(await admissionHits(), before + 1n, 'denied admin HTTP request must add exactly one network admission hit');
  } finally { await db.end(); }
});

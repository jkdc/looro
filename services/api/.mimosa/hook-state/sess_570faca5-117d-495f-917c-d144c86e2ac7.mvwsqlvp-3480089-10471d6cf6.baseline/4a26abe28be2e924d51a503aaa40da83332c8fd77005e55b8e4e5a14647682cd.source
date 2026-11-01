import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AuthAdmission } from '../src/auth-admission.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => {
  if (db) await db.query("DELETE FROM auth_rate_limits WHERE operation='public'");
});
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const hmacKey = 'e'.repeat(64), proxyToken = 'f'.repeat(64);
const identified = (address: string) => ({
  'x-app-client-ip': address, 'x-app-proxy-token': proxyToken
});
// Tiny per-instance caps so the durable path is reachable inside a test.
const app = (overrides: Partial<Record<string, { network: number; global: number }>>) => createApp({
  db: db!, auth: {},
  accounts: { admission: new AuthAdmission(db!, { hmacKey, proxyToken, allowLocalLoopback: false }, overrides as never) }
});
const pricing = (service: ReturnType<typeof app>, headers?: Record<string, string>) =>
  service.inject({ method: 'GET', url: '/v1/pricing', headers: headers ?? {} });

integration('public content reads are durably limited per client network', async () => {
  const service = app({ public: { network: 3, global: 1000 } });
  try {
    assert.equal((await pricing(service, identified('203.0.113.7'))).statusCode, 200);
    assert.equal((await pricing(service, identified('203.0.113.7'))).statusCode, 200);
    assert.equal((await pricing(service, identified('203.0.113.7'))).statusCode, 200);
    const rejected = await pricing(service, identified('203.0.113.7'));
    assert.equal(rejected.statusCode, 429, 'a fourth read from one network must be durably rejected');
    assert.equal(rejected.headers['retry-after'], '3600');
    assert.equal((await pricing(service, identified('203.0.113.8'))).statusCode, 200,
      'another network must keep its own allowance');
  } finally { await service.close(); }
});

integration('public reads without an authenticated identity share only the global cap', async () => {
  const service = app({ public: { network: 3, global: 2 } });
  try {
    // No proxy headers: never fail closed (a shared per-socket bucket would
    // let one visitor exhaust everyone), but the global cap still applies.
    assert.equal((await pricing(service)).statusCode, 200);
    assert.equal((await pricing(service)).statusCode, 200);
    const rejected = await pricing(service);
    assert.equal(rejected.statusCode, 429, 'the global allowance must be durable');
    assert.equal(rejected.headers['retry-after'], '3600');
  } finally { await service.close(); }
});

integration('readiness probes and the payment return are never admission limited', async () => {
  const service = app({ public: { network: 1, global: 1 } });
  try {
    assert.equal((await service.inject({ method: 'GET', url: '/readyz' })).statusCode, 200);
    assert.equal((await service.inject({ method: 'GET', url: '/readyz' })).statusCode, 200);
    assert.equal((await service.inject({ method: 'GET', url: '/payment-return' })).statusCode, 200);
    assert.equal((await service.inject({ method: 'GET', url: '/payment-return' })).statusCode, 200);
  } finally { await service.close(); }
});

integration('public reads stay open when accounts admission is not configured', async () => {
  const service = createApp({ db: db!, auth: {} });
  try {
    assert.equal((await pricing(service)).statusCode, 200);
  } finally { await service.close(); }
});

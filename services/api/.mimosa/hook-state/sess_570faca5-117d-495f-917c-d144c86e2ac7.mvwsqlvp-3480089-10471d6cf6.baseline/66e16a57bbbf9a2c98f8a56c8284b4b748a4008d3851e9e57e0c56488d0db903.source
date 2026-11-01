import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { trialEligibility, type TrialAttestor } from '../src/trial.js';
import { TRIAL_MS } from '../src/pricing.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE trial_devices CASCADE'); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

const attestor = (overrides: Partial<Awaited<ReturnType<TrialAttestor['verify']>>> = {}): TrialAttestor => ({
  async verify() { return { deviceReference: 'device-fresh', attestationKeyID: 'key-1', previouslyClaimed: false, ...overrides }; }
});

integration('a fresh device receives the full trial allowance once and later reads it back', async () => {
  assert.deepEqual(await trialEligibility(db!, {}, attestor()), { remainingMilliseconds: TRIAL_MS });
  const row = (await db!.query('SELECT device_reference,attestation_key_id,used_ms,reserved_ms,blocked FROM trial_devices')).rows[0]!;
  assert.deepEqual(row, { device_reference: 'device-fresh', attestation_key_id: 'key-1',
    used_ms: '0', reserved_ms: '0', blocked: false });
  // A retry is the same device, not a second grant.
  assert.deepEqual(await trialEligibility(db!, {}, attestor()), { remainingMilliseconds: TRIAL_MS });
  await db!.query("UPDATE trial_devices SET used_ms=300000, reserved_ms=120000 WHERE device_reference='device-fresh'");
  assert.deepEqual(await trialEligibility(db!, {}, attestor()), { remainingMilliseconds: TRIAL_MS - 420_000 });
  assert.equal((await db!.query('SELECT count(*)::int AS n FROM trial_devices')).rows[0]!.n, 1);
});

integration('a previously claimed or blocked device never receives another allowance', async () => {
  await assert.rejects(trialEligibility(db!, {}, attestor({ previouslyClaimed: true })),
    (error: { code?: string }) => error.code === 'trial_already_claimed');
  assert.equal((await db!.query('SELECT count(*)::int AS n FROM trial_devices')).rows[0]!.n, 0);

  await db!.query("INSERT INTO trial_devices(device_reference,attestation_key_id,blocked) VALUES('device-fresh','key-1',true)");
  await assert.rejects(trialEligibility(db!, {}, attestor()),
    (error: { code?: string }) => error.code === 'trial_unavailable');
});

integration('an attestation key change invalidates the device instead of granting a new trial', async () => {
  await db!.query("INSERT INTO trial_devices(device_reference,attestation_key_id) VALUES('device-fresh','key-old')");
  await assert.rejects(trialEligibility(db!, {}, attestor({ attestationKeyID: 'key-new' })),
    (error: { code?: string }) => error.code === 'trial_unavailable');
  assert.equal((await db!.query("SELECT attestation_key_id FROM trial_devices WHERE device_reference='device-fresh'")).rows[0]!.attestation_key_id,
    'key-old');
});

integration('the trial eligibility route reports the verified remainder and fails closed', async () => {
  const app = createApp({ db: db!, auth: {}, attestor: attestor() });
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/trial/eligibility', payload: { proof: 'opaque' } });
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { remainingMilliseconds: TRIAL_MS });
    await db!.query("UPDATE trial_devices SET used_ms=600000");
    assert.deepEqual((await app.inject({ method: 'POST', url: '/v1/trial/eligibility',
      payload: { proof: 'opaque' } })).json(), { remainingMilliseconds: 0 });
  } finally { await app.close(); }

  const claimed = createApp({ db: db!, auth: {}, attestor: attestor({ previouslyClaimed: true }) });
  try {
    const response = await claimed.inject({ method: 'POST', url: '/v1/trial/eligibility', payload: {} });
    assert.equal(response.statusCode, 403);
    assert.equal(response.json().error?.code, 'trial_already_claimed');
  } finally { await claimed.close(); }
});

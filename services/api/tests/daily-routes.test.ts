import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import type { Database } from '../src/db.js';

const fixture = JSON.parse(readFileSync(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));

test('daily public package route exists and stays disabled until explicitly enabled', async () => {
  const db = { query: async () => ({ rows: [], rowCount: 0 }), connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/daily-practice/current?language=en' });
    assert.equal(response.statusCode, 503);
    assert.equal(response.json().error.code, 'daily_unavailable');
    assert.equal(response.headers['cache-control'], 'no-store');
  } finally { await app.close(); }
});

test('daily current returns only the published public projection with a short ETag cache', async () => {
  const payload = { ...fixture, privateAnswerKey: { listening: ['secret'] } };
  const db = { query: async (sql: string) => sql.includes('SELECT read_enabled')
    ? { rows: [{ read_enabled: true, enabled_languages: ['en'] }], rowCount: 1 }
    : { rows: [{ public_payload: payload, content_hash: payload.contentHash }], rowCount: 1 },
    connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/daily-practice/current?language=en' });
    assert.equal(response.statusCode, 200, response.body);
    assert.equal(response.headers.etag, `"${payload.contentHash}"`);
    assert.equal(response.headers['cache-control'], 'public, max-age=30');
    assert.equal(response.json().package.privateAnswerKey, undefined);
    const notModified = await app.inject({ method: 'GET', url: '/v1/daily-practice/current?language=en',
      headers: { 'if-none-match': `"${payload.contentHash}"` } });
    assert.equal(notModified.statusCode, 304);
  } finally { await app.close(); }
});

test('daily package version route serves only the published public projection', async () => {
  const payload = { ...fixture, privateAnswerKey: { practice: ['secret'] } };
  const db = { query: async (sql: string) => sql.includes('SELECT read_enabled')
    ? { rows: [{ read_enabled: true, enabled_languages: ['en'] }], rowCount: 1 }
    : sql.includes('SELECT p.public_payload')
      ? { rows: [{ public_payload: payload, content_hash: payload.contentHash, language: 'en' }], rowCount: 1 }
      : { rows: [], rowCount: 0 }, connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: `/v1/daily-practice/packages/${payload.packageVersionId}` });
    assert.equal(response.statusCode, 200, response.body);
    assert.equal(response.json().privateAnswerKey, undefined);
    assert.equal(response.headers['cache-control'], 'public, max-age=30');
  } finally { await app.close(); }
});

test('daily historical package reads honor the currently enabled language allowlist', async () => {
  const db = { query: async (sql: string) => sql.includes('SELECT read_enabled')
    ? { rows: [{ read_enabled: true, enabled_languages: [] }], rowCount: 1 }
    : sql.includes('SELECT p.public_payload')
      ? { rows: [{ public_payload: fixture, content_hash: fixture.contentHash, language: 'en' }], rowCount: 1 }
      : { rows: [], rowCount: 0 }, connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: `/v1/daily-practice/packages/${fixture.packageVersionId}` });
    assert.equal(response.statusCode, 404);
    assert.equal(response.json().error.code, 'not_found');
  } finally { await app.close(); }
});

test('daily audio route is no longer served by the API', async () => {
  const db = { query: async () => ({ rows: [], rowCount: 0 }), connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const path = `/v1/daily-practice/packages/${fixture.packageVersionId}/audio`;
    const response = await app.inject({ method: 'GET', url: path });
    assert.equal(response.statusCode, 404);
  } finally { await app.close(); }
});

test('daily attempt creation requires authenticated identity before any body is accepted', async () => {
  const db = { query: async () => ({ rows: [], rowCount: 0 }), connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/daily-practice/attempts', payload: {} });
    assert.equal(response.statusCode, 401);
    assert.equal(response.headers['cache-control'], 'no-store');
  } finally { await app.close(); }
});

test('daily attempt history is private and requires authentication', async () => {
  const db = { query: async () => ({ rows: [], rowCount: 0 }), connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/daily-practice/attempts?language=en' });
    assert.equal(response.statusCode, 401);
    assert.equal(response.headers['cache-control'], 'no-store');
  } finally { await app.close(); }
});

test('daily review reads are private and require authentication', async () => {
  const db = { query: async () => ({ rows: [], rowCount: 0 }), connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/daily-practice/attempts/11111111-1111-4111-8111-111111111111/review' });
    assert.equal(response.statusCode, 401);
    assert.equal(response.headers['cache-control'], 'no-store');
  } finally { await app.close(); }
});

test('daily attempt package history is private and requires authentication', async () => {
  const db = { query: async () => ({ rows: [], rowCount: 0 }), connect: async () => { throw new Error('not used'); } } as unknown as Database;
  const app = createApp({ db, auth: {} });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/daily-practice/attempts/11111111-1111-4111-8111-111111111111/package' });
    assert.equal(response.statusCode, 401);
    assert.equal(response.headers['cache-control'], 'no-store');
  } finally { await app.close(); }
});

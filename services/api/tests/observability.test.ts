// Observability tests. Sections are appended by successive observability tasks;
// keep one clearly marked section per module.
//
// Section 1: structured logger (src/log.ts)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { log } from '../src/log.js';

test('log emits one JSON line per call with allow-listed scalar fields only', () => {
  const original = console.info;
  const lines: string[] = [];
  console.info = (value: string) => { lines.push(value); };
  try {
    log.info('http_request', { request_id: 'abc', status: 200, duration_ms: 12.4, optional: undefined, nothing: null });
    assert.equal(lines.length, 1);
    const parsed = JSON.parse(lines[0]!) as Record<string, unknown>;
    assert.equal(parsed['event'], 'http_request');
    assert.equal(parsed['level'], 'info');
    assert.equal(parsed['request_id'], 'abc');
    assert.equal(parsed['status'], 200);
    assert.equal(parsed['duration_ms'], 12.4); // numbers preserved, never stringified
    assert.equal('optional' in parsed, false); // undefined dropped
    assert.equal(parsed['nothing'], null);
    assert.ok(typeof parsed['timestamp'] === 'string' && !Number.isNaN(Date.parse(parsed['timestamp'] as string)));
  } finally { console.info = original; }
});

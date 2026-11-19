import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requestRateLimitPerMinute } from '../src/request-rate-limit.js';

test('the per-network rate limit defaults to 120 when unset or empty', () => {
  assert.equal(requestRateLimitPerMinute({}), 120);
  assert.equal(requestRateLimitPerMinute({ API_REQUESTS_PER_MINUTE: '' }), 120);
});

test('the per-network rate limit accepts a configured integer', () => {
  assert.equal(requestRateLimitPerMinute({ API_REQUESTS_PER_MINUTE: '600' }), 600);
  assert.equal(requestRateLimitPerMinute({ API_REQUESTS_PER_MINUTE: '1' }), 1);
});

test('the per-network rate limit rejects non-integers and out-of-range values', () => {
  for (const value of ['0', '-5', '10001', '1.5', 'abc']) {
    assert.throws(() => requestRateLimitPerMinute({ API_REQUESTS_PER_MINUTE: value }),
      /API_REQUESTS_PER_MINUTE/, `value ${value} must be rejected`);
  }
});

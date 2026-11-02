import { test } from 'node:test';
import assert from 'node:assert/strict';
import { providerExtraHeaders } from '../src/llm-config.js';

test('opencode transport sends the session header the gateway requires', () => {
  const headers = providerExtraHeaders('opencode');
  assert.match(headers['x-opencode-session'] ?? '',
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.equal(headers['user-agent'], 'palique-api/1.0');
});

test('an explicit session id is honored', () => {
  assert.equal(providerExtraHeaders('opencode', 'fixed-session')['x-opencode-session'], 'fixed-session');
});

test('non-opencode providers carry no extra headers', () => {
  assert.deepEqual(providerExtraHeaders('glm'), {});
  assert.deepEqual(providerExtraHeaders('openai'), {});
});

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { recordDailyProviderUsage } from '../src/daily-jobs.js';

test('daily_provider_usage_persists_only_token_counts_for_later_analysis', async () => {
  const calls: unknown[][] = [];
  const tx = { query: async (...args: unknown[]) => { calls.push(args); return { rowCount: 1 }; } } as any;
  await recordDailyProviderUsage(tx, '11111111-1111-4111-8111-111111111111', {
    state: 'complete', tokensIn: 120, tokensOut: 45,
  });
  assert.match(String(calls[0]?.[0]), /UPDATE daily_provider_operations SET usage=/);
  assert.deepEqual(calls[0]?.[1], ['11111111-1111-4111-8111-111111111111',
    JSON.stringify({ state: 'complete', tokensIn: 120, tokensOut: 45 })]);
});

test('daily_provider_usage_rejects_prompt_or_personal_data_fields', async () => {
  let called = false;
  const tx = { query: async () => { called = true; return { rowCount: 1 }; } } as any;
  await assert.rejects(recordDailyProviderUsage(tx, '11111111-1111-4111-8111-111111111111', {
    state: 'complete', tokensIn: 120, tokensOut: 45, prompt: 'private content',
  } as any), /invalid_daily_usage/);
  assert.equal(called, false);
});

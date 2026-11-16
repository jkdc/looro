import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { Database } from '../src/db.js';
import { createDailyLlmProvider } from '../src/daily-llm.js';
import type { ChatMessage, ChatReply, ResolvedLlmConfig } from '../src/llm-config.js';

const config: ResolvedLlmConfig = { provider: 'glm', baseUrl: 'https://llm.example/v1', model: 'exam-model', apiKey: 'secret' };
const messages: ChatMessage[] = [{ role: 'user', content: 'Generate daily content' }];

test('daily_generation_and_review_reuse_the_exam_provider_config_without_a_second_config', async () => {
  const seen: unknown[] = [];
  const client = await createDailyLlmProvider({} as Database, {}, {
    resolve: async () => config,
    complete: async (resolved, input) => { seen.push([resolved, input]); return { content: '{"ok":true}', tokensIn: 3, tokensOut: 2 }; },
  });
  const reply = await client.complete(messages);
  assert.equal(client.provider, 'glm');
  assert.equal(client.model, 'exam-model');
  assert.equal(reply.content, '{"ok":true}');
  assert.deepEqual(seen, [[config, messages]]);
  assert.deepEqual(client.usage(), { state: 'complete', tokensIn: 3, tokensOut: 2 });
});

test('daily_provider_exposes_the_same_token_usage_accounting_as_exam_generation', async () => {
  const client = await createDailyLlmProvider({} as Database, {}, {
    resolve: async () => config,
    complete: async () => ({ content: '{}', tokensIn: 12, tokensOut: 7 }),
  });
  await client.complete(messages);
  await client.complete(messages);
  assert.deepEqual(client.usage(), { state: 'complete', tokensIn: 24, tokensOut: 14 });
});

test('daily_provider_checkpoints_token_usage_before_and_after_a_call_for_pre_release_analysis', async () => {
  const snapshots: unknown[] = [];
  const client = await createDailyLlmProvider({} as Database, {}, {
    resolve: async () => config,
    complete: async () => ({ content: '{}', tokensIn: 12, tokensOut: 7 }),
    onUsage: async snapshot => { snapshots.push(snapshot); },
  });
  await client.complete(messages);
  assert.deepEqual(snapshots, [
    { state: 'unknown', tokensIn: null, tokensOut: null },
    { state: 'complete', tokensIn: 12, tokensOut: 7 },
  ]);
});

test('daily_provider_marks_token_usage_unknown_when_a_provider_call_fails_without_usage', async () => {
  const client = await createDailyLlmProvider({} as Database, {}, {
    resolve: async () => config,
    complete: async () => { throw new Error('transport'); },
  });
  await assert.rejects(client.complete(messages), /transport/);
  assert.deepEqual(client.usage(), { state: 'unknown', tokensIn: null, tokensOut: null });
});

test('daily_provider_keeps_known_usage_when_the_post_call_checkpoint_fails', async () => {
  let checkpoints = 0;
  const client = await createDailyLlmProvider({} as Database, {}, {
    resolve: async () => config,
    complete: async () => ({ content: '{}', tokensIn: 12, tokensOut: 7 }),
    onUsage: async () => { checkpoints += 1; if (checkpoints === 2) throw new Error('storage unavailable'); },
  });
  await assert.rejects(client.complete(messages), /llm_outcome_unknown/);
  assert.deepEqual(client.usage(), { state: 'complete', tokensIn: 12, tokensOut: 7 });
});

test('daily_llm_provider_is_unavailable_when_the_exam_provider_is_not_configured', async () => {
  let calls = 0;
  await assert.rejects(createDailyLlmProvider({} as Database, {}, {
    resolve: async () => undefined,
    complete: async (): Promise<ChatReply> => { calls += 1; return { content: '{}' }; },
  }), /daily_llm_unavailable/);
  assert.equal(calls, 0);
});

test('daily LLM reports per-call token checkpoints separately from its cumulative job total', async () => {
  let call = 0;
  const reports: { state: string; tokensIn: number | null; tokensOut: number | null }[] = [];
  const provider = await createDailyLlmProvider({} as Database, {}, {
    resolve: async () => config,
    complete: async () => ({ content: '{}', tokensIn: ++call * 10, tokensOut: call }),
  });
  await provider.complete([], undefined, async usage => { reports.push(usage); });
  await provider.complete([], undefined, async usage => { reports.push(usage); });
  assert.deepEqual(reports, [
    { state: 'unknown', tokensIn: null, tokensOut: null },
    { state: 'complete', tokensIn: 10, tokensOut: 1 },
    { state: 'unknown', tokensIn: null, tokensOut: null },
    { state: 'complete', tokensIn: 20, tokensOut: 2 },
  ]);
  assert.deepEqual(provider.usage(), { state: 'complete', tokensIn: 30, tokensOut: 3 });
});

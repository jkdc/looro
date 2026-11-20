import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chatCompletion, encryptSecret, fingerprintSecret, keyHint, LlmTransportError, maskKey } from "../src/llm-config.js";

test('empty provider content preserves explicitly reported usage on its failure', async () => {
  await assert.rejects(chatCompletion({ provider: 'openai', baseUrl: 'https://llm.example/v1', model: 'test', apiKey: 'fake' },
    [{ role: 'user', content: 'generate' }], async () => new Response(JSON.stringify({
      choices: [{ message: { content: '' } }], usage: { prompt_tokens: 17, completion_tokens: 0 },
    }), { status: 200 })), error => {
    assert.ok(error instanceof LlmTransportError);
    assert.equal(error.code, 'llm_empty_reply');
    assert.deepEqual((error as unknown as { usage: unknown }).usage, { tokensIn: 17, tokensOut: 0 });
    return true;
  });
});

test("chat completion does not reissue after an unknown transport outcome", async () => {
  let calls = 0;
  await assert.rejects(chatCompletion({ provider: "glm", baseUrl: "https://llm.example/v1", model: "test", apiKey: "key" },
    [{ role: "user", content: "generate" }], async () => {
      calls += 1;
      throw new TypeError("connection closed after request write");
    }), error => error instanceof LlmTransportError && error.code === "llm_outcome_unknown");
  assert.equal(calls, 1);
});

test("chat completion forwards cancellation to the provider transport", async () => {
  const controller = new AbortController();
  let receivedSignal: AbortSignal | undefined;
  await assert.rejects(chatCompletion({ provider: "glm", baseUrl: "https://llm.example/v1", model: "test", apiKey: "key" },
    [{ role: "user", content: "generate" }], async (_url, init) => {
      receivedSignal = init?.signal ?? undefined;
      throw new DOMException("aborted", "AbortError");
    }, controller.signal), error => error instanceof LlmTransportError && error.code === "llm_outcome_unknown");
  assert.equal(receivedSignal, controller.signal);
});


test("chat completion does not retry when the response body fails after headers", async () => {
  let calls = 0;
  await assert.rejects(chatCompletion({ provider: "glm", baseUrl: "https://llm.example/v1", model: "test", apiKey: "key" },
    [{ role: "user", content: "generate" }], async () => {
      calls += 1;
      return { ok: true, json: async () => { throw new TypeError("response body terminated"); } } as unknown as Response;
    }), error => error instanceof LlmTransportError && error.code === "llm_outcome_unknown");
  assert.equal(calls, 1);
});

test("key hints mask only secrets the master key can decrypt", () => {
  const env = { LEVELTEST_LLM_MASTER_KEY: "a".repeat(64) };
  const encrypted = encryptSecret("sk-live-1234567890", env)!;
  assert.equal(keyHint(encrypted, env), "sk-l…7890");
  assert.equal(keyHint(encrypted, { LEVELTEST_LLM_MASTER_KEY: "b".repeat(64) }), null);
  assert.equal(keyHint(encrypted, {}), null);
  assert.equal(keyHint("not-a-ciphertext", env), null);
  assert.equal(keyHint("", env), null);
  assert.equal(maskKey("short"), "••••");
  assert.equal(maskKey("sk-live-1234567890"), "sk-l…7890");
});

test("fingerprints are stable, truncated SHA-256 identifiers", () => {
  const expected = createHash("sha256").update("plain-secret").digest("hex").slice(0, 16);
  assert.equal(fingerprintSecret("plain-secret"), expected);
  assert.equal(fingerprintSecret("plain-secret"), fingerprintSecret("plain-secret"));
  assert.notEqual(fingerprintSecret("plain-secret"), fingerprintSecret("plain-secret2"));
  assert.match(fingerprintSecret("plain-secret"), /^[a-f0-9]{16}$/);
});

test('chat completion reports only bounded finish reasons and the usage supplied by the provider', async () => {
  for (const [given, expected] of [['length', 'length'], [undefined, 'not_reported'], ['private provider text', 'unknown']]) {
    const reply = await chatCompletion({ provider: 'openai', baseUrl: 'https://llm.example/v1', model: 'test', apiKey: 'fake' },
      [{ role: 'user', content: 'generate' }], async () => new Response(JSON.stringify({
        choices: [{ finish_reason: given, message: { content: 'fixture' } }], usage: { completion_tokens: 5 },
      })));
    assert.equal(Reflect.get(reply, 'finishReason'), expected);
    assert.equal(reply.tokensIn, undefined);
    assert.equal(reply.tokensOut, 5);
  }
});

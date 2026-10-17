import { test } from "node:test";
import assert from "node:assert/strict";
import { chatCompletion, LlmTransportError } from "../src/llm-config.js";

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

import type { Database } from './db.js';
import { GenerationUsageTracker, type GenerationUsage } from './level-test-generation-usage.js';
import { chatCompletion, LlmTransportError, resolveLlmConfig, type ChatMessage, type ChatReply, type ResolvedLlmConfig } from './llm-config.js';

export type DailyLlmProvider = {
  provider: ResolvedLlmConfig['provider'];
  model: string;
  complete(messages: ChatMessage[], signal?: AbortSignal, onUsage?: (usage: GenerationUsage) => Promise<void>): Promise<ChatReply>;
  usage(): GenerationUsage;
};

type DailyLlmDependencies = {
  resolve: typeof resolveLlmConfig;
  complete(config: ResolvedLlmConfig, messages: ChatMessage[], signal?: AbortSignal): Promise<ChatReply>;
  onUsage?(usage: GenerationUsage): Promise<void>;
};

/** Resolves the same active provider configuration used by exam generation once per daily job. */
export async function createDailyLlmProvider(db: Database, env: NodeJS.ProcessEnv,
  dependencies: DailyLlmDependencies = { resolve: resolveLlmConfig, complete: (config, messages, signal) => chatCompletion(config, messages, fetch, signal) }): Promise<DailyLlmProvider> {
  const config = await dependencies.resolve(db, env);
  if (!config) throw new Error('daily_llm_unavailable');
  const usage = new GenerationUsageTracker();
  return {
    provider: config.provider,
    model: config.model,
    usage: () => usage.snapshot(),
    complete: async (messages, signal, onUsage) => {
      if (signal?.aborted) throw new LlmTransportError('llm_outcome_unknown');
      usage.begin();
      const callUsage = new GenerationUsageTracker();
      callUsage.begin();
      const callCheckpoint = async () => {
        try { await (onUsage ?? dependencies.onUsage)?.(callUsage.snapshot()); }
        catch { throw new LlmTransportError('llm_outcome_unknown'); }
      };
      await callCheckpoint();
      if (signal?.aborted) throw new LlmTransportError('llm_outcome_unknown');
      let reply: ChatReply;
      try {
        reply = await dependencies.complete(config, messages, signal);
      } catch (error) {
        const partial = error instanceof LlmTransportError ? error.usage : undefined;
        usage.report(partial);
        callUsage.report(partial);
        await callCheckpoint();
        throw error;
      }
      usage.report(reply);
      callUsage.report(reply);
      await callCheckpoint();
      if (signal?.aborted) throw new LlmTransportError('llm_outcome_unknown');
      return reply;
    },
  };
}

import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto';
import { Agent } from 'undici';
import type { Database } from './db.js';
import { paliqueMetrics } from './metrics.js';

/** LLM provider configuration for weekly level-test generation
    (level-tests-llm): presets (openai / glm z.ai / opencode go gateway),
    AES-256-GCM-encrypted API keys stored in `llm_provider_configs`,
    resolution DB-active → env, and one OpenAI-compatible chat transport.
    Keys never leave this module in clear: callers receive a resolved config
    whose key is redacted unless they ask for the transport explicitly. */

export type LlmProvider = 'openai' | 'glm' | 'opencode';

export interface LlmProviderPreset { baseUrl: string; models: string[] }

export const LLM_PRESETS: Record<LlmProvider, LlmProviderPreset> = {
  openai: { baseUrl: 'https://api.openai.com/v1', models: ['gpt-5.6-luna', 'gpt-5.6-terra', 'gpt-5.6-sol',
    'gpt-6.1-sol', 'gpt-6-sol', 'gpt-6-luna', 'gpt-6-astra', 'gpt-5.5'] },
  glm: { baseUrl: 'https://api.z.ai/api/paas/v4/', models: ['glm-4.5-flash', 'glm-4.7-flash', 'glm-4.6', 'glm-4.7',
    'glm-5', 'glm-5.1', 'glm-5.2', 'glm-5.3', 'glm-5.3-flash', 'glm-5.3-flashx'] },
  opencode: { baseUrl: 'https://opencode.ai/zen/go/v1', models: ['glm-5.3-flash', 'glm-5.3', 'glm-5.2',
    'kimi-k3', 'kimi-k2.7-code', 'deepseek-v4.1-flash', 'deepseek-v4-pro', 'mimo-v2.6-pro', 'longcat-2.0'] },
};

export const LLM_PROVIDERS: LlmProvider[] = ['openai', 'glm', 'opencode'];

export const isLlmProvider = (value: unknown): value is LlmProvider =>
  typeof value === 'string' && (LLM_PROVIDERS as string[]).includes(value);

export interface ResolvedLlmConfig { provider: LlmProvider; baseUrl: string; model: string; apiKey: string;
  /** Stable session id for gateways that route per conversation (OpenCode Go). */ sessionID?: string }
export interface PublicLlmConfig { provider: LlmProvider; baseUrl: string; model: string; keyHint: string;
  publishMode: 'review' | 'auto'; verifyMode: 'flag' | 'strict' | 'off'; variants: number }
export interface GeneratedExamVariant { variant: number; items: unknown[]; passages: unknown[];
  readingPassageIds: string[]; listeningPassageIds: string[] }

const masterKey = (env: NodeJS.ProcessEnv): Buffer | undefined => {
  const raw = env.LEVELTEST_LLM_MASTER_KEY ?? '';
  if (!/^[a-f0-9]{64}$/.test(raw)) return undefined;
  return Buffer.from(raw, 'hex');
};

export const encryptSecret = (plain: string, env: NodeJS.ProcessEnv): string | undefined => {
  const key = masterKey(env);
  if (!key || !plain) return undefined;
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([iv, cipher.update(plain, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  return encrypted.toString('base64');
};

export const decryptSecret = (stored: string, env: NodeJS.ProcessEnv): string | undefined => {
  const key = masterKey(env);
  if (!key) return undefined;
  try {
    const raw = Buffer.from(stored, 'base64');
    const iv = raw.subarray(0, 12), payload = raw.subarray(12);
    const decipher = createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(payload.subarray(payload.length - 16));
    return decipher.update(payload.subarray(0, payload.length - 16)).toString('utf8') +
      decipher.final('utf8');
  } catch { return undefined; }
};

export const maskKey = (key: string): string =>
  key.length <= 8 ? '••••' : `${key.slice(0, 4)}…${key.slice(-4)}`;

interface ProviderConfigRow { provider: string; base_url: string; model: string;
  api_key_encrypted: string }

/** Active DB config (decrypted) when the master key can read it, else env,
    else undefined. DB configs that cannot be decrypted are skipped, never
    falling back to plaintext. */
export async function resolveLlmConfig(db: Database, env: NodeJS.ProcessEnv): Promise<ResolvedLlmConfig | undefined> {
  try {
    const rows = await db.query(
      `SELECT provider, base_url, model, api_key_encrypted FROM llm_provider_configs
       WHERE is_active ORDER BY updated_at DESC LIMIT 1`) as { rows: ProviderConfigRow[] };
    for (const row of rows.rows ?? []) {
      if (!isLlmProvider(row.provider)) continue;
      const key = decryptSecret(row.api_key_encrypted, env);
      if (key && row.base_url && row.model)
        return { provider: row.provider, baseUrl: row.base_url, model: row.model, apiKey: key };
    }
  } catch { /* table missing or DB unavailable: env path decides. */ }
  const provider = env.LEVELTEST_LLM_PROVIDER;
  if (isLlmProvider(provider) && env.LEVELTEST_LLM_API_KEY) {
    const preset = LLM_PRESETS[provider];
    return { provider, baseUrl: env.LEVELTEST_LLM_URL || preset.baseUrl,
      model: env.LEVELTEST_LLM_MODEL || preset.models[0] || '', apiKey: env.LEVELTEST_LLM_API_KEY };
  }
  return undefined;
}

export interface ChatMessage { role: 'system' | 'user'; content: string }
export interface ChatReply { content: string; tokensIn?: number; tokensOut?: number }

/** One OpenAI-compatible chat completion. Any transport error after invocation
    has an unknown provider-side outcome and must not be retried automatically. */
// Long generations with reasoning models can take >5 min to first byte.
const llmAgent = new Agent({ headersTimeout: 900_000, bodyTimeout: 900_000 });

export async function chatCompletion(config: ResolvedLlmConfig, messages: ChatMessage[],
                                     fetchFn: typeof fetch = fetch, signal?: AbortSignal): Promise<ChatReply> {
  const started = performance.now();
  try {
    const url = `${config.baseUrl.replace(/\/$/, '')}/chat/completions`;
    let response: Response;
    try {
      response = await fetchFn(url, {
        method: 'POST',
        signal,
        headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}`,
          // OpenCode Go routes per conversation and rejects session-less requests.
          ...(config.provider === 'opencode' ? { 'x-opencode-session': config.sessionID ?? randomUUID(),
            'user-agent': 'palique-api/1.0' } : {}) },
        body: JSON.stringify({ model: config.model, messages, temperature: 0.4, stream: false }),
        dispatcher: llmAgent,
      } as RequestInit & { dispatcher: Agent });
    } catch {
      throw new LlmTransportError('llm_outcome_unknown');
    }
    if (!response.ok) throw new LlmTransportError(`llm_http_${response.status}`);
    let payload: { choices?: { message?: { content?: string } }[]; usage?: { prompt_tokens?: number; completion_tokens?: number } };
    try {
      payload = await response.json() as typeof payload;
    } catch {
      throw new LlmTransportError('llm_outcome_unknown');
    }
    const content = payload.choices?.[0]?.message?.content;
    const reportedUsage = {
      ...(Number.isSafeInteger(payload.usage?.prompt_tokens) && payload.usage!.prompt_tokens! >= 0
        ? { tokensIn: payload.usage!.prompt_tokens } : {}),
      ...(Number.isSafeInteger(payload.usage?.completion_tokens) && payload.usage!.completion_tokens! >= 0
        ? { tokensOut: payload.usage!.completion_tokens } : {}),
    };
    if (typeof content !== 'string' || !content.trim()) throw new LlmTransportError('llm_empty_reply', reportedUsage);
    const outcome: ChatReply = { content,
      ...reportedUsage };
    paliqueMetrics.llmRequests.add(1, { provider: config.provider, status: 'ok' });
    if (typeof payload.usage?.prompt_tokens === 'number' && Number.isFinite(payload.usage.prompt_tokens))
      paliqueMetrics.llmTokens.add(payload.usage.prompt_tokens, { provider: config.provider, direction: 'input' });
    if (typeof payload.usage?.completion_tokens === 'number' && Number.isFinite(payload.usage.completion_tokens))
      paliqueMetrics.llmTokens.add(payload.usage.completion_tokens, { provider: config.provider, direction: 'output' });
    return outcome;
  } catch (error) {
    paliqueMetrics.llmRequests.add(1, { provider: config.provider, status: 'failed',
      error_code: error instanceof LlmTransportError ? error.code : 'llm_unknown_error' });
    throw error; // unchanged: the no-retry contract relies on the original LlmTransportError
  } finally {
    paliqueMetrics.llmRequestDuration.record((performance.now() - started) / 1000, { provider: config.provider });
  }
}

export class LlmTransportError extends Error {
  constructor(public code: string, public usage?: Pick<ChatReply, 'tokensIn' | 'tokensOut'>) { super(code); }
}

/** Extracts the first JSON object from a reply that may carry prose or code fences. */
export function extractJson(content: string): unknown {
  const fenced = content.match(/```(?:json)?\s*([\s\S]*?)```/);
  const candidate = (fenced?.[1] ?? content).trim();
  // Find the outermost JSON value: object {...} or array [...].
  const objStart = candidate.indexOf('{');
  const arrStart = candidate.indexOf('[');
  const start = arrStart >= 0 && (objStart < 0 || arrStart < objStart) ? arrStart : objStart;
  const openChar = start >= 0 ? candidate[start] : '';
  const closeChar = openChar === '[' ? ']' : '}';
  const end = candidate.lastIndexOf(closeChar);
  if (start < 0 || end <= start) throw new LlmTransportError('llm_json_missing');
  return JSON.parse(candidate.slice(start, end + 1));
}

export function keyHint(encrypted: string, env: NodeJS.ProcessEnv): string | null {
  const key = decryptSecret(encrypted, env);
  return key ? maskKey(key) : null;
}

export function fingerprintSecret(plain: string): string {
  return createHash('sha256').update(plain).digest('hex').slice(0, 16);
}



import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { decryptSecret, encryptSecret, isLlmProvider, keyHint, maskKey, resolveLlmConfig,
  LLM_PRESETS, type LlmProvider } from './llm-config.js';

/** Admin API shapes for the LLM provider configuration (level-tests-llm).
    The stored API key is never returned: responses carry a masked hint only,
    and every mutation is audited by the caller via `admin_actions`. */

export interface PublicLlmConfigView {
  provider: LlmProvider; baseUrl: string; model: string; keyHint: string | null;
  publishMode: 'review' | 'auto'; verifyMode: 'flag' | 'strict' | 'off'; variants: number;
}
export interface LlmConfigUpdate {
  provider?: unknown; baseUrl?: unknown; model?: unknown; apiKey?: unknown; label?: unknown;
  publishMode?: unknown; verifyMode?: unknown; variants?: unknown;
}

export const LLM_PROVIDER_CATALOG = Object.entries(LLM_PRESETS).map(([provider, preset]) => ({
  provider, baseUrl: preset.baseUrl, models: preset.models }));

export async function readLlmConfigView(db: Database, env: NodeJS.ProcessEnv): Promise<PublicLlmConfigView> {
  const defaults = defaultSettings(env);
  const view: PublicLlmConfigView = {
    provider: env.LEVELTEST_LLM_PROVIDER && isLlmProvider(env.LEVELTEST_LLM_PROVIDER)
      ? env.LEVELTEST_LLM_PROVIDER : 'openai',
    baseUrl: env.LEVELTEST_LLM_URL || LLM_PRESETS.openai.baseUrl,
    model: env.LEVELTEST_LLM_MODEL || LLM_PRESETS.openai.models[0] || 'gpt-5.6-luna',
    keyHint: env.LEVELTEST_LLM_API_KEY ? maskKey(env.LEVELTEST_LLM_API_KEY) : null,
    publishMode: defaults.publishMode, verifyMode: defaults.verifyMode, variants: defaults.variants,
  };
  try {
    const rows = await db.query(`SELECT provider, base_url, model, api_key_encrypted FROM llm_provider_configs
      WHERE is_active ORDER BY updated_at DESC LIMIT 1`);
    const row = rows.rows[0] as { provider: LlmProvider; base_url: string; model: string;
      api_key_encrypted: string } | undefined;
    if (!row) return view;
    const key = decryptSecret(row.api_key_encrypted, env);
    return { ...view, provider: row.provider, baseUrl: row.base_url, model: row.model,
      keyHint: key ? maskKey(key) : view.keyHint };
  } catch { return view; }
}

export interface UpsertResult { updated: boolean }

/** Upserts the active provider config and/or the settings. Fails closed with
    `llm_master_key_missing` when a key is submitted but the env master key is
    absent or malformed. */
export async function upsertLlmConfig(db: Database, env: NodeJS.ProcessEnv,
                                      update: LlmConfigUpdate): Promise<UpsertResult> {
  const settings = defaultSettings(env);
  let touched = false;
  if (update.provider !== undefined || update.baseUrl !== undefined || update.model !== undefined ||
      update.apiKey !== undefined) {
    const provider = typeof update.provider === 'string' && isLlmProvider(update.provider)
      ? update.provider : undefined;
    if (!provider) throw new ConfigError('invalid_llm_provider');
    const preset = LLM_PRESETS[provider];
    const baseUrl = typeof update.baseUrl === 'string' && update.baseUrl.trim()
      ? update.baseUrl.trim() : preset.baseUrl;
    const model = typeof update.model === 'string' && update.model.trim()
      ? update.model.trim() : preset.models[0] || '';
    const apiKey = typeof update.apiKey === 'string' && update.apiKey.trim() ? update.apiKey.trim() : null;
    if (!baseUrl || !model) throw new ConfigError('invalid_llm_config');
    await transaction(db, async sql => {
      const encrypted = apiKey ? encryptSecret(apiKey, env)
        : await storedKey(sql, provider);
      if (!encrypted) throw new ConfigError('llm_master_key_missing', 503);
      await sql.query('UPDATE llm_provider_configs SET is_active=false WHERE is_active');
      await sql.query(`INSERT INTO llm_provider_configs(id, provider, label, base_url, model, api_key_encrypted, is_active)
        VALUES(gen_random_uuid(), $1, $2, $3, $4, $5, true)
        ON CONFLICT (provider, base_url, model) DO UPDATE SET label=$2, api_key_encrypted=$5,
          is_active=true, updated_at=now()`, [provider, labelFor(update.label, provider), baseUrl, model, encrypted]);
    });
    touched = true;
  }
  if (update.publishMode !== undefined || update.verifyMode !== undefined || update.variants !== undefined) {
    const publish = update.publishMode === 'auto' ? 'auto' : update.publishMode === 'review' ? 'review' : settings.publishMode;
    const verify = update.verifyMode === 'strict' || update.verifyMode === 'off'
      ? update.verifyMode as 'strict' | 'off' : settings.verifyMode;
    const variants = typeof update.variants === 'number' && Number.isSafeInteger(update.variants) &&
      update.variants >= 1 && update.variants <= 5 ? update.variants : settings.variants;
    await transaction(db, async sql => {
      await sql.query(`INSERT INTO level_test_settings(singleton, publish_mode, verify_mode, variants)
        VALUES(true, $1, $2, $3)
        ON CONFLICT (singleton) DO UPDATE SET publish_mode=$1, verify_mode=$2, variants=$3`,
        [publish, verify, variants]);
    });
    touched = true;
  }
  if (!touched) throw new ConfigError('invalid_llm_config');
  return { updated: true };
}

export async function storedConfigForTest(db: Database, env: NodeJS.ProcessEnv) {
  const config = await resolveLlmConfig(db, env);
  if (!config) throw new ConfigError('llm_config_missing', 404);
  return config;
}

export class ConfigError extends Error {
  constructor(public code: string, public status: number = 400) { super(code); }
}

// ---- internals ----

function labelFor(label: unknown, provider: LlmProvider): string {
  if (typeof label === 'string' && label.trim()) return label.trim().slice(0, 100);
  const preset = LLM_PRESETS[provider];
  return `${provider} · ${preset.models[0] ?? 'custom'}`;
}

function defaultSettings(env: NodeJS.ProcessEnv) {
  return {
    publishMode: env.LEVELTEST_PUBLISH_MODE === 'auto' ? 'auto' as const : 'review' as const,
    verifyMode: env.LEVELTEST_VERIFY === 'strict' ? 'strict' as const :
      env.LEVELTEST_VERIFY === 'off' ? 'off' as const : 'flag' as const,
    variants: clampVariants(Number(env.LEVELTEST_VARIANTS ?? 3)),
  };
}

function clampVariants(value: number): number {
  return Number.isSafeInteger(value) && value >= 1 && value <= 5 ? value : 3;
}

async function storedKey(sql: PoolClient, provider: LlmProvider): Promise<string | undefined> {
  const rows = await sql.query(`SELECT api_key_encrypted FROM llm_provider_configs
    WHERE is_active AND provider=$1 LIMIT 1`, [provider]);
  return (rows.rows[0] as { api_key_encrypted?: string } | undefined)?.api_key_encrypted;
}

import { transaction, type Database } from './db.js';
import { encryptSecret, isLlmProvider, maskKey, resolveLlmConfig, LLM_PRESETS, type LlmProvider } from './llm-config.js';
import { levelTestSettings, type LevelTestSettings } from './level-test-settings.js';

export interface PublicLlmConfigView extends LevelTestSettings {
  provider: LlmProvider; baseUrl: string; model: string; keyHint: string | null;
}
export interface LlmConfigUpdate {
  provider?: unknown; baseUrl?: unknown; model?: unknown; apiKey?: unknown; label?: unknown;
  publishMode?: unknown; verifyMode?: unknown; variants?: unknown;
}
export const LLM_PROVIDER_CATALOG = Object.entries(LLM_PRESETS).map(([provider, preset]) => ({
  provider, baseUrl: preset.baseUrl, models: preset.models }));

/** Show the same effective provider/settings that generation resolves; never return a secret. */
export async function readLlmConfigView(db: Database, env: NodeJS.ProcessEnv): Promise<PublicLlmConfigView> {
  const settings = await levelTestSettings(db, env), config = await resolveLlmConfig(db, env);
  const provider = config?.provider ?? (isLlmProvider(env.LEVELTEST_LLM_PROVIDER) ? env.LEVELTEST_LLM_PROVIDER : 'openai');
  return { ...settings, provider, baseUrl: config?.baseUrl ?? (env.LEVELTEST_LLM_URL || LLM_PRESETS[provider].baseUrl),
    model: config?.model ?? (env.LEVELTEST_LLM_MODEL || LLM_PRESETS[provider].models[0] || ''),
    keyHint: config ? maskKey(config.apiKey) : null };
}
export interface UpsertResult { updated: boolean }

/** Serialize partial configuration updates and commit provider/settings together. */
export async function upsertLlmConfig(db: Database, env: NodeJS.ProcessEnv, update: LlmConfigUpdate): Promise<UpsertResult> {
  const providerTouched = ['provider', 'baseUrl', 'model', 'apiKey', 'label'].some(key => update[key as keyof LlmConfigUpdate] !== undefined);
  const settingsTouched = ['publishMode', 'verifyMode', 'variants'].some(key => update[key as keyof LlmConfigUpdate] !== undefined);
  if (!providerTouched && !settingsTouched) throw new ConfigError('invalid_llm_config');
  if (update.publishMode !== undefined && !['auto', 'review'].includes(update.publishMode as string) ||
      update.verifyMode !== undefined && !['strict', 'flag', 'off'].includes(update.verifyMode as string) ||
      update.variants !== undefined && !(typeof update.variants === 'number' && Number.isSafeInteger(update.variants) && update.variants >= 1 && update.variants <= 5))
    throw new ConfigError('invalid_llm_config');
  await transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('level-test-config'))");
    if (providerTouched) {
      const current = (await sql.query(`SELECT provider, label, base_url, model, api_key_encrypted FROM llm_provider_configs
        WHERE is_active ORDER BY updated_at DESC LIMIT 1 FOR UPDATE`)).rows[0] as
        { provider: LlmProvider; label: string; base_url: string; model: string; api_key_encrypted: string } | undefined;
      const effectiveProvider = current?.provider ??
        (isLlmProvider(env.LEVELTEST_LLM_PROVIDER) ? env.LEVELTEST_LLM_PROVIDER : 'openai');
      const provider = update.provider === undefined ? effectiveProvider : update.provider;
      if (!isLlmProvider(provider)) throw new ConfigError('invalid_llm_provider');
      const switched = effectiveProvider !== provider;
      // Switching providers must not forward an old credential or endpoint implicitly.
      if (switched && (update.apiKey === undefined || update.baseUrl === undefined || update.model === undefined))
        throw new ConfigError('invalid_llm_config');
      const baseUrl = field(update.baseUrl, current?.base_url ?? (env.LEVELTEST_LLM_URL || LLM_PRESETS[provider].baseUrl));
      const model = field(update.model, current?.model ?? (env.LEVELTEST_LLM_MODEL || LLM_PRESETS[provider].models[0] || ''));
      const apiKey = update.apiKey === undefined ? undefined : field(update.apiKey, '');
      const encrypted = apiKey ? encryptSecret(apiKey, env) : current?.api_key_encrypted ??
        (env.LEVELTEST_LLM_API_KEY ? encryptSecret(env.LEVELTEST_LLM_API_KEY, env) : undefined);
      if (!encrypted) throw new ConfigError('llm_master_key_missing', 503);
      const label = update.label === undefined ? current?.label ?? `${provider} · ${model}` : field(update.label, '').slice(0, 100);
      await sql.query('UPDATE llm_provider_configs SET is_active=false WHERE is_active');
      await sql.query(`INSERT INTO llm_provider_configs(id, provider, label, base_url, model, api_key_encrypted, is_active)
        VALUES(gen_random_uuid(), $1, $2, $3, $4, $5, true)
        ON CONFLICT (provider, base_url, model) DO UPDATE SET label=$2, api_key_encrypted=$5,
          is_active=true, updated_at=now()`, [provider, label, baseUrl, model, encrypted]);
    }
    if (settingsTouched) {
      const current = await levelTestSettings(sql, env);
      await sql.query(`INSERT INTO level_test_settings(singleton, publish_mode, verify_mode, variants)
        VALUES(true, $1, $2, $3)
        ON CONFLICT (singleton) DO UPDATE SET publish_mode=$1, verify_mode=$2, variants=$3`,
      [update.publishMode ?? current.publishMode, update.verifyMode ?? current.verifyMode, update.variants ?? current.variants]);
    }
  });
  return { updated: true };
}
function field(value: unknown, fallback: string): string {
  if (value === undefined && fallback) return fallback;
  if (typeof value === 'string' && value.trim()) return value.trim();
  throw new ConfigError('invalid_llm_config');
}
export async function storedConfigForTest(db: Database, env: NodeJS.ProcessEnv) {
  const config = await resolveLlmConfig(db, env);
  if (!config) throw new ConfigError('llm_config_missing', 404);
  return config;
}
export class ConfigError extends Error {
  constructor(public code: string, public status: number = 400) { super(code); }
}

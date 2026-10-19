import type { PoolClient } from 'pg';
import type { Database } from './db.js';

export interface LevelTestSettings {
  publishMode: 'review' | 'auto'; verifyMode: 'flag' | 'strict' | 'off'; variants: number;
}
export function defaultLevelTestSettings(env: NodeJS.ProcessEnv): LevelTestSettings {
  const variants = Number(env.LEVELTEST_VARIANTS ?? 3);
  return { publishMode: env.LEVELTEST_PUBLISH_MODE === 'auto' ? 'auto' : 'review',
    verifyMode: env.LEVELTEST_VERIFY === 'strict' ? 'strict' : env.LEVELTEST_VERIFY === 'off' ? 'off' : 'flag',
    variants: Number.isSafeInteger(variants) && variants >= 1 && variants <= 5 ? variants : 3 };
}
/** Shared DB → env resolution for generation, admin reads and partial settings writes. */
export async function levelTestSettings(db: Database | PoolClient, env: NodeJS.ProcessEnv): Promise<LevelTestSettings> {
  const defaults = defaultLevelTestSettings(env);
  try {
    const row = (await db.query('SELECT publish_mode, verify_mode, variants FROM level_test_settings WHERE singleton')).rows[0];
    return row ? { publishMode: row.publish_mode, verifyMode: row.verify_mode, variants: row.variants } : defaults;
  } catch { return defaults; }
}

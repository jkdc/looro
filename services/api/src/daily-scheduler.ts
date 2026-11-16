import type { Database } from './db.js';
import { ensureDailyPackage } from './daily-jobs.js';

export interface DailyScheduler { start(): void; stop(): void }
export function createDailyScheduler(db: Database, now: () => Date = () => new Date()): DailyScheduler {
  let timer: ReturnType<typeof setInterval>|undefined;
  let running = false;
  const tick = async () => {
    if (running) return;
    running = true;
    try {
      const config = (await db.query<{generation_enabled:boolean;enabled_languages:string[]}>(
        'SELECT generation_enabled,enabled_languages FROM daily_config WHERE singleton=true')).rows[0];
      if (!config?.generation_enabled) return;
      const today = now();
      if (!Number.isFinite(today.getTime())) return;
      const dates = [0,1].map(offset => { const date=new Date(today); date.setUTCDate(date.getUTCDate()+offset); return date.toISOString().slice(0,10); });
      for (const language of config.enabled_languages) for (const date of dates) await ensureDailyPackage(db,language,date);
    } finally { running = false; }
  };
  return { start() { if (timer) return; void tick().catch(() => undefined); timer=setInterval(() => { void tick().catch(() => undefined); },60_000); timer.unref(); },
    stop() { if (timer) clearInterval(timer); timer=undefined; } };
}

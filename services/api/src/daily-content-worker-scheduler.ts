import { randomUUID } from 'node:crypto';
import type { Database } from './db.js';
import { log } from './log.js';
import { runDailyContentWorkerOnce, type DailyContentWorkerResult } from './daily-workers.js';

export interface DailyContentWorkerScheduler { start():void; stop():Promise<void> }
export function createDailyContentWorkerScheduler(db:Database,env:NodeJS.ProcessEnv,runOnce=runDailyContentWorkerOnce):DailyContentWorkerScheduler {
  const workerId=`daily-${randomUUID()}`;
  let timer:ReturnType<typeof setInterval>|undefined;
  let inFlight:Promise<DailyContentWorkerResult|null>|undefined;
  let stopping=false;
  const tick=()=>{
    if(stopping||inFlight)return;
    inFlight=runOnce(db,env,workerId).catch(()=>{log.error('daily_content_worker_tick_failed');return null;})
      .finally(()=>{inFlight=undefined;});
  };
  return {
    start(){if(timer)return;stopping=false;tick();timer=setInterval(tick,15_000);timer.unref();},
    async stop(){stopping=true;if(timer)clearInterval(timer);timer=undefined;await inFlight;}
  };
}

import { randomUUID } from 'node:crypto';
import type { Database } from './db.js';
import { log } from './log.js';
import { runDailyReviewWorkerOnce, type DailyReviewWorkerResult } from './daily-workers.js';

export interface DailyReviewWorkerScheduler { start():void; stop():Promise<void> }
export function createDailyReviewWorkerScheduler(db:Database,env:NodeJS.ProcessEnv,runOnce=runDailyReviewWorkerOnce):DailyReviewWorkerScheduler {
  const workerId=`daily-review-${randomUUID()}`;
  let timer:ReturnType<typeof setInterval>|undefined;
  let inFlight:Promise<DailyReviewWorkerResult|null>|undefined;
  let stopping=false;
  const tick=()=>{
    if(stopping||inFlight)return;
    inFlight=runOnce(db,env,workerId).catch(()=>{log.error('daily_review_worker_tick_failed');return null;})
      .finally(()=>{inFlight=undefined;});
  };
  return {
    start(){if(timer)return;stopping=false;tick();timer=setInterval(tick,5_000);timer.unref();},
    async stop(){stopping=true;if(timer)clearInterval(timer);timer=undefined;await inFlight;}
  };
}

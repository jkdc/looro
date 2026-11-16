import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createDailyReviewWorkerScheduler } from '../src/daily-review-worker-scheduler.js';
import type { Database } from '../src/db.js';

test('daily review scheduler starts one immediate poll and waits for shutdown',async()=>{
  let calls=0,release!:(value:null)=>void;
  const pending=new Promise<null>(resolve=>{release=resolve;});
  const scheduler=createDailyReviewWorkerScheduler({} as Database,{},async()=>{calls++;return pending;});
  scheduler.start();scheduler.start();assert.equal(calls,1);
  let stopped=false;const stopping=scheduler.stop().then(()=>{stopped=true;});
  await Promise.resolve();assert.equal(stopped,false);release(null);await stopping;assert.equal(stopped,true);
});

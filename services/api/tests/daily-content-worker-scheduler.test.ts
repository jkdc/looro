import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createDailyContentWorkerScheduler } from '../src/daily-content-worker-scheduler.js';
import type { Database } from '../src/db.js';

test('daily content worker scheduler starts one immediate poll and waits for it on stop', async () => {
  let calls=0,release!:()=>void;
  const pending=new Promise<null>(resolve=>{release=()=>resolve(null);});
  const scheduler=createDailyContentWorkerScheduler({} as Database,{},async()=>{calls++;return pending;});
  scheduler.start();scheduler.start();
  assert.equal(calls,1);
  let stopped=false;
  const stopping=scheduler.stop().then(()=>{stopped=true;});
  await Promise.resolve();
  assert.equal(stopped,false);
  release();await stopping;
  assert.equal(stopped,true);
});

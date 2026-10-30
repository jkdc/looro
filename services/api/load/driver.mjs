import {randomBytes} from 'node:crypto';
export const createFixtureToken=()=>randomBytes(32).toString('base64url');
/** Pure workload selection and bounded arrival scheduling. Importing starts no traffic. */
export function positiveInteger(value, fallback, max, name) {
  const n = value === undefined ? fallback : Number(value);
  if (value === '' || !Number.isSafeInteger(n) || n < 1 || n > max) throw new Error(`Invalid ${name}`);
  return n;
}
export function readTask(index, tokens = []) {
  if (tokens.length) return { method: 'GET', path: '/v1/minutes', token: tokens[index % tokens.length] };
  return { method: 'GET', path: ['/v1/pricing', '/v1/minutes/products?provider=stripe', '/healthz'][index % 3] };
}
export function clientHeaders(index, networks, proxyToken) {
  if (!proxyToken) return {};
  const n = index % networks;
  return { 'x-app-proxy-token': proxyToken, 'x-app-client-ip': `10.${Math.floor(n / 65536)}.${Math.floor(n / 256) % 256}.${n % 256}` };
}
const percentiles = samples => {
  const sorted = samples.map(s => s.milliseconds).sort((a,b) => a-b);
  const p = n => sorted.length ? sorted[Math.min(sorted.length-1, Math.ceil(sorted.length*n)-1)] : null;
  return { p50:p(.5), p95:p(.95), p99:p(.99), max:sorted.at(-1) ?? null };
};
export function report(samples, timing) {
  const useful = samples.filter(s => s.useful);
  const statuses = {};
  for (const s of samples) statuses[s.status] = (statuses[s.status] ?? 0)+1;
  return { ...timing, completed:samples.length, dropped:timing.offered-timing.launched,
    drained:samples.length-timing.completedInWindow, useful:useful.length,
    statuses, requestBodyBytes:samples.reduce((sum,s)=>sum+(s.requestBodyBytes??0),0),responseBodyBytes:samples.reduce((sum,s)=>sum+(s.responseBodyBytes??0),0), transportFailures:samples.filter(s => !s.status).length,
    offeredRps:timing.offered/timing.offeringSeconds,
    usefulRps:timing.elapsedSeconds ? useful.length/timing.elapsedSeconds : 0,
    latency:{ all:percentiles(samples), success:percentiles(useful) } };
}
export async function runPhase({name,durationS,targetRps,maxInFlight=100,tasks}, dependencies) {
  const {now,sleep,sample} = dependencies;
  const start=now(), end=start+durationS*1000, interval=1000/targetRps;
  const offered=Math.ceil(durationS*targetRps), pending=new Set(), samples=[];
  let slot=0, launched=0, completedInWindow=0;
  while (slot < offered && now()<end) {
    const due=start+slot*interval;
    if (now()<due) { await sleep(Math.min(due-now(),end-now())); continue; }
    // Skip missed arrival slots instead of replaying a backlog in a tight loop.
    const current=Math.min(offered-1,Math.floor((now()-start)/interval));
    slot=Math.max(slot,current);
    if (pending.size<maxInFlight) {
      const index=launched++;
      const began=now();
      const p=Promise.resolve().then(()=>sample(tasks(index),index)).catch(()=>({status:0,useful:false,milliseconds:now()-began}))
        .then(s=>{samples.push(s);if(now()<end) completedInWindow++;}).finally(()=>pending.delete(p));
      pending.add(p);
    }
    slot++;
    // Allow completed transport work to release capacity before the next arrival.
    await Promise.resolve();
  }
  if(now()<end) await sleep(end-now());
  await Promise.allSettled([...pending]);
  return report(samples,{name,offered,launched,completedInWindow,offeringSeconds:durationS,elapsedSeconds:(now()-start)/1000});
}

/** Minimal, route-specific useful-result checks, deliberately excluding policy rejections. */
export function usefulPayload(path, payload) {
  path=new URL(path,'http://localhost').pathname;
  if (!payload || typeof payload!=='object') return false;
  if(path==='/healthz') return payload.ok===true;
  if(path==='/v1/pricing') return payload.currency==='USD' && payload.moneyUnit==='nanoUSD';
  if(path==='/v1/minutes/products') return Array.isArray(payload.products);
  if(path==='/v1/minutes') return typeof payload.availableMilliseconds==='number';
  if(path==='/v1/auth/challenge') return typeof payload.challengeID==='string';
  if(path==='/v1/proxy/responses') return Array.isArray(payload.choices) && typeof payload.metering?.nanoUSDSettled==='string';
  return false;
}
export function usefulResult(task,status,payload) {
  const path=new URL(task.path,'http://localhost').pathname;
  if(!payload||typeof payload!=='object')return false;
  if(path==='/v1/progress'){
    if(task.method==='POST')return status===202 && Array.isArray(task.body?.entries) && Number.isSafeInteger(payload.merged) && payload.merged===task.body.entries.length;
    return task.method==='GET' && status===200 && Array.isArray(payload.progress) && payload.progress.every(row=>typeof row.itemID==='string'&&typeof row.language==='string'&&Number.isSafeInteger(row.attempts));
  }
  if(['/v1/level-tests/current','/v1/level-tests/hybrid'].includes(path))return task.method==='GET' && status===200 && payload.languageID==='en' && payload.level==='A1' && Number.isSafeInteger(payload.weekId) && Array.isArray(payload.items) && payload.items.length>0 && payload.items.every(item=>typeof item.id==='string'&&typeof item.section==='string');
  return status===200 && usefulPayload(task.path,payload);
}
export async function sampleRequest(task,index,{base,timeoutMs=5000,networks=200,proxyToken='',fetch:fetchFn=fetch}) {
  const start=performance.now(), controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(new Error('deadline')),timeoutMs);
  try {
    const res=await fetchFn(new URL(task.path,base),{method:task.method,redirect:'manual',signal:controller.signal,
      headers:{...clientHeaders(index,networks,proxyToken),...(task.token?{authorization:`Bearer ${task.token}`} : {}),
        ...(task.body?{'content-type':'application/json'}:{})},...(task.body?{body:JSON.stringify(task.body)}:{})});
    // Bound the generator's own memory as well as request lifetime.
    const reader=res.body?.getReader();let bytes=0;const chunks=[];
    try {
      if(reader) while(true) {const {done,value}=await reader.read();if(done)break;bytes+=value.length;if(bytes>2*1024*1024) throw Error('response_too_large');chunks.push(value);}
    } finally {await reader?.cancel().catch(()=>{});}
    let payload;try {payload=JSON.parse(Buffer.concat(chunks).toString('utf8'));} catch {}
    return {status:res.status,useful:usefulResult(task,res.status,payload),requestBodyBytes:task.body?Buffer.byteLength(JSON.stringify(task.body)):0,responseBodyBytes:bytes,milliseconds:performance.now()-start,outcome:res.status>=400?'http_error':'response'};
  } catch {return {status:0,useful:false,milliseconds:performance.now()-start,outcome:controller.signal.aborted?'timeout':'transport_error'};}
  finally {clearTimeout(timer);}
}
export function validateTarget(env) {
  if(env.LOAD_ISOLATED!=='true' || !env.BASE) throw Error('Explicit isolated target required');
  const u=new URL(env.BASE);
  const allowed=u.protocol==='http:' && ['localhost','127.0.0.1','[::1]','api-load'].includes(u.hostname) || u.protocol==='https:' && u.hostname==='caddy-load';
  if(!allowed || u.username || u.password || u.pathname!=='/' || u.search || u.hash) throw Error('Unsafe target');
  return u;
}
export async function preflight(env,fixture,fetchFn=fetch) {
  const base=validateTarget(env);
  if(!/^[a-f0-9-]{36}$/.test(fixture?.runId) || !fixture.tokens?.length || !fixture.proxyToken) throw Error('Invalid fixture manifest');
  // No credential is sent to an address taken from the manifest.
  const identity=await fetchFn('http://stub-load:8081/fixture/identity',{redirect:'error',signal:AbortSignal.timeout(5000)});
  if(!identity.ok || (await identity.json()).runId!==fixture.runId) throw Error('Fixture identity mismatch');
  const headers={authorization:`Bearer ${fixture.tokens[0]}`,...clientHeaders(0,1,fixture.proxyToken)};
  const response=await fetchFn(new URL('/v1/minutes',base),{headers,redirect:'error',signal:AbortSignal.timeout(5000)});
  if(!response.ok || !usefulPayload('/v1/minutes',await response.json())) throw Error('Invalid authenticated fixture');
  const probe=await fetchFn(new URL('/v1/proxy/responses',base),{method:'POST',headers:{...headers,'content-type':'application/json'},
    body:JSON.stringify({model:'stub',stream:false,messages:[{role:'user',content:'fixture-preflight'}]}),redirect:'error',signal:AbortSignal.timeout(35000)});
  const payload=await probe.json();
  if(!probe.ok || payload.choices?.[0]?.message?.content!==fixture.runId || !usefulPayload('/v1/proxy/responses',payload)) throw Error('Proxy is not the fixture provider');
  return fixture;
}

export const writeTask=()=>({method:'POST',path:'/v1/auth/challenge',body:{}});
export function loadScenario(value='fast') {
  if(!['fast','slow','hang'].includes(value))throw Error('Invalid load scenario');return value;
}
export const driverRunArguments=()=>['run','--rm','--no-deps','driver-load'];

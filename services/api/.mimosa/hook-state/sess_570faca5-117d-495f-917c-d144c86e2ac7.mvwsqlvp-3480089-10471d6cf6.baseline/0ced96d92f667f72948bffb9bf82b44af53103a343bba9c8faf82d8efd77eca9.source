import { randomUUID } from 'node:crypto';
import type { Database } from './db.js';
import { prepareProxyAttempt, markProxyDispatched, finishProxyAttempt, settleProxyAttempt } from './proxy-attempts.js';
import { readBoundedJSON, ProxyResponseError } from './proxy-transport.js';
import { paliqueMetrics } from './metrics.js';
import { log } from './log.js';
import { ServiceError } from './errors.js';
import { assertOutboundUrl } from './outbound-url.js';

/** Token billing proxy: relays a non-streaming OpenAI-compatible completion
    call (v1 contract; `stream: true` is rejected) to the configured provider
    using the server key, meters provider-reported usage against the caller's
    nanoUSD wallet at the active rate version, refuses accounts without the
    BYOK unlock, and caps concurrent relays per account
    (`PROXY_MAX_CONCURRENT`, default 2) so one account cannot monopolise the
    server key. Grace is bounded: a single call whose real cost exceeds the
    balance settles at the real cost (floor zero) so the reply is never
    wasted, but a zero balance refuses the next call. A success reply whose
    usage cannot be metered is refused rather than served free, and every
    successful relay leaves a durable `proxy_usage` row with the token counts
    and what they cost. */
export interface TokenProxyConfig { providerURL: string; providerKey: string; maxTokens: number; maxConcurrentPerAccount: number; maxInFlight?:number; timeoutMs?:number; maxResponseBytes?:number }

const boundedInteger=(value:string|undefined,fallback:number,max:number,name:string,min=1):number=>{
  const n=value===undefined?fallback:Number(value);
  if(value==='' || !Number.isSafeInteger(n) || n<min || n>max)throw new Error(`${name} must be a positive integer within its supported range.`);
  return n;
};

export const tokenProxyConfig = (env: NodeJS.ProcessEnv): TokenProxyConfig | undefined => {
  const providerURL = env.PROXY_PROVIDER_URL, providerKey = env.PROXY_PROVIDER_KEY;
  if (!providerURL || !providerKey) return undefined;
  // The Authorization header carries the server key: plain http is only
  // acceptable for loopback lab transports.
  assertOutboundUrl(providerURL, 'PROXY_PROVIDER_URL');
  const maxConcurrentPerAccount = Number(env.PROXY_MAX_CONCURRENT ?? 2);
  if (!Number.isSafeInteger(maxConcurrentPerAccount) || maxConcurrentPerAccount < 1)
    throw new Error('PROXY_MAX_CONCURRENT must be a positive integer.');
  return { providerURL, providerKey, maxTokens: boundedInteger(env.PROXY_MAX_TOKENS,4000,Number.MAX_SAFE_INTEGER,'PROXY_MAX_TOKENS'), maxConcurrentPerAccount,
    maxInFlight:boundedInteger(env.PROXY_MAX_IN_FLIGHT,10,1000,'PROXY_MAX_IN_FLIGHT'),
    timeoutMs:boundedInteger(env.PROXY_TIMEOUT_MS,30000,300000,'PROXY_TIMEOUT_MS'),
    maxResponseBytes:boundedInteger(env.PROXY_MAX_RESPONSE_BYTES,1048576,16777216,'PROXY_MAX_RESPONSE_BYTES',1024) };
};

const inputEstimate = (body: { messages?: unknown[]; input?: unknown }) => {
  const text = typeof body === 'object' && body !== null ? JSON.stringify(body) : '';
  return Math.ceil(text.length / 4) + 256;
};

const usageToken = (value: unknown): bigint | null =>
  typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER ? BigInt(value) : null;

/** Provider-reported usage, strictly validated: both counts must be present,
    integers within the safe range and non-negative. Anything else returns
    null so the caller can fail closed instead of settling a guess. */
export const providerUsage = (payload: unknown): { inputTokens: bigint; outputTokens: bigint } | null => {
  const usage = payload && typeof payload === 'object' && 'usage' in payload ? (payload as { usage: unknown }).usage : undefined;
  if (!usage || typeof usage !== 'object' || Array.isArray(usage)) return null;
  const fields = usage as Record<string, unknown>;
  const inputTokens = usageToken(fields.prompt_tokens), outputTokens = usageToken(fields.completion_tokens);
  return inputTokens === null || outputTokens === null ? null : { inputTokens, outputTokens };
};

export interface RateVersion { id: string; nanoPerMillionInput: bigint; nanoPerMillionOutput: bigint }

export async function activeRateVersion(db: Database): Promise<RateVersion> {
  const row = (await db.query(`SELECT id,nano_per_million_input,nano_per_million_output FROM rate_versions
    ORDER BY valid_from DESC LIMIT 1`)).rows[0];
  if (!row) throw new ServiceError('proxy_unavailable', 503);
  return { id: row.id, nanoPerMillionInput: BigInt(row.nano_per_million_input), nanoPerMillionOutput: BigInt(row.nano_per_million_output) };
}

export class TokenProxy {
  readonly #inFlight=new Map<string,number>();
  readonly #active=new Set<Promise<unknown>>();
  readonly #controllers=new Set<AbortController>();
  #stopping=false;
  readonly #instrument=paliqueMetrics.proxyInFlight;
  readonly #observe:Parameters<typeof paliqueMetrics.proxyInFlight.addCallback>[0]=o=>o.observe(this.#controllers.size);

  constructor(readonly db:Database,readonly config:TokenProxyConfig,readonly fetchFn:typeof fetch=fetch) {
    this.#instrument.addCallback(this.#observe);
  }
  async availableBalance(accountID:string):Promise<bigint> {
    const row=(await this.db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1',[accountID])).rows[0];
    return BigInt(row?.balance_nano??0n)-BigInt(row?.reserved_nano??0n);
  }
  async stop():Promise<void> {
    this.#stopping=true;
    for(const c of this.#controllers)c.abort(new Error('proxy_shutdown'));
    await Promise.allSettled([...this.#active]);
    this.#instrument.removeCallback(this.#observe);
  }
  async relay(accountID:string,body:Record<string,unknown>,options:{signal?:AbortSignal}={}):Promise<{status:number;payload:Record<string,unknown>}> {
    if(body.stream===true)throw new ServiceError('streaming_unsupported_v1',400);
    if(this.#stopping)throw new ServiceError('proxy_unavailable',503);
    const count=this.#inFlight.get(accountID)??0;
    if(count>=this.config.maxConcurrentPerAccount){paliqueMetrics.proxyRejected.add(1,{reason:'account'});throw new ServiceError('account_busy',429);}
    if(this.#controllers.size>=(this.config.maxInFlight??10)){paliqueMetrics.proxyRejected.add(1,{reason:'global'});throw new ServiceError('proxy_busy',503);}
    const controller=new AbortController();this.#controllers.add(controller);this.#inFlight.set(accountID,count+1);
    const cancel=()=>controller.abort(new Error('client_cancelled'));
    options.signal?.addEventListener('abort',cancel,{once:true});if(options.signal?.aborted)cancel();
    let timeout=false;
    const timer=setTimeout(()=>{timeout=true;controller.abort(new Error('deadline'));},this.config.timeoutMs??30000);
    const work=this.#meteredRelay(accountID,body,controller.signal,()=>timeout).finally(()=>{
      clearTimeout(timer);options.signal?.removeEventListener('abort',cancel);this.#controllers.delete(controller);
      const n=(this.#inFlight.get(accountID)??1)-1;if(n)this.#inFlight.set(accountID,n);else this.#inFlight.delete(accountID);
      this.#active.delete(work);
    });
    this.#active.add(work);return work;
  }
  async #meteredRelay(accountID:string,body:Record<string,unknown>,signal:AbortSignal,timedOut:()=>boolean):Promise<{status:number;payload:Record<string,unknown>}> {
    let id:string|undefined,dispatched=false,settling=false,terminal=false;
    let outcome='transport_error', began:number|undefined;
    try {
      signal.throwIfAborted();
      const balance=await this.availableBalance(accountID);signal.throwIfAborted();
      if(balance<=0n)throw new ServiceError('token_balance_exhausted',402);
      const rate=await activeRateVersion(this.db);signal.throwIfAborted();
      const estimate=BigInt(inputEstimate(body))*rate.nanoPerMillionInput/1_000_000n+BigInt(this.config.maxTokens)*rate.nanoPerMillionOutput/1_000_000n;
      if(estimate>balance)throw new ServiceError('token_balance_exhausted',402);
      id=randomUUID();await prepareProxyAttempt(this.db,{id,accountID,rateVersionID:rate.id});signal.throwIfAborted();
      await markProxyDispatched(this.db,id);dispatched=true;signal.throwIfAborted();
      began=performance.now();
      const response=await this.fetchFn(this.config.providerURL,{method:'POST',signal,
        headers:{'content-type':'application/json',authorization:`Bearer ${this.config.providerKey}`},body:JSON.stringify(body)});
      // Always consume under the same byte/time limit, including error responses.
      let payload:unknown;
      try {payload=await readBoundedJSON(response,{signal,maxBytes:this.config.maxResponseBytes??1048576});}
      catch(error){if(response.status>=400 && !(error instanceof ProxyResponseError && error.outcome==='oversize') && !signal.aborted)outcome='http_error';throw error;}
      if(response.status>=400){outcome='http_error';
        if([400,401,403,429].includes(response.status)){await finishProxyAttempt(this.db,id,'rejected');terminal=true;}
        throw new ServiceError('provider_error',502);
      }
      const usage=providerUsage(payload);
      if(!usage){outcome='invalid_usage';throw new ServiceError('provider_error',502);}
      // Known usage must be settled even if cancellation arrives while SQL is committing.
      settling=true;outcome='ok';
      const cost=usage.inputTokens*rate.nanoPerMillionInput/1_000_000n+usage.outputTokens*rate.nanoPerMillionOutput/1_000_000n;
      const metering=await settleProxyAttempt(this.db,{id,usage,estimateNano:estimate,costNano:cost,settledNano:cost>balance?balance:cost});
      terminal=true;
      return {status:200,payload:{...(payload as Record<string,unknown>),metering}};
    } catch(error) {
      if(id&&!terminal) {
        try {await finishProxyAttempt(this.db,id,dispatched?'unknown':'rejected');}
        catch {log.error('proxy_attempt_outcome_pending');}
        if(dispatched)paliqueMetrics.proxyUnknownAttempts.add(1);
      }
      if(!settling&&signal.aborted){outcome=timedOut()?'timeout':'client_cancelled';throw new ServiceError(timedOut()?'provider_timeout':'provider_error',timedOut()?504:502);}
      if(error instanceof ProxyResponseError){outcome=error.outcome;throw new ServiceError('provider_error',502);}
      if(error instanceof ServiceError)throw error;
      if(settling) {outcome='transport_error';throw error;}
      throw new ServiceError('provider_error',502);
    } finally {if(began!==undefined)paliqueMetrics.proxyProviderDuration.record((performance.now()-began)/1000,{outcome});}
  }
}

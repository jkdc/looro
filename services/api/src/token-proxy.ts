import { randomUUID } from 'node:crypto';
import type { Database } from './db.js';
import { transaction } from './db.js';
import { appendEntry } from './ledger.js';
import { ServiceError } from './errors.js';

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
export interface TokenProxyConfig { providerURL: string; providerKey: string; maxTokens: number; maxConcurrentPerAccount: number }

export const tokenProxyConfig = (env: NodeJS.ProcessEnv): TokenProxyConfig | undefined => {
  const providerURL = env.PROXY_PROVIDER_URL, providerKey = env.PROXY_PROVIDER_KEY;
  if (!providerURL || !providerKey) return undefined;
  try { new URL(providerURL); } catch { throw new Error('PROXY_PROVIDER_URL must be an absolute URL.'); }
  const maxConcurrentPerAccount = Number(env.PROXY_MAX_CONCURRENT ?? 2);
  if (!Number.isSafeInteger(maxConcurrentPerAccount) || maxConcurrentPerAccount < 1)
    throw new Error('PROXY_MAX_CONCURRENT must be a positive integer.');
  return { providerURL, providerKey, maxTokens: Number(env.PROXY_MAX_TOKENS ?? 4_000), maxConcurrentPerAccount };
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
  /** In-flight relay count per account. The cap check and increment are
      synchronous (no await between them), so the count is exact within this
      single process. */
  readonly #inFlight = new Map<string, number>();

  constructor(readonly db: Database, readonly config: TokenProxyConfig, readonly fetchFn: typeof fetch = fetch) {}

  async availableBalance(accountID: string): Promise<bigint> {
    const row = (await this.db.query(`SELECT w.balance_nano,w.reserved_nano FROM wallets w WHERE w.account_id=$1`, [accountID])).rows[0];
    return BigInt(row?.balance_nano ?? 0n) - BigInt(row?.reserved_nano ?? 0n);
  }

  async relay(accountID: string, body: Record<string, unknown>): Promise<{ status: number; payload: Record<string, unknown> }> {
    // v1 is non-streaming by contract: a streaming request would return SSE text
    // that the JSON metering path cannot parse (and its usage arrives in chunks),
    // so it is rejected before the provider is ever called. Clients send
    // `stream: false` — the app engines' OpenAICompatibleEngine does.
    if (body.stream === true) throw new ServiceError('streaming_unsupported_v1', 400);
    // Per-account concurrency cap, ahead of the wallet and provider path: one
    // account must not monopolise the shared server key.
    const inFlight = this.#inFlight.get(accountID) ?? 0;
    if (inFlight >= this.config.maxConcurrentPerAccount) throw new ServiceError('account_busy', 429);
    this.#inFlight.set(accountID, inFlight + 1);
    try {
      return await this.#meteredRelay(accountID, body);
    } finally {
      const remaining = (this.#inFlight.get(accountID) ?? 1) - 1;
      if (remaining <= 0) this.#inFlight.delete(accountID); else this.#inFlight.set(accountID, remaining);
    }
  }

  /** Balance admission, the provider call and metered settlement. Every exit
      path — including thrown metering errors — lands in relay()'s finally, so
      the concurrency slot always frees. */
  async #meteredRelay(accountID: string, body: Record<string, unknown>): Promise<{ status: number; payload: Record<string, unknown> }> {
    const balance = await this.availableBalance(accountID);
    if (balance <= 0n) throw new ServiceError('token_balance_exhausted', 402);
    const rate = await activeRateVersion(this.db);
    const estimate = BigInt(inputEstimate(body)) * rate.nanoPerMillionInput / 1_000_000n
      + BigInt(this.config.maxTokens) * rate.nanoPerMillionOutput / 1_000_000n;
    if (estimate > balance) throw new ServiceError('token_balance_exhausted', 402);

    const response = await this.fetchFn(this.config.providerURL, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${this.config.providerKey}` },
      body: JSON.stringify(body),
    });
    if (response.status >= 400) {
      await response.text();
      throw new ServiceError('provider_error', 502);
    }
    const payload = await response.json() as Record<string, unknown>;
    const usage = providerUsage(payload);
    // A success reply the server cannot meter is refused, not served free:
    // settlement is the only honest cost boundary, and a provider that omits
    // or corrupts its usage must never become an unbilled usage channel.
    // Nothing debits and the reply is not returned.
    if (!usage) throw new ServiceError('provider_error', 502);
    const cost = usage.inputTokens * rate.nanoPerMillionInput / 1_000_000n
      + usage.outputTokens * rate.nanoPerMillionOutput / 1_000_000n;
    const settled = cost > balance ? balance : cost;
    const reference = `proxy:${randomUUID()}`;
    await transaction(this.db, async sql => {
      // appendEntry applies the wallet delta itself; the ledger row is the audit trail.
      if (settled > 0n) await appendEntry(sql, accountID, reference, 'settle', -settled, 0n, rate.id);
      // Durable usage record: the token counts and what they cost, tied to the
      // settle entry when there was one. A zero-cost call is recorded without
      // a ledger row, so usage reporting never depends on money movement.
      await sql.query(`INSERT INTO proxy_usage(id,account_id,rate_version,input_tokens,output_tokens,estimate_nano,cost_nano,settled_nano,ledger_reference)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
      [randomUUID(), accountID, rate.id, usage.inputTokens.toString(), usage.outputTokens.toString(),
        estimate.toString(), cost.toString(), settled.toString(), settled > 0n ? reference : null]);
    });
    return { status: 200, payload: { ...payload, metering: { rateVersion: rate.id, nanoUSDSettled: settled.toString() } } };
  }
}

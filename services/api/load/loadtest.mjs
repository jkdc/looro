#!/usr/bin/env node
/** Local load driver for the Palique API (pre-deployment capacity signal).
 *
 * Usage (from the repo root, api_default network):
 *   docker run --rm --network api_default -v "$PWD":/repo -w /repo \
 *     -e BASE=http://api:8080 -e TOKENS=/repo/services/api/load/tokens.txt \
 *     node:22 node services/api/load/loadtest.mjs
 *
 * Phases: warmup → steady authenticated reads → unauthenticated challenge
 * writes → burst spike → proxy completions. Reports RPS and latency percentiles
 * per phase; the proxy phase also counts 402 (token_balance_exhausted) and
 * 403 (unlock_required) outcomes.
 * The local Postgres is a single docker container: results are a relative
 * regression signal, not an absolute production capacity number.
 *
 * The proxy phase drives POST /v1/proxy/responses with unlocked-account tokens.
 * It exists to load the API's metering and admission path, never a real
 * provider: start the target API with PROXY_PROVIDER_URL/PROXY_PROVIDER_KEY
 * pointed at a local stub provider (see services/api/tests/token-proxy.test.ts
 * for the shape) and never at MiniMax, OpenRouter or any other real endpoint.
 */
const BASE = process.env.BASE ?? 'http://api:8080';
// Production traffic reaches the API through the trusted reverse proxy, which
// forwards the real client IP. This driver simulates that: with the proxy
// token it rotates synthetic client IPs so the per-network rate limits (the
// same caps production enforces per user network) distribute across a
// realistic user population instead of one docker IP.
const PROXY_TOKEN = process.env.PROXY_TOKEN ?? '';
const NETWORKS = Number(process.env.NETWORKS ?? 200);
const tokens = (process.env.TOKENS ? await import('node:fs').then(fs => fs.readFileSync(process.env.TOKENS, 'utf8')) : '')
  .split('\n').map(t => t.trim()).filter(Boolean);

let requestCounter = 0;
const clientHeaders = () => {
  if (!PROXY_TOKEN) return {};
  const ip = `10.${Math.floor(requestCounter / 250) % 250}.${requestCounter % 250}.7`;
  requestCounter++;
  return { 'x-app-proxy-token': PROXY_TOKEN, 'x-app-client-ip': ip };
};

const fetchOpts = (token) => token ? { headers: { authorization: `Bearer ${token}`, ...clientHeaders() } } : { headers: clientHeaders() };

async function sample(method, path, token) {
  const t0 = performance.now();
  try {
    const res = await fetch(BASE + path, { method, ...fetchOpts(token), redirect: 'manual' });
    const ok = res.status < 400;
    await res.arrayBuffer();
    return { ok, status: res.status, ms: performance.now() - t0 };
  } catch { return { ok: false, status: 0, ms: performance.now() - t0 }; }
}

function pct(sorted, p) { return sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))] : 0; }

function report(name, results, wallSeconds) {
  const lat = results.filter(r => r.ok).map(r => r.ms).sort((a, b) => a - b);
  const errors = results.length - lat.length;
  return { name, requests: results.length, ok: lat.length, errors,
    rps: +(results.length / wallSeconds).toFixed(1),
    p50: +pct(lat, 50).toFixed(1), p95: +pct(lat, 95).toFixed(1), p99: +pct(lat, 99).toFixed(1),
    max: lat.length ? +lat[lat.length - 1].toFixed(1) : null };
}

async function runPhase(name, durationS, targetRps, tasks, { sample: sampleFn = sample, report: reportFn = report } = {}) {
  const results = [];
  const deadline = performance.now() + durationS * 1000;
  const interval = 1000 / targetRps;
  let nextAt = performance.now();
  const inFlight = new Set();
  while (performance.now() < deadline) {
    const now = performance.now();
    if (now >= nextAt) {
      const p = sampleFn(...tasks(results.length))
        .then(r => { results.push(r); })
        .catch(() => { results.push({ ok: false, status: 0, ms: 0 }); })
        .finally(() => inFlight.delete(p));
      inFlight.add(p);
      nextAt += interval;
    } else {
      await new Promise(r => setTimeout(r, Math.min(deadline - now, Math.max(1, nextAt - now))));
    }
  }
  await Promise.allSettled([...inFlight]);
  return reportFn(name, results, durationS);
}

async function spike(concurrency, tasks) {
  const t0 = performance.now();
  const results = await Promise.all(Array.from({ length: concurrency }, (_, i) => sample(...tasks(i))));
  return report(`spike(${concurrency})`, results, (performance.now() - t0) / 1000);
}

// Proxy phase: POST /v1/proxy/responses with a non-streaming (v1 contract)
// OpenAI-compatible body. Latency percentiles count 200s only; 402
// (token_balance_exhausted) and 403 (unlock_required) are reported separately
// so an exhausted or locked wallet population is visible, not buried in errors.
const PROXY_BODY = JSON.stringify({ model: 'stub', stream: false, messages: [{ role: 'user', content: 'loadtest' }] });

async function proxySample(token) {
  const t0 = performance.now();
  try {
    const res = await fetch(BASE + '/v1/proxy/responses', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}`, ...clientHeaders() },
      body: PROXY_BODY, redirect: 'manual' });
    await res.arrayBuffer();
    return { ok: res.status === 200, status: res.status, ms: performance.now() - t0 };
  } catch { return { ok: false, status: 0, ms: performance.now() - t0 }; }
}

function proxyReport(name, results, wallSeconds) {
  const ok = results.filter(r => r.status === 200).map(r => r.ms).sort((a, b) => a - b);
  const count = (status) => results.filter(r => r.status === status).length;
  return { name, requests: results.length, ok: ok.length, exhausted402: count(402), locked403: count(403),
    errors: results.length - ok.length - count(402) - count(403),
    rps: +(results.length / wallSeconds).toFixed(1),
    p50: +pct(ok, 50).toFixed(1), p95: +pct(ok, 95).toFixed(1), p99: +pct(ok, 99).toFixed(1),
    max: ok.length ? +ok[ok.length - 1].toFixed(1) : null };
}

const READS = [['/v1/pricing'], ['/v1/minutes/products'], ['/healthz']];
const readTask = (i) => {
  if (tokens.length) return ['GET', '/v1/minutes', tokens[i % tokens.length]];
  const [m, p] = READS[i % READS.length];
  return [m, p, undefined];
};
const writeTask = () => ['POST', '/v1/auth/challenge', undefined];
const mixed = (i) => (i % 5 === 0 ? writeTask() : readTask(i));

console.log('target:', BASE, 'tokens:', tokens.length);
// The proxy phase runs only with tokens: an anonymous proxy call is just 401
// noise, while an unlocked-account population exercises metering, 402s and
// 403s. It must target a stub provider per the header note.
const phases = [
  await runPhase('warmup', 10, 20, readTask),
  await runPhase('steady-reads', 45, 120, readTask),
  await runPhase('challenge-writes', 25, 25, writeTask),
  await runPhase('mixed', 30, 60, mixed),
  await spike(300, readTask),
];
if (tokens.length) phases.push(await runPhase('proxy-completions', 20, 5, i => [tokens[i % tokens.length]],
  { sample: proxySample, report: proxyReport }));
console.log(phases.map(r => JSON.stringify(r)).join('\n'));

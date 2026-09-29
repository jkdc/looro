#!/usr/bin/env node
/** Local load driver for the Palique API (pre-deployment capacity signal).
 *
 * Usage (from the repo root, api_default network):
 *   docker run --rm --network api_default -v "$PWD":/repo -w /repo \
 *     -e BASE=http://api:8080 -e TOKENS=/repo/services/api/load/tokens.txt \
 *     node:22 node services/api/load/loadtest.mjs
 *
 * Phases: warmup → steady authenticated reads → unauthenticated challenge
 * writes → burst spike. Reports RPS and latency percentiles per phase.
 * The local Postgres is a single docker container: results are a relative
 * regression signal, not an absolute production capacity number.
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
    return { ok, ms: performance.now() - t0 };
  } catch { return { ok: false, ms: performance.now() - t0 }; }
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

async function runPhase(name, durationS, targetRps, tasks) {
  const results = [];
  const deadline = performance.now() + durationS * 1000;
  const interval = 1000 / targetRps;
  let nextAt = performance.now();
  const inFlight = new Set();
  while (performance.now() < deadline) {
    const now = performance.now();
    if (now >= nextAt) {
      const p = sample(...tasks(results.length))
        .then(r => { results.push(r); })
        .catch(() => { results.push({ ok: false, ms: 0 }); })
        .finally(() => inFlight.delete(p));
      inFlight.add(p);
      nextAt += interval;
    } else {
      await new Promise(r => setTimeout(r, Math.min(deadline - now, Math.max(1, nextAt - now))));
    }
  }
  await Promise.allSettled([...inFlight]);
  return report(name, results, durationS);
}

async function spike(concurrency, tasks) {
  const t0 = performance.now();
  const results = await Promise.all(Array.from({ length: concurrency }, (_, i) => sample(...tasks(i))));
  return report(`spike(${concurrency})`, results, (performance.now() - t0) / 1000);
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
console.log([
  await runPhase('warmup', 10, 20, readTask),
  await runPhase('steady-reads', 45, 120, readTask),
  await runPhase('challenge-writes', 25, 25, writeTask),
  await runPhase('mixed', 30, 60, mixed),
  await spike(300, readTask),
].map(r => JSON.stringify(r)).join('\n'));

/** Per-network inbound request cap (requests/minute) for the trusted-proxy
    admission limiter (app.ts). The hardcoded 120 locked out a single operator
    running the admin panel: every open tab polls status endpoints every ~2s
    and rejected requests still consume the window, so one busy minute locked
    the panel (and everything else on the same network) out entirely.
    API_REQUESTS_PER_MINUTE raises it per deployment; default stays 120. */
export function requestRateLimitPerMinute(env: NodeJS.ProcessEnv): number {
  const raw = env.API_REQUESTS_PER_MINUTE ?? '';
  if (raw === '') return 120;
  const n = Number(raw);
  if (!Number.isSafeInteger(n) || n < 1 || n > 10_000)
    throw new Error('API_REQUESTS_PER_MINUTE must be an integer within [1, 10000].');
  return n;
}

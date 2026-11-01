/** Outbound provider URL policy: server-side fetch targets carry the server's
    API key in the Authorization header, so plain http to a public host is a
    configuration error. Loopback http transports stay available for local
    labs. Mirrors the rule LiveProvider already enforces on its own origin. */
export function assertOutboundUrl(value: string, name: string): string {
  let url: URL;
  try { url = new URL(value); }
  catch { throw new Error(`${name} must be an absolute URL.`); }
  if (url.username || url.password) throw new Error(`${name} must not embed credentials.`);
  if (url.protocol === 'https:') return value;
  const loopback = url.protocol === 'http:' && ['127.0.0.1', '[::1]', '::1', 'localhost'].includes(url.hostname);
  if (!loopback) throw new Error(`${name} must be https (plain http is only allowed for loopback transports).`);
  return value;
}

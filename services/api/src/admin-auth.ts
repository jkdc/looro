import { randomBytes, randomUUID } from 'node:crypto';
import type { IncomingHttpHeaders } from 'node:http';
import { digest, verifyIdentity, type Provider } from './auth.js';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export interface AdminAuthConfig { googleClientID?: string; appleClientID?: string; hmacKey: string;
  proxyToken: string; allowedOrigin: string; allowLocalLoopback: boolean }
export interface AdminAuthServices { db: Database; config: AdminAuthConfig;
  identityVerifier?: typeof verifyIdentity }

export const ADMIN_COOKIE = 'admin_session';
export const ADMIN_SESSION_SECONDS = 8 * 3_600;
const MAX_LIVE_SESSIONS = 3;

export function adminAuthConfig(env: NodeJS.ProcessEnv): AdminAuthConfig | undefined {
  const googleClientID = env.GOOGLE_ADMIN_CLIENT_ID, appleClientID = env.APPLE_ADMIN_CLIENT_ID;
  if (!googleClientID && !appleClientID) return undefined;
  const hmacKey = env.ADMIN_HMAC_KEY ?? '', proxyToken = env.ADMIN_PROXY_TOKEN ?? '';
  if (!/^[a-f0-9]{64}$/.test(hmacKey) || !/^[a-f0-9]{64}$/.test(proxyToken) || hmacKey === proxyToken)
    throw new Error('Admin auth requires separate random 32-byte hex secrets.');
  // The panel origin is the CSRF anchor: every mutating admin route compares Origin against it.
  const allowedOrigin = env.ADMIN_PUBLIC_ORIGIN ?? '';
  const httpsOrigin = /^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/.test(allowedOrigin);
  const loopbackOrigin = /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d{1,5})?$/.test(allowedOrigin);
  const allowLocalLoopback = env.ADMIN_ALLOW_LOCAL_LOOPBACK === 'true';
  if (!httpsOrigin && !(loopbackOrigin && allowLocalLoopback))
    throw new Error('ADMIN_PUBLIC_ORIGIN must be an exact https origin (or loopback with local loopback allowed).');
  return { googleClientID, appleClientID, hmacKey, proxyToken, allowedOrigin, allowLocalLoopback };
}

export function adminCookieHeader(token: string, maxAgeSeconds = ADMIN_SESSION_SECONDS): string {
  return `${ADMIN_COOKIE}=${token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAgeSeconds}`;
}
export const adminClearCookieHeader = () => `${ADMIN_COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0`;

export function adminBearer(headers: IncomingHttpHeaders): string | undefined {
  const cookie = headers.cookie;
  if (typeof cookie !== 'string') return undefined;
  for (const part of cookie.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === ADMIN_COOKIE) { const value = rest.join('=');
      return /^[A-Za-z0-9_-]{43}$/.test(value) ? value : undefined; }
  }
  return undefined;
}

export function assertAdminOrigin(headers: IncomingHttpHeaders, allowedOrigin: string, method: string): void {
  if (!['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) return;
  // SameSite=Strict already blocks most cross-site sends; this closes the rest.
  if (headers.origin !== undefined && headers.origin !== allowedOrigin) throw new ServiceError('admin_origin_required', 403);
}

export async function exchangeAdminIdentity(services: AdminAuthServices, provider: Provider, token: string,
  challengeID: string, verify: NonNullable<AdminAuthServices['identityVerifier']>): Promise<{ adminID: string; token: string }> {
  const nonce = (await services.db.query('SELECT nonce_hash FROM auth_challenges WHERE id=$1 AND expires_at>now() AND used_at IS NULL',
    [challengeID])).rows[0];
  if (!nonce) throw new ServiceError('invalid_challenge', 401);
  const identity = await verify(provider, token, nonce.nonce_hash as string, services.config);
  const row = (await services.db.query('SELECT id, disabled_at FROM admins WHERE provider=$1 AND subject=$2',
    [identity.provider, identity.subject])).rows[0];
  // Generic refusal: no oracle about whether an identity exists or why it cannot enter.
  if (!row) throw new ServiceError('not_admin', 403);
  if (row.disabled_at) throw new ServiceError('admin_disabled', 403);
  const adminToken = randomBytes(32).toString('base64url');
  await transaction(services.db, async sql => {
    const claimed = await sql.query('UPDATE auth_challenges SET used_at=now() WHERE id=$1 AND used_at IS NULL AND expires_at>now() RETURNING id', [challengeID]);
    if (!claimed.rowCount) throw new ServiceError('invalid_challenge', 401);
    if (identity.email !== null) await sql.query('UPDATE admins SET email=$2 WHERE id=$1', [row.id, identity.email]);
    // Keep only the newest sessions; a lost device cannot accumulate silent access.
    await sql.query(`DELETE FROM admin_sessions WHERE admin_id=$1 AND (expires_at<=now() OR revoked_at IS NOT NULL OR id IN
      (SELECT id FROM admin_sessions WHERE admin_id=$1 AND expires_at>now() AND revoked_at IS NULL
      ORDER BY created_at DESC,id DESC OFFSET $2))`, [row.id, MAX_LIVE_SESSIONS - 1]);
    await sql.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at)
      VALUES($1,$2,$3,now()+interval '${ADMIN_SESSION_SECONDS} seconds')`, [randomUUID(), row.id, digest(adminToken)]);
  });
  return { adminID: row.id as string, token: adminToken };
}

export async function exchangeSessionToken(services: AdminAuthServices, bearer: string): Promise<{ adminID: string; token: string }> {
  // Browser-free clients authenticate with the bearer minted at exchange and may
  // convert it to the cookie form; the token's authority is unchanged.
  const row = (await services.db.query(`SELECT s.admin_id FROM admin_sessions s JOIN admins a ON a.id=s.admin_id
    WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL AND a.disabled_at IS NULL`,
  [digest(bearer)])).rows[0];
  if (!row) throw new ServiceError('admin_sign_in_required', 401);
  return { adminID: row.admin_id as string, token: bearer };
}

export async function adminAuthenticate(db: Database, headers: IncomingHttpHeaders): Promise<{ adminID: string; email: string | null }> {
  const token = adminBearer(headers);
  const row = (await db.query(`SELECT s.admin_id,a.email FROM admin_sessions s JOIN admins a ON a.id=s.admin_id
    WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL AND a.disabled_at IS NULL`,
  [token ? digest(token) : ''])).rows[0];
  if (!row) throw new ServiceError('admin_sign_in_required', 401);
  return { adminID: row.admin_id as string, email: row.email as string | null };
}

export async function adminSignOut(services: AdminAuthServices, headers: IncomingHttpHeaders): Promise<void> {
  const token = adminBearer(headers);
  await services.db.query('UPDATE admin_sessions SET revoked_at=now() WHERE token_hash=$1', [token ? digest(token) : '']);
}

export async function pruneAdminRecords(db: Database): Promise<void> {
  await db.query('DELETE FROM admin_sessions WHERE expires_at<now() OR revoked_at IS NOT NULL');
}

import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import type { IncomingHttpHeaders } from 'node:http';
import { digest } from './auth.js';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export interface AdminAuthConfig { hmacKey: string; proxyToken: string; allowedOrigin: string; allowLocalLoopback: boolean }
export interface AdminAuthServices { db: Database; config: AdminAuthConfig }

export const ADMIN_COOKIE = 'admin_session';
export const ADMIN_SESSION_SECONDS = 8 * 3_600;
const MAX_LIVE_SESSIONS = 3;

export function adminAuthConfig(env: NodeJS.ProcessEnv): AdminAuthConfig | undefined {
  const hmacKey = env.ADMIN_HMAC_KEY ?? '', proxyToken = env.ADMIN_PROXY_TOKEN ?? '';
  // The panel only exists when its operator secrets are provisioned; identity
  // providers play no part — operators are accounts created locally via the CLI.
  if (!hmacKey && !proxyToken && !env.ADMIN_PUBLIC_ORIGIN) return undefined;
  if (!/^[a-f0-9]{64}$/.test(hmacKey) || !/^[a-f0-9]{64}$/.test(proxyToken) || hmacKey === proxyToken)
    throw new Error('Admin auth requires separate random 32-byte hex secrets.');
  // The panel origin is the CSRF anchor: every mutating admin route compares Origin against it.
  const allowedOrigin = env.ADMIN_PUBLIC_ORIGIN ?? '';
  const httpsOrigin = /^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/.test(allowedOrigin);
  const loopbackOrigin = /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d{1,5})?$/.test(allowedOrigin);
  const allowLocalLoopback = env.ADMIN_ALLOW_LOCAL_LOOPBACK === 'true';
  if (!httpsOrigin && !(loopbackOrigin && allowLocalLoopback))
    throw new Error('ADMIN_PUBLIC_ORIGIN must be an exact https origin (or loopback with local loopback allowed).');
  return { hmacKey, proxyToken, allowedOrigin, allowLocalLoopback };
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

/* ---- local operator credentials (the only web login) ---- */

const SCRYPT_PARAMS = 'scrypt$N=16384,r=8,p=1';

export function hashAdminPassword(password: string): string {
  const salt = randomBytes(16);
  const key = scryptSync(password, salt, 32, { N: 16384, r: 8, p: 1 });
  return `${SCRYPT_PARAMS}$${salt.toString('hex')}$${key.toString('hex')}`;
}

export function verifyAdminPassword(password: string, stored: string): boolean {
  const parts = stored.split('$');
  if (parts.length !== 4 || parts[0] !== 'scrypt' || parts[1] !== 'N=16384,r=8,p=1') return false;
  const salt = Buffer.from(parts[2] ?? '', 'hex'), key = Buffer.from(parts[3] ?? '', 'hex');
  if (salt.length !== 16 || key.length !== 32) return false;
  return timingSafeEqual(key, scryptSync(password, salt, 32, { N: 16384, r: 8, p: 1 }));
}

/** Web panel login with locally provisioned credentials. The challenge stays
 *  single-use, and unknown users burn a dummy verification so response timing
 *  does not reveal whether the operator account exists. */
export async function localAdminLogin(services: AdminAuthServices,
  credentials: { username?: string; password?: string }, challengeID: string): Promise<{ adminID: string; token: string }> {
  const username = credentials.username, password = credentials.password;
  if (typeof username !== 'string' || !/^[a-z0-9._-]{3,64}$/.test(username) ||
      typeof password !== 'string' || !password || password.length > 1024)
    throw new ServiceError('invalid_credentials', 401);
  const nonce = (await services.db.query('SELECT nonce_hash FROM auth_challenges WHERE id=$1 AND expires_at>now() AND used_at IS NULL',
    [challengeID])).rows[0];
  if (!nonce) throw new ServiceError('invalid_challenge', 401);
  const row = (await services.db.query(`SELECT id, disabled_at, password_hash FROM admins
    WHERE provider='local' AND subject=$1`, [username])).rows[0];
  const ok = row?.password_hash ? verifyAdminPassword(password, row.password_hash as string) :
    (verifyAdminPassword(password, hashAdminPassword(password === '' ? '-' : 'x')), false);
  if (!ok) throw new ServiceError('invalid_credentials', 401);
  if (row.disabled_at) throw new ServiceError('admin_disabled', 403);
  const adminToken = randomBytes(32).toString('base64url');
  await transaction(services.db, async sql => {
    const claimed = await sql.query('UPDATE auth_challenges SET used_at=now() WHERE id=$1 AND used_at IS NULL AND expires_at>now() RETURNING id', [challengeID]);
    if (!claimed.rowCount) throw new ServiceError('invalid_challenge', 401);
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
  // Browser-free clients authenticate with the bearer minted at login and may
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

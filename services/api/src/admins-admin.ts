import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import type { PoolClient } from 'pg';
import { connectDatabase, transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

const uuidPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
export type AdminProvider = 'google' | 'apple';
export interface AdminRecord { id: string; email: string | null; provider: AdminProvider; subject: string;
  createdAt: string; disabledAt: string | null; liveSessions?: number }

// Administrator registration is a local operator action against the database.
// The panel never grants or revokes admin status; see docs/admin-panel.md.
export async function addAdmin(db: Database, provider: string, subject: string, email?: string):
  Promise<AdminRecord> {
  if (provider !== 'google' && provider !== 'apple') throw new ServiceError('invalid_admin_provider');
  if (typeof subject !== 'string' || !subject || subject.length > 255) throw new ServiceError('invalid_admin_subject');
  if (email !== undefined && (typeof email !== 'string' || email.length > 254)) throw new ServiceError('invalid_admin_email');
  return transaction(db, async sql => {
    await sql.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`admin:${provider}:${subject}`]);
    if ((await sql.query('SELECT 1 FROM admins WHERE provider=$1 AND subject=$2', [provider, subject])).rowCount)
      throw new ServiceError('admin_already_registered', 409);
    const id = randomUUID();
    await sql.query('INSERT INTO admins(id,email,provider,subject) VALUES($1,$2,$3,$4)', [id, email ?? null, provider, subject]);
    return { id, email: email ?? null, provider: provider as AdminProvider, subject, createdAt: new Date().toISOString(), disabledAt: null };
  });
}

export async function listAdmins(db: Database): Promise<AdminRecord[]> {
  return (await db.query(`SELECT a.id,a.email,a.provider,a.subject,a.created_at,a.disabled_at,
    (SELECT count(*) FROM admin_sessions s WHERE s.admin_id=a.id AND s.expires_at>now() AND s.revoked_at IS NULL) AS live_sessions
    FROM admins a ORDER BY a.created_at, a.id`)).rows.map(row => ({
    id: row.id as string, email: row.email as string | null, provider: row.provider as AdminProvider,
    subject: row.subject as string, createdAt: (row.created_at as Date).toISOString(),
    disabledAt: row.disabled_at ? (row.disabled_at as Date).toISOString() : null,
    liveSessions: Number(row.live_sessions)
  }));
}

export async function disableAdmin(db: Database, id: string): Promise<void> {
  if (typeof id !== 'string' || !uuidPattern.test(id)) throw new ServiceError('invalid_admin_id');
  await transaction(db, async sql => {
    await sql.query('UPDATE admins SET disabled_at=COALESCE(disabled_at,now()) WHERE id=$1', [id]);
    // A disabled administrator loses live sessions immediately, not at expiry.
    await sql.query('UPDATE admin_sessions SET revoked_at=now() WHERE admin_id=$1 AND revoked_at IS NULL', [id]);
  });
}

export async function recordAdminAction(sql: PoolClient, adminID: string,
  action: string, target: string, reason: string, detail: unknown): Promise<void> {
  if (!uuidPattern.test(adminID) || !action || !target || typeof reason !== 'string' ||
      reason.trim().length < 3 || reason.length > 500 || /[\x00-\x1f]/.test(reason))
    throw new ServiceError('admin_reason_required');
  await sql.query('INSERT INTO admin_actions(id,admin_id,action,target,reason,detail) VALUES($1,$2,$3,$4,$5,$6)',
    [randomUUID(), adminID, action, target, reason, JSON.stringify(detail ?? {})]);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const databaseURL = process.env.DATABASE_URL, command = process.argv[2];
  if (!databaseURL || !['add', 'list', 'disable'].includes(command ?? '')) {
    console.error('Use DATABASE_URL and admins-admin.js add <google|apple> <subject> [email] | list | disable <admin-id>.');
    process.exitCode = 1;
  } else {
    const db = connectDatabase(databaseURL);
    try {
      if (command === 'add') {
        const [provider, subject, email] = process.argv.slice(3);
        if (!provider || !subject || process.argv.length > 6) throw new Error();
        console.info(JSON.stringify(await addAdmin(db, provider, subject, email)));
      } else if (command === 'list') {
        if (process.argv.length !== 3) throw new Error();
        for (const admin of await listAdmins(db))
          console.info(`${admin.id} ${admin.provider} ${admin.subject}${admin.email ? ' ' + admin.email : ''}` +
            `${admin.disabledAt ? ' DISABLED' : ''} sessions=${admin.liveSessions}`);
      } else {
        if (process.argv.length !== 4) throw new Error();
        await disableAdmin(db, process.argv[3]!);
        console.info('Administrator disabled and sessions revoked.');
      }
    } catch (error) {
      console.error(error instanceof ServiceError ? error.code : 'admin_operation_failed');
      process.exitCode = 1;
    } finally { await db.end(); }
  }
}

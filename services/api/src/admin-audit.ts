import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { ServiceError } from './errors.js';

const uuidPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

/** Persist one append-only admin action on the caller's existing transaction. */
export async function recordAdminAction(sql: PoolClient, adminID: string,
  action: string, target: string, reason: string, detail: unknown): Promise<void> {
  if (!uuidPattern.test(adminID) || !action || !target || typeof reason !== 'string' ||
      reason.trim().length < 3 || reason.length > 500 || /[\x00-\x1f]/.test(reason))
    throw new ServiceError('admin_reason_required');
  await sql.query('INSERT INTO admin_actions(id,admin_id,action,target,reason,detail) VALUES($1,$2,$3,$4,$5,$6)',
    [randomUUID(), adminID, action, target, reason, JSON.stringify(detail ?? {})]);
}

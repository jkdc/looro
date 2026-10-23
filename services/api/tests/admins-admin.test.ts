import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { addAdmin, disableAdmin, listAdmins } from '../src/admins-admin.js';
import { recordAdminAction } from '../src/admin-audit.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE admin_actions, admin_sessions, admins CASCADE'); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

integration('administrators are registered per provider subject and duplicates are refused', async () => {
  const first = await addAdmin(db!, 'google', 'operator-subject-1', 'root@example.test');
  assert.match(first.id, /^[a-f0-9-]{36}$/);
  assert.equal(first.provider, 'google');
  await assert.rejects(addAdmin(db!, 'google', 'operator-subject-1', 'again@example.test'), /admin_already_registered/);
  // The same human on another provider, or the same subject spelling, is a different principal.
  await addAdmin(db!, 'apple', 'operator-subject-1');
  const admins = await listAdmins(db!);
  assert.equal(admins.length, 2);
  assert.equal(admins[0]!.liveSessions, 0);
});

integration('invalid registrations are refused before any query matters', async () => {
  for (const [provider, subject, email] of [['microsoft', 's'], ['google', ''], ['google', 'x'.repeat(256)],
    ['google', 's', 'e'.repeat(255)]] as const)
    await assert.rejects(addAdmin(db!, provider, subject, email));
  assert.equal((await listAdmins(db!)).length, 0);
});

integration('disabling an administrator revokes live sessions immediately', async () => {
  const admin = await addAdmin(db!, 'google', 'operator-subject-2');
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, 'hash-a']);
  await db!.query(`INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')`,
    [randomUUID(), admin.id, 'hash-b']);
  assert.equal((await listAdmins(db!))[0]!.liveSessions, 2);
  await disableAdmin(db!, admin.id);
  const after = (await listAdmins(db!))[0]!;
  assert.ok(after.disabledAt);
  assert.equal(after.liveSessions, 0);
  await assert.rejects(disableAdmin(db!, 'not-a-uuid'), /invalid_admin_id/);
});

integration('admin action audit rows are append-only', async () => {
  const admin = await addAdmin(db!, 'google', 'operator-subject-3');
  await transaction(db!, async sql => {
    await recordAdminAction(sql, admin.id, 'policy.update', 'minute_policy', 'raise welcome allowance to 15', { version: 2 });
  });
  await assert.rejects(transaction(db!, async sql => {
    await sql.query('UPDATE admin_actions SET reason=$1', ['tampered']);
  }));
  await assert.rejects(transaction(db!, async sql => {
    await sql.query('DELETE FROM admin_actions');
  }));
});

integration('audit reasons are validated', async () => {
  const admin = await addAdmin(db!, 'google', 'operator-subject-4');
  for (const reason of ['', 'ab', 'x'.repeat(501), 'line\nbreak'])
    await assert.rejects(transaction(db!, async sql => recordAdminAction(sql, admin.id, 'test', 't', reason, {})),
      /admin_reason_required/);
  await assert.rejects(transaction(db!, async sql => recordAdminAction(sql, 'not-a-uuid', 'test', 't', 'valid reason', {})));
});

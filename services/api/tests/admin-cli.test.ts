import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const db = databaseURL ? connectDatabase(databaseURL) : null;
const root = dirname(dirname(fileURLToPath(import.meta.url)));
const tsx = join(root, 'node_modules', '.bin', 'tsx');
before(async () => { if (db) await migrate(db); });
after(async () => {
  if (db) {
    await db.query("DELETE FROM admins WHERE subject LIKE 'cli-test-%'");
    await db.query("DELETE FROM access_requests WHERE email LIKE 'cli-test-%'");
    await db.end();
  }
});
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

interface Run { status: number | null; stdout: string; stderr: string }
const cli = (script: string, args: string[], stdin = '', database = databaseURL): Run => {
  const result = spawnSync(tsx, [join('src', `${script}.ts`), ...args], {
    cwd: root, encoding: 'utf8', input: stdin, timeout: 90_000,
    env: { ...process.env, DATABASE_URL: database ?? '' }
  });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
};

integration('every operator CLI refuses to run without its required arguments', async () => {
  for (const [script, usage] of [
    ['admins-admin', 'admins-admin\\.js'], ['access-requests-admin', 'access-requests-admin\\.js'],
    ['minutes-admin', 'minutes-admin'], ['tokens-admin', 'tokens-admin'],
    ['account-closeout-admin', 'account-closeout-admin']
  ] as const) {
    const result = cli(script, []);
    assert.equal(result.status, 1, `${script}: ${result.stderr}`);
    assert.match(result.stderr, new RegExp(usage));
  }
});

integration('admins-admin adds, lists, passwords and disables an operator', async () => {
  const subject = `cli-test-${randomUUID()}`;
  const added = cli('admins-admin', ['add', 'local', subject, `${subject}@example.test`]);
  assert.equal(added.status, 0, added.stderr);
  const adminID = JSON.parse(added.stdout).id as string;
  assert.ok(adminID);

  const listed = cli('admins-admin', ['list']);
  assert.equal(listed.status, 0, listed.stderr);
  assert.match(listed.stdout, new RegExp(`${adminID} local ${subject}`));

  const passwd = cli('admins-admin', ['passwd', adminID], 'cli-test-password\n');
  assert.equal(passwd.status, 0, passwd.stderr);
  assert.match(passwd.stdout, /Password set/);
  assert.equal((await db!.query('SELECT count(*)::int AS n FROM admin_sessions WHERE admin_id=$1', [adminID])).rows[0]!.n, 0);

  const disabled = cli('admins-admin', ['disable', adminID]);
  assert.equal(disabled.status, 0, disabled.stderr);
  assert.match(disabled.stdout, /disabled and sessions revoked/i);
  assert.ok((await db!.query('SELECT disabled_at FROM admins WHERE id=$1', [adminID])).rows[0]!.disabled_at);

  const unknownCommand = cli('admins-admin', ['invent']);
  assert.equal(unknownCommand.status, 1);
});

integration('access-requests-admin prunes, exports privately and deletes by email', async () => {
  const fresh = `cli-test-${randomUUID()}@example.test`, expired = `cli-test-${randomUUID()}@example.test`;
  await db!.query(`INSERT INTO access_requests(email,requested_at,consent_version,source)
    VALUES($1,now(),'waitlist-v1','website'),($2,now()-interval '13 months','waitlist-v1','website')`, [fresh, expired]);
  await db!.query(`INSERT INTO access_request_limits(scope,identifier,window_start,expires_at,hits)
    VALUES('ip_hour',$1,now()-interval '2 days',now()-interval '1 day',1)`, ['a'.repeat(64)]);

  const pruned = cli('access-requests-admin', ['prune']);
  assert.equal(pruned.status, 0, pruned.stderr);
  assert.match(pruned.stdout, /Pruned [1-9]\d* expired requests and [1-9]\d* rate buckets/);
  assert.equal((await db!.query('SELECT email FROM access_requests WHERE email=$1', [expired])).rowCount, 0);

  const directory = await mkdtemp(join(tmpdir(), 'access-export-'));
  const target = join(directory, 'requests.json');
  try {
    const exported = cli('access-requests-admin', ['export', target]);
    assert.equal(exported.status, 0, exported.stderr);
    const payload = JSON.parse(await readFile(target, 'utf8'));
    assert.equal(payload.version, 1);
    const emails = payload.requests.map((row: { email: string }) => row.email);
    assert.ok(emails.includes(fresh));
    assert.equal((await readFile(target, 'utf8')).includes(expired), false);

    const relative = cli('access-requests-admin', ['export', 'relative.json']);
    assert.equal(relative.status, 1);
    assert.match(relative.stderr, /No email or database details are logged/);
  } finally { await rm(directory, { recursive: true, force: true }); }

  const deleted = cli('access-requests-admin', ['delete'], fresh);
  assert.equal(deleted.status, 0, deleted.stderr);
  assert.equal((await db!.query('SELECT email FROM access_requests WHERE email=$1', [fresh])).rowCount, 0);
});

integration('minutes, tokens and closeout CLIs print their JSON contracts', async () => {
  const policy = cli('minutes-admin', ['policy']);
  assert.equal(policy.status, 0, policy.stderr);
  const current = JSON.parse(policy.stdout);
  const updatedPolicy = cli('minutes-admin', ['set-policy'], JSON.stringify({
    policy: { version: current.version, welcomeEnabled: current.welcomeEnabled, welcomeMinutes: current.welcomeMinutes },
    actor: 'cli-test-operator', reason: 'Exercise the operator policy wrapper'
  }));
  assert.equal(updatedPolicy.status, 0, updatedPolicy.stderr);
  assert.equal(JSON.parse(updatedPolicy.stdout).version, current.version + 1);

  const accountID = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [accountID, `cli-test-${accountID}@example.test`]);
  await db!.query('INSERT INTO wallets(account_id) VALUES($1) ON CONFLICT DO NOTHING', [accountID]);
  const summary = cli('tokens-admin', ['show', accountID]);
  assert.equal(summary.status, 0, summary.stderr);
  assert.equal(JSON.parse(summary.stdout).accountID, accountID);
  assert.equal(cli('tokens-admin', ['show', 'not-a-uuid']).status, 1);

  // Operator unlock wrappers are local-only CLIs; their effect is re-read from
  // the summary instead of trusting the command output.
  const grant = { accountID, actor: 'cli-test-operator', reason: 'Exercise the operator unlock wrapper' };
  const granted = cli('tokens-admin', ['grant-unlock'], JSON.stringify(grant));
  assert.equal(granted.status, 0, granted.stderr);
  assert.equal(JSON.parse(granted.stdout).changed, true);
  const unlocked = cli('tokens-admin', ['show', accountID]);
  assert.equal(JSON.parse(unlocked.stdout).entitlement.unlocked, true);
  const revoked = cli('tokens-admin', ['revoke-unlock'], JSON.stringify(grant));
  assert.equal(revoked.status, 0, revoked.stderr);
  assert.equal(JSON.parse(revoked.stdout).unlocked, false);

  const closeout = cli('account-closeout-admin', ['inspect'], JSON.stringify({ accountID }));
  assert.equal(closeout.status, 0, closeout.stderr);
  assert.equal(JSON.parse(closeout.stdout).accountID, accountID);
  const malformed = cli('account-closeout-admin', ['inspect'], JSON.stringify({ accountID, extra: true }));
  assert.equal(malformed.status, 1);
  assert.equal(malformed.stderr.trim(), 'invalid_request');

  const migrated = cli('migrate', []);
  assert.equal(migrated.status, 0, migrated.stderr);
  assert.match(migrated.stdout, /Palique schema ready/);

  // An unreachable database must fail the migration instead of reporting success.
  const failed = cli('migrate', [], '', 'postgresql://nobody:nobody@127.0.0.1:1/unreachable');
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /Palique migration failed/);
});

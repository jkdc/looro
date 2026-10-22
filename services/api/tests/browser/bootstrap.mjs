// Bootstrap for the browser-E2E stack: one local operator with a known
// password, plus one seeded student exam attempt for the results section.
// The draft weeks are NOT inserted here — the seed-generate service creates
// them through the real generation pipeline against the stub provider.
import pg from 'pg';
import { randomUUID, createHash } from 'node:crypto';

const databaseURL = process.env.DATABASE_URL;
if (!databaseURL) { console.error('DATABASE_URL required'); process.exit(1); }

const db = new pg.Pool({ connectionString: databaseURL });
try {
  const admins = await import('/app/dist/src/admins-admin.js');
  let adminID;
  try {
    adminID = (await admins.addAdmin(db, 'local', 'e2e-operator', 'e2e@example.test')).id;
  } catch (error) {
    console.error('addAdmin:', error instanceof Error ? error.message : error);
    adminID = (await admins.listAdmins(db)).find(admin => admin.subject === 'e2e-operator')?.id;
  }
  if (!adminID) throw new Error('operator bootstrap failed');
  await admins.setAdminPassword(db, adminID, 'e2e-browser-password');
  console.log(`operator ready (${adminID})`);

  // One completed student attempt (idempotent by email) so the results
  // section has real data: snapshot identity follows migration 042 exactly.
  const weekId = Number(process.env.SEED_WEEK_ID) || (() => {
    const now = new Date();
    const monday = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
    const day = monday.getUTCDay() || 7;
    monday.setUTCDate(monday.getUTCDate() + 4 - day);
    const start = new Date(Date.UTC(monday.getUTCFullYear(), 0, 1));
    return monday.getUTCFullYear() * 100 + Math.ceil(((monday.getTime() - start.getTime()) / 86_400_000 + 1) / 7);
  })();
  const existing = await db.query(`SELECT id FROM accounts WHERE email = 'student@example.test' LIMIT 1`);
  if (existing.rowCount) {
    console.log('student attempt already seeded');
  } else {
  const accountID = randomUUID();
  await db.query('INSERT INTO accounts(id,email,is_guest) VALUES($1,$2,false)', [accountID, 'student@example.test']);
  const content = { languageID: 'en', level: 'A1', weekId, version: 1, thresholds: { overall: 60 },
    source: 'llm', passages: [], readingPassageIds: [], listeningPassageIds: [],
    items: [{ id: 'i1', section: 'recognition', kind: 'choice', prompt: 'She ___ a doctor.',
      options: ['is', 'are', 'am'], answers: ['is'], explain: 'With she, the verb is is.' }] };
  const hash = createHash('sha256').update(JSON.stringify(content)).digest('hex');
  const examID = randomUUID();
  const metadata = JSON.stringify({ examId: examID, contentId: `sha256:${hash}`, contentHash: hash,
    contentRevision: 1, gradingVersion: 1, source: 'llm' });
  await db.query(`INSERT INTO level_test_attempt_snapshots(account_id,content_id,content_hash,content_json)
    VALUES($1,$2,$3,$4)`, [accountID, `sha256:${hash}`, hash,
    JSON.stringify({ ...content, examId: examID, contentId: `sha256:${hash}`, contentHash: hash })]);
  await db.query(`INSERT INTO level_test_attempt_snapshot_ids(account_id,exam_id,content_id,metadata_json)
    VALUES($1,$2,$3,$4)`, [accountID, examID, `sha256:${hash}`, metadata]);
  const result = { overall: 84, passed: true, nextBand: 'A2', weakSections: ['listening'],
    sections: [{ section: 'recognition', score: 90 }, { section: 'writing', score: 75 }],
    questionReviews: [{ questionId: 'i1', section: 'recognition', response: 'is', correct: true }] };
  await db.query(`INSERT INTO level_test_attempts(account_id,attempt_id,exam_id,state,position,revision,
    started_at,updated_at,completed_at,answers,result,attempt_json)
    VALUES($1,$2,$3,'completed',0,1,now(),now(),now(),'[]'::jsonb,$4::jsonb,'{}'::jsonb)`,
    [accountID, randomUUID(), examID, JSON.stringify(result)]);
  console.log(`student attempt seeded (account ${accountID}, week ${weekId})`);
  }

  // One failed job from the previous week: deterministic red row plus a real
  // error dialog to assert, without waiting for the stub to fail.
  await db.query(`INSERT INTO level_test_generation_jobs(language_id,level,week_index,state,attempts,error_code,
      error_details,started_at,finished_at,updated_at)
    VALUES('en','B2',$1 - 1,'failed',2,'generation_failed',$2::jsonb,
      now() - interval '40 minutes', now() - interval '35 minutes', now() - interval '35 minutes')
    ON CONFLICT(language_id,level,week_index) DO UPDATE SET state='failed',attempts=2,error_code='generation_failed',
      error_details=EXCLUDED.error_details,updated_at=EXCLUDED.updated_at`,
  [weekId, JSON.stringify(['reading: only 2 passages (needed 4)', 'writing: empty response'])]);
  console.log(`failed generation job seeded (en B2, week ${weekId - 1})`);
} finally {
  await db.end();
}

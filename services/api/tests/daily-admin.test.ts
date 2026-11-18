import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { normalizeDailyAdminConfig, parseDailyAdminGenerationRequest, parseDailyAdminPackageQuery, parseDailyAdminPublicationRequest,
  parseDailyAdminJobQuery, listDailyAdminJobs, getDailyAdminPackage, getDailyAdminRun, projectDailyAdminPackage, runDailyAdminIdempotent, validateDailyAdminReason } from '../src/daily-admin.js';
import { publishDailyAdminPackage } from '../src/daily-admin.js';

test('daily admin mutation reasons require ten safe trimmed codepoints', () => {
  assert.equal(validateDailyAdminReason('  review daily  '), 'review daily');
  assert.throws(() => validateDailyAdminReason('too short'), /admin_reason_required/);
  assert.throws(() => validateDailyAdminReason('valid\nreason text'), /admin_reason_required/);
});

test('daily admin maps invalid private answer keys to a client error', async () => {
  const fixture = JSON.parse(readFileSync(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));
  const packageId = '11111111-1111-4111-8111-111111111111';
  const adminId = '22222222-2222-4222-8222-222222222222';
  const idempotencyKey = '33333333-3333-4333-8333-333333333333';
  const db = { connect: async () => ({
    query: async (sql: string) => {
      if (sql.includes('FROM daily_admin_request_keys')) return { rows: [] };
      if (sql.includes('FROM daily_packages')) return { rows: [{ id: packageId, language: 'en', editorial_date: fixture.editorialDate,
        state: 'ready', revision: 1, public_payload: fixture, private_payload: {}, content_hash: fixture.contentHash,
        text_hash: 'a'.repeat(64), artifact_hash: 'b'.repeat(64), judge_state: 'passed', judge_text_hash: 'a'.repeat(64),
        judge_artifact_hash: 'b'.repeat(64) }] };
      return { rows: [] };
    }, release: () => undefined,
  }) } as any;
  await assert.rejects(publishDailyAdminPackage(db, adminId, { packageVersionId: packageId, artifactHash: 'b'.repeat(64),
    expectedRevision: 1, reason: 'Publish a test package' }, idempotencyKey), error =>
    error instanceof Error && (error as Error & { code?: string; status?: number }).code === 'invalid_daily_answer_keys' &&
      (error as Error & { status?: number }).status === 400);
});

test('daily admin package projection never returns private payload or answer keys', () => {
  const packageValue = JSON.parse(readFileSync(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));
  packageValue.answer_key = { q1: 'secret' };
  const projected = projectDailyAdminPackage({ id: 'package-id', state: 'ready', private_payload: { answers: { q1: 'secret' }, apiKey: 'secret' },
    public_payload: packageValue });
  const preview = projected.preview as Record<string, unknown>;
  assert.equal('private_payload' in projected, false);
  assert.equal('answer_key' in projected, false);
  assert.equal((preview.topic as Record<string, unknown>).id, packageValue.topic.id);
  assert.equal('answer_key' in preview, false);
  assert.equal(projected.state, 'ready');
});

test('daily admin package detail qualifies provider evidence columns shared with job rows', async () => {
  const fixture = JSON.parse(readFileSync(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));
  const statements: string[] = [];
  const db = { query: async (sql: string) => {
    statements.push(sql);
    return { rows: sql.includes('FROM daily_packages') ? [{ id: '11111111-1111-4111-8111-111111111111', language: 'en',
      editorial_date: '2026-11-07', state: 'ready', revision: 1, public_payload: fixture, private_payload: {},
      content_hash: fixture.contentHash, text_hash: 'a'.repeat(64), artifact_hash: 'b'.repeat(64), judge_state: 'passed',
      judge_text_hash: 'a'.repeat(64), judge_artifact_hash: 'b'.repeat(64), created_at: new Date() }] : [] };
  } } as any;
  await getDailyAdminPackage(db, '11111111-1111-4111-8111-111111111111');
  assert.match(statements[1]!, /SELECT o\.phase,o\.state,o\.result_hash,o\.usage,o\.updated_at/);
});

test('daily admin package listing limits its date window and binds cursors to its filters', () => {
  const base = { language:'en',dateFrom:'2026-11-01',dateTo:'2026-12-01',limit:'20' };
  const query = parseDailyAdminPackageQuery(base);
  assert.equal(query.limit,20);
  assert.throws(()=>parseDailyAdminPackageQuery({...base,dateTo:'2026-12-02'}),/invalid_daily_admin_query/);
  assert.throws(()=>parseDailyAdminPackageQuery({...base,cursor:'not-a-cursor'}),/invalid_daily_admin_query/);
  assert.throws(()=>parseDailyAdminPackageQuery({...base,language:'fr'}),/invalid_daily_admin_query/);
});

test('daily admin job monitor bounds its date range and supported job kinds', () => {
  const query = parseDailyAdminJobQuery({ language: 'en', dateFrom: '2026-11-01', dateTo: '2026-11-30', kind: 'review', limit: '20' });
  assert.deepEqual(query, { language: 'en', dateFrom: '2026-11-01', dateTo: '2026-11-30', kind: 'review', limit: 20 });
  assert.throws(() => parseDailyAdminJobQuery({ language: 'en', dateFrom: '2026-11-01', dateTo: '2027-01-01' }), /invalid_daily_admin_query/);
  assert.throws(() => parseDailyAdminJobQuery({ language: 'fr', dateFrom: '2026-11-01', dateTo: '2026-11-02' }), /invalid_daily_admin_query/);
  assert.throws(() => parseDailyAdminJobQuery({ language: 'en', dateFrom: '2026-11-01', dateTo: '2026-11-02', kind: 'provider' }), /invalid_daily_admin_query/);
});

test('daily admin job monitor returns only bounded operational metadata and aggregate costs', async () => {
  let queryText = '';
  let queryValues: unknown[] = [];
  const db = { query: async (sql: string, values: unknown[] = []) => {
    queryText = sql; queryValues = values;
    return { rows: [{ id: 'run-1', kind: 'content', language: 'en', editorial_date: '2026-11-07', state: 'failed',
      revision: 3, error_code: 'judge_failed', phase: 'judge', reserved_nano_usd: '500',
      settled_nano_usd: '200', created_at: '2026-11-07T04:00:00Z', updated_at: '2026-11-07T04:02:00Z' }] };
  } } as any;
  const page = await listDailyAdminJobs(db, { language: 'en', dateFrom: '2026-11-01', dateTo: '2026-11-07', limit: '25' });
  assert.equal(queryValues[1], '2026-11-01');
  assert.equal(queryValues[2], '2026-11-07');
  assert.match(queryText, /daily_cost_reservations/);
  assert.match(queryText, /jsonb_agg\(jsonb_build_object\('phase',o\.phase,'state',o\.state,'usage',o\.usage,'updated_at',o\.updated_at\)/);
  assert.match(queryText, /ORDER BY j\.created_at DESC/);
  assert.deepEqual(page.items[0], { id: 'run-1', kind: 'content', language: 'en', editorial_date: '2026-11-07', state: 'failed',
    revision: 3, error_code: 'judge_failed', phase: 'judge', reserved_nano_usd: '500', settled_nano_usd: '200',
    created_at: '2026-11-07T04:00:00Z', updated_at: '2026-11-07T04:02:00Z', provider_operations: [] });
  assert.equal('attempt_id' in page.items[0]!, false);
});

test('daily admin job history exposes only per-operation provider token evidence', async () => {
  const providerOperations = [{ phase: 'review', state: 'succeeded', usage: { state: 'complete', tokensIn: 81, tokensOut: 24 }, updated_at: '2026-11-08T10:00:00Z' }];
  const db = { query: async () => ({ rows: [{ id: 'run-2', kind: 'review', language: 'en', editorial_date: null, state: 'succeeded',
    revision: 2, error_code: null, phase: 'review', provider_operations: providerOperations, reserved_nano_usd: '0', settled_nano_usd: '0',
    created_at: '2026-11-08T10:00:00Z', updated_at: '2026-11-08T10:00:00Z' }] }) } as any;
  const page = await listDailyAdminJobs(db, { language: 'en', dateFrom: '2026-11-08', dateTo: '2026-11-08', kind: 'review' });
  assert.deepEqual(page.items[0]?.provider_operations, providerOperations);
  assert.equal(JSON.stringify(page.items).includes('prompt'), false);
  assert.equal(JSON.stringify(page.items).includes('learner'), false);
});

test('daily run polling returns its allowlisted provider usage history', async () => {
  const providerOperations = [{ phase: 'review', state: 'succeeded', usage: { state: 'complete', tokensIn: 81, tokensOut: 24, prompt: 'private' }, updated_at: new Date('2026-11-08T10:00:00Z') }];
  const db = { query: async () => ({ rows: [{ id: '11111111-1111-4111-8111-111111111111', state: 'succeeded', provider_operations: providerOperations }] }) } as any;
  const result = await getDailyAdminRun(db, '11111111-1111-4111-8111-111111111111');
  assert.deepEqual(result.provider_operations, [{ phase: 'review', state: 'succeeded',
    usage: null, updated_at: '2026-11-08T10:00:00.000Z' }]);
});

test('daily content run polling binds the generated package without exposing review bindings', async () => {
  const packageVersionId = '22222222-2222-4222-8222-222222222222';
  const db = { query: async (sql: string) => {
    assert.match(sql, /j\.binding_id/);
    return { rows: [{ id: '11111111-1111-4111-8111-111111111111', kind: 'content', binding_id: packageVersionId,
      state: 'succeeded', provider_operations: [] }] };
  } } as any;
  const run = await getDailyAdminRun(db, '11111111-1111-4111-8111-111111111111');
  assert.equal(run.packageVersionId, packageVersionId);
  assert.equal('binding_id' in run, false);

  const reviewDb = { query: async () => ({ rows: [{ id: '33333333-3333-4333-8333-333333333333', kind: 'review',
    binding_id: '44444444-4444-4444-8444-444444444444', state: 'succeeded', provider_operations: [] }] }) } as any;
  const reviewRun = await getDailyAdminRun(reviewDb, '33333333-3333-4333-8333-333333333333');
  assert.equal(reviewRun.packageVersionId, null);
});

test('daily generation run requests use an exact enabled language, UTC date, and audit reason', () => {
  assert.deepEqual(parseDailyAdminGenerationRequest({language:'en',editorialDate:'2026-11-07',reason:'Manual editorial run'}),
    {language:'en',editorialDate:'2026-11-07',reason:'Manual editorial run'});
  assert.throws(()=>parseDailyAdminGenerationRequest({language:'fr',editorialDate:'2026-11-07',reason:'Manual editorial run'}),/invalid_daily_admin_request/);
  assert.throws(()=>parseDailyAdminGenerationRequest({language:'en',editorialDate:'2026-02-30',reason:'Manual editorial run'}),/invalid_daily_admin_request/);
  assert.throws(()=>parseDailyAdminGenerationRequest({language:'en',editorialDate:'2026-11-07',reason:'short'}),/admin_reason_required/);
});

test('daily publication request binds an exact revision, artifact hash, and reason', () => {
  const request={packageVersionId:'11111111-1111-4111-8111-111111111111',artifactHash:'a'.repeat(64),expectedRevision:4,reason:'Reviewed editorial content'};
  assert.equal(parseDailyAdminPublicationRequest(request).expectedRevision,4);
  assert.throws(()=>parseDailyAdminPublicationRequest({...request,artifactHash:'not-a-hash'}),/invalid_daily_admin_request/);
  assert.throws(()=>parseDailyAdminPublicationRequest({...request,expectedRevision:-1}),/invalid_daily_admin_request/);
  assert.throws(()=>parseDailyAdminPublicationRequest({...request,extra:'unapproved'}),/invalid_daily_admin_request/);
});

test('daily admin idempotency replays the first response and rejects key reuse with a different body', async () => {
  let stored: { request_hash: string; response: unknown } | undefined;
  const db = { connect: async () => {
    const client = { query: async (sql: string, values: unknown[] = []) => {
      if (sql === 'BEGIN' || sql === 'COMMIT' || sql === 'ROLLBACK') return { rows: [], rowCount: 0 };
      if (sql.includes('pg_advisory_xact_lock')) return { rows: [], rowCount: 1 };
      if (sql.includes('SELECT request_hash,response FROM daily_admin_request_keys'))
        return { rows: stored ? [stored] : [], rowCount: stored ? 1 : 0 };
      if (sql.includes('INSERT INTO daily_admin_request_keys')) {
        stored = { request_hash: String(values[3]), response: JSON.parse(String(values[4])) };
        return { rows: [], rowCount: 1 };
      }
      throw new Error(`unexpected query: ${sql}`);
    }, release() {} };
    return client as any;
  } } as any;
  let effects = 0;
  const body = { expectedRevision: 0, readEnabled: false };
  const first = await runDailyAdminIdempotent(db, '11111111-1111-4111-8111-111111111111', 'config_update',
    '22222222-2222-4222-8222-222222222222', body, async () => ({ revision: ++effects }));
  const replay = await runDailyAdminIdempotent(db, '11111111-1111-4111-8111-111111111111', 'config_update',
    '22222222-2222-4222-8222-222222222222', body, async () => ({ revision: ++effects }));
  assert.deepEqual(first, { response: { revision: 1 }, replayed: false });
  assert.deepEqual(replay, { response: { revision: 1 }, replayed: true });
  await assert.rejects(runDailyAdminIdempotent(db, '11111111-1111-4111-8111-111111111111', 'config_update',
    '22222222-2222-4222-8222-222222222222', { ...body, readEnabled: true }, async () => ({ revision: ++effects })),
  /idempotency_conflict/);
  assert.equal(effects, 1);
});

test('daily config accepts only explicit English feature flags and non-negative integer budgets', () => {
  const value=normalizeDailyAdminConfig({schemaVersion:1,expectedRevision:0,readEnabled:false,newAttemptsEnabled:false,
    generationEnabled:false,autoPublishEnabled:false,budgetEnforced:false,enabledLanguages:['en'],contentBudgetNanoUsd:'0',reviewBudgetNanoUsd:'0',retentionDays:365});
  assert.equal(value.autoPublishEnabled,false);
  assert.equal(value.budgetEnforced,false);
  assert.deepEqual(value.enabledLanguages,['en']);
});

test('daily config rejects unsupported languages, budget floats, stale revisions, and missing budget enforcement', () => {
  const base={schemaVersion:1,expectedRevision:4,readEnabled:true,newAttemptsEnabled:true,generationEnabled:true,
    autoPublishEnabled:false,budgetEnforced:true,enabledLanguages:['en'],contentBudgetNanoUsd:'500',reviewBudgetNanoUsd:'400',retentionDays:365};
  assert.throws(()=>normalizeDailyAdminConfig({...base,enabledLanguages:['en','fr']}),/invalid/i);
  assert.throws(()=>normalizeDailyAdminConfig({...base,reviewBudgetNanoUsd:'1.5'}),/invalid/i);
  assert.throws(()=>normalizeDailyAdminConfig({...base,expectedRevision:-1}),/invalid/i);
  assert.throws(()=>normalizeDailyAdminConfig((({...base,budgetEnforced:undefined}) as Record<string,unknown>)),/invalid/i);
});

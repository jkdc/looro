import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Database } from '../../db.js';
import { transaction } from '../../db.js';
import { ServiceError } from '../../errors.js';
import { recordAdminAction } from '../../admin-audit.js';
import { approveGeneratedWeek, rejectGeneratedWeek, retireGeneratedWeek } from '../../level-tests-publication.js';
import { levelTestGenerationStatus, levelTestWeekDetail, listLevelTestDrafts, listLevelTestWeeks,
  parseWeekListQuery, planGenerationLaunch, runGenerationLaunch, validateRegenerateRequest } from '../../level-tests-admin.js';
import { runLevelTestGenerationJob } from '../../level-tests-generation-jobs.js';
import { weekIndex } from '../../level-tests.js';
import type { LevelTestSchedulerConfig } from '../../level-tests-scheduler.js';

interface AdminLevelTestRouteDependencies {
  db: Database;
  env: NodeJS.ProcessEnv;
  assertPageOrigin: (request: FastifyRequest) => void;
  assertCsrf: (request: FastifyRequest) => void;
  authenticateAdmin: (request: FastifyRequest) => Promise<{ adminID: string }>;
  generationStatus?: { config: LevelTestSchedulerConfig; isStarted: () => boolean };
}

const objectBody = (request: FastifyRequest): Record<string, unknown> => {
  if (!request.body || typeof request.body !== 'object' || Array.isArray(request.body) || Buffer.isBuffer(request.body))
    throw new ServiceError('invalid_request');
  return request.body as Record<string, unknown>;
};
const stringField = (body: Record<string, unknown>, field: string, max = 1024): string => {
  const value = body[field];
  if (typeof value !== 'string' || !value || value.length > max) throw new ServiceError('invalid_request');
  return value;
};
const uuid = (value: string): string => {
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value))
    throw new ServiceError('invalid_request');
  return value;
};

/** Routes which launch or inspect generation weeks; registered on the root app. */
export function registerAdminLevelTestGenerationRoutes(app: FastifyInstance,
  { db, env, assertPageOrigin, assertCsrf, authenticateAdmin }: AdminLevelTestRouteDependencies): void {
  app.get('/v1/admin/level-tests/weeks', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const listing = await listLevelTestWeeks(db, parseWeekListQuery(request.query as Record<string, unknown>));
    reply.header('cache-control', 'no-store');
    return listing;
  });
  app.get('/v1/admin/level-tests/weeks/:id', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const week = await levelTestWeekDetail(db, uuid((request.params as { id: string }).id));
    reply.header('cache-control', 'no-store');
    if (!week) throw new ServiceError('level_test_week_not_found', 404);
    return week;
  });
  app.post('/v1/admin/level-tests/regenerate', { bodyLimit: 4096 }, async (request, reply) => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request), body = objectBody(request);
    if (Object.keys(body).some(key => !['languageID', 'level', 'weekIndex', 'reason'].includes(key)))
      throw new ServiceError('invalid_request');
    const languageID = stringField(body, 'languageID', 8), level = stringField(body, 'level', 4);
    const reason = stringField(body, 'reason', 500);
    if (reason.trim().length < 3) throw new ServiceError('invalid_request');
    if (body.weekIndex !== undefined && (typeof body.weekIndex !== 'number' || !Number.isSafeInteger(body.weekIndex)))
      throw new ServiceError('invalid_request');
    const weekId = body.weekIndex === undefined ? weekIndex(new Date()) : body.weekIndex as number;
    await validateRegenerateRequest(db, languageID, level, weekId);
    await transaction(db, async sql => recordAdminAction(sql, admin.adminID, 'level_test.regenerate',
      `${languageID}:${level}:${weekId}`, reason, { languageID, level, weekId }));
    // Fire-and-forget remains intentional: the job module serializes and records failure.
    void runLevelTestGenerationJob(db, env, { languageID, level, weekIndex: weekId, force: true, origin: 'user_operator' })
      .catch(() => undefined);
    return reply.code(202).send({ started: true });
  });
  app.post('/v1/admin/level-tests/generate', { bodyLimit: 4096 }, async (request, reply) => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request), body = objectBody(request);
    if (Object.keys(body).some(key => !['languageID', 'levels', 'weekIndex', 'reason'].includes(key)))
      throw new ServiceError('invalid_request');
    const languageID = stringField(body, 'languageID', 8);
    const reason = stringField(body, 'reason', 500);
    if (reason.trim().length < 3) throw new ServiceError('invalid_request');
    if (body.weekIndex !== undefined && (typeof body.weekIndex !== 'number' || !Number.isSafeInteger(body.weekIndex)))
      throw new ServiceError('invalid_request');
    const weekId = body.weekIndex === undefined ? weekIndex(new Date()) : body.weekIndex as number;
    const levels = body.levels === undefined || body.levels === 'all'
      ? ['A1', 'A2', 'B1', 'B2', 'C1']
      : Array.isArray(body.levels) ? body.levels.map(level => String(level)) : [String(body.levels)];
    const keys = await planGenerationLaunch(db, languageID, levels, weekId);
    await transaction(db, async sql => recordAdminAction(sql, admin.adminID, 'level_test.generate',
      `${languageID}:${weekId}`, reason, { languageID, weekId, keys }));
    runGenerationLaunch(db, env, keys, weekId);
    return reply.code(202).send({ started: true, keys });
  });
}

/** Review and moderation routes retain their existing publication boundaries. */
export function registerAdminLevelTestReviewRoutes(app: FastifyInstance,
  { db, generationStatus, assertPageOrigin, assertCsrf, authenticateAdmin }: AdminLevelTestRouteDependencies): void {
  app.get('/v1/admin/level-tests/status', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const result = await levelTestGenerationStatus(db, generationStatus);
    reply.header('cache-control', 'no-store');
    return result;
  });
  app.get('/v1/admin/level-tests/drafts', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const result = await listLevelTestDrafts(db, request.query as Record<string, unknown>);
    reply.header('cache-control', 'no-store');
    return result;
  });
  app.post('/v1/admin/level-tests/:generationId/reject', { bodyLimit: 1024 }, async request => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request), body = objectBody(request);
    if (Object.keys(body).some(key => key !== 'reason')) throw new ServiceError('invalid_request');
    const id = uuid((request.params as { generationId: string }).generationId);
    return rejectGeneratedWeek(db, id, admin.adminID, stringField(body, 'reason', 500));
  });
  app.post('/v1/admin/level-tests/:generationId/approve', { bodyLimit: 1024 }, async request => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request), body = objectBody(request);
    if (Object.keys(body).some(key => key !== 'reason')) throw new ServiceError('invalid_request');
    const id = uuid((request.params as { generationId: string }).generationId);
    return approveGeneratedWeek(db, id, admin.adminID, stringField(body, 'reason', 500));
  });
  app.post('/v1/admin/level-tests/:generationId/retire', { bodyLimit: 1024 }, async request => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request), body = objectBody(request);
    if (Object.keys(body).some(key => key !== 'reason')) throw new ServiceError('invalid_request');
    const id = uuid((request.params as { generationId: string }).generationId);
    return retireGeneratedWeek(db, id, admin.adminID, stringField(body, 'reason', 500));
  });
}

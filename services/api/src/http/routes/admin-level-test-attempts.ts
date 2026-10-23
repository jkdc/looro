import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Database } from '../../db.js';
import { ServiceError } from '../../errors.js';
import { attemptCsv, levelTestAttemptDetail, levelTestAttemptSummary, listLevelTestAttempts,
  parseAttemptListQuery } from '../../level-tests-attempts-admin.js';

export interface AdminLevelTestAttemptRouteDependencies {
  db: Database;
  assertPageOrigin: (request: FastifyRequest) => void;
  authenticateAdmin: (request: FastifyRequest) => Promise<unknown>;
}

const accountID = (value: string): string => {
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value))
    throw new ServiceError('invalid_request');
  return value;
};

export function registerAdminLevelTestAttemptRoutes(app: FastifyInstance,
  { db, assertPageOrigin, authenticateAdmin }: AdminLevelTestAttemptRouteDependencies): void {
  // Keep origin validation before session authentication, matching the original handlers.
  app.get('/v1/admin/level-test-attempts', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const listing = await listLevelTestAttempts(db, parseAttemptListQuery(request.query as Record<string, unknown>));
    reply.header('cache-control', 'no-store');
    return listing;
  });

  app.get('/v1/admin/level-test-attempts/summary', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const query = request.query as Record<string, unknown>;
    const group = query.group === undefined ? undefined : String(query.group);
    if (group !== undefined && group !== 'week' && group !== 'level') throw new ServiceError('invalid_request');
    reply.header('cache-control', 'no-store');
    return levelTestAttemptSummary(db, {
      language: query.language === undefined ? undefined : String(query.language),
      level: query.level === undefined ? undefined : String(query.level),
      group: group as 'week' | 'level' | undefined });
  });

  app.get('/v1/admin/level-test-attempts/export', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const csv = await attemptCsv(db, parseAttemptListQuery(request.query as Record<string, unknown>));
    reply.header('cache-control', 'no-store');
    if (csv === undefined) throw new ServiceError('export_too_large', 413);
    reply.header('content-type', 'text/csv; charset=utf-8');
    reply.header('content-disposition', 'attachment; filename=level-test-attempts.csv');
    return csv;
  });

  app.get('/v1/admin/level-test-attempts/:accountId/:attemptId', async (request, reply) => {
    assertPageOrigin(request);
    await authenticateAdmin(request);
    const attempt = await levelTestAttemptDetail(db,
      accountID((request.params as { accountId: string }).accountId), (request.params as { attemptId: string }).attemptId);
    reply.header('cache-control', 'no-store');
    if (!attempt) throw new ServiceError('level_test_attempt_not_found', 404);
    return attempt;
  });
}

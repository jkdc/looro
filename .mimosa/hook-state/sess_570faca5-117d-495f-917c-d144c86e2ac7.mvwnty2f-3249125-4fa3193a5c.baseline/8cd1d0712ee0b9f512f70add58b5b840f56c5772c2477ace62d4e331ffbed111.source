import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Database } from '../../db.js';
import { transaction } from '../../db.js';
import { ServiceError } from '../../errors.js';
import { recordAdminAction } from '../../admin-audit.js';
import { ConfigError, LLM_PROVIDER_CATALOG, readLlmConfigView, storedConfigForTest, upsertLlmConfig } from '../../llm-config-admin.js';

interface AdminLevelTestLlmRouteDependencies {
  db: Database;
  env: NodeJS.ProcessEnv;
  assertCsrf: (request: FastifyRequest) => void;
  authenticateAdmin: (request: FastifyRequest) => Promise<{ adminID: string }>;
  testTimeoutMs?: number;
}

const objectBody = (request: FastifyRequest): Record<string, unknown> => {
  if (!request.body || typeof request.body !== 'object' || Array.isArray(request.body) || Buffer.isBuffer(request.body))
    throw new ServiceError('invalid_request');
  return request.body as Record<string, unknown>;
};

/** LLM settings routes are separate from HTTP composition but remain on its root Fastify instance. */
export function registerAdminLevelTestLlmConfigRoutes(app: FastifyInstance,
  { db, env, assertCsrf, authenticateAdmin, testTimeoutMs }: AdminLevelTestLlmRouteDependencies): void {
  app.get('/v1/admin/llm-config', async request => {
    await authenticateAdmin(request);
    const view = await readLlmConfigView(db, env);
    return { ...view, providers: LLM_PROVIDER_CATALOG };
  });
  app.put('/v1/admin/llm-config', { bodyLimit: 8192 }, async (request, reply) => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request), body = objectBody(request);
    const allowed = ['provider', 'baseUrl', 'model', 'apiKey', 'label', 'publishMode', 'verifyMode', 'variants'];
    if (Object.keys(body).some(key => !allowed.includes(key))) throw new ServiceError('invalid_request');
    try {
      const result = await upsertLlmConfig(db, env, body as Record<string, unknown>);
      await transaction(db, async sql => recordAdminAction(sql, admin.adminID, 'llm_config.update',
        'llm_provider_configs', typeof body.provider === 'string' ? body.provider : 'settings',
        { keys: Object.keys(body) }));
      return reply.send(result);
    } catch (error) {
      if (error instanceof ConfigError) throw new ServiceError(error.code, error.status);
      throw error;
    }
  });
  app.post('/v1/admin/llm-config/test', { bodyLimit: 4096 }, async (request, reply) => {
    assertCsrf(request);
    const admin = await authenticateAdmin(request);
    try {
      const config = await storedConfigForTest(db, env);
      const auditOutcome = async (outcome: string, status: number | null) => {
        try {
          await transaction(db, async sql => recordAdminAction(sql, admin.adminID, 'llm_config.test',
            config.provider, `Connection test ${outcome}`, { provider: config.provider, model: config.model, outcome, status }));
        } catch { throw new ServiceError('admin_audit_unavailable', 503); }
      };
      const controller = new AbortController();
      let timedOut = false;
      const timeoutMs = Math.min(testTimeoutMs ?? 8_000, 30_000);
      const timer = setTimeout(() => { timedOut = true; controller.abort(new Error('llm_test_timeout')); }, timeoutMs);
      try {
        const providerReply = await fetch(`${config.baseUrl.replace(/\/$/, '')}/chat/completions`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}`,
            'x-opencode-session': request.id, 'user-agent': 'palique-api/1.0' },
          body: JSON.stringify({ model: config.model, messages: [{ role: 'user', content: 'Reply with OK.' }] }),
          signal: controller.signal,
        });
        const outcome = { ok: providerReply.ok, status: providerReply.status,
          detail: providerReply.ok ? undefined : (await providerReply.text()).slice(0, 500) };
        controller.abort(new Error('llm_test_complete'));
        void providerReply.body?.cancel().catch(() => undefined);
        await auditOutcome(providerReply.ok ? 'ok' : 'provider_rejected', providerReply.status);
        return reply.send(outcome);
      } catch {
        const outcome = timedOut ? 'timeout' : 'unreachable';
        await auditOutcome(outcome, null);
        return reply.code(502).send({ ok: false, error: timedOut ? 'llm_test_timeout' : 'llm_unreachable' });
      } finally { clearTimeout(timer); }
    } catch (error) {
      if (error instanceof ConfigError) throw new ServiceError(error.code, error.status);
      if (error instanceof ServiceError) throw error;
      return reply.code(502).send({ ok: false, error: 'llm_unreachable' });
    }
  });
}

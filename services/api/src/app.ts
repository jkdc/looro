import { context, SpanKind, SpanStatusCode, trace, type ContextManagementToken, type Span } from '@opentelemetry/api';
import Fastify, { type FastifyRequest } from 'fastify';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { accountProfile, authenticate, bearerHash, createChallenge, deleteAccount, exchangeIdentity, hasGoogleSignIn, signOut, verifyIdentity, type AppleRevoker, type AuthConfig } from './auth.js';
import { HelperSessionLimitError, ServiceError } from './errors.js';
import { registerLevelTestAttemptRoutes } from './level-test-attempts.js';
import { applyStripeEvent, STRIPE_WEBHOOK_PATH, type SandboxPayments } from './payments.js';
import { RATE_VERSION } from './pricing.js';
import { aiPricingPolicy } from './ai-top-up-pricing.js';
import { conversationBalance } from './conversation-balance.js';
import { trialEligibility, UnconfiguredAttestor, type TrialAttestor } from './trial.js';
import type { HostedVoice } from './hosted-voice.js';
import { ACCESS_REQUEST_PATH, isPrivateAddress, trustedClientNetwork, type AccessRequests } from './access-requests.js';
import { TokenProxy } from './token-proxy.js';
import type { AuthAdmission } from './auth-admission.js';
import { claimWelcomeMinutes, minuteBalance, UnconfiguredMinuteAttestor, type MinuteAttestor } from './minutes.js';
import { startGuestMinutes, linkGuestMinutes, UnconfiguredGuestMinuteAttestor, type GuestMinuteAttestor } from './guest-minutes.js';
import { AI_REPORT_BODY_LIMIT, AI_REPORT_PATH, reportNetwork, type AIReports } from './feedback.js';
import { stripeOrderByKey, type MinutePurchases } from './minute-purchases.js';
import type { AIValuePurchases, PurchaseFulfillmentRouter } from './ai-value-purchases.js';
import type { PackPurchases } from './pack-products.js';
import { STRIPE_MINUTES_WEBHOOK_PATH, STRIPE_WEBHOOK_BODY_LIMIT, type StripeMinuteProvider } from './stripe-minute-provider.js';
import type { PlayMinuteProvider } from './play-minute-provider.js';
import { HOSTED_HELPER_BODY_LIMIT, type HostedHelpers } from './hosted-helpers.js';
import { startupDiagnostic, type StartupDiagnostic } from './startup-diagnostics.js';
import { adminAuthenticate, adminClearCookieHeader, adminCookieHeader, assertAdminOrigin,
  exchangeSessionToken, adminSignOut, localAdminLogin, type AdminAuthServices } from './admin-auth.js';
import { BROWSE_TABLES, buildBrowseQuery, browseSchema } from './admin-browse.js';
import { snapshot as adminSnapshot, timeseries, usageOverview, TIMESERIES_SETS } from './admin-metrics.js';
import { recordAdminAction } from './admin-audit.js';
import { welcomePolicy, updateWelcomePolicy, prepareMinuteCampaign, applyMinuteCampaign } from './minutes-admin.js';
import { accountCloseoutReport } from './account-closeout-admin.js';
import { normalizeAccessEmail } from './access-requests.js';
import { subscriptionCatalog, subscriptionConfig, subscriptionTiers, runDripForAccount, activeSubscription,
  updateSubscriptionConfig } from './subscriptions.js';
import { USAGE_EVENTS_BODY_LIMIT, USAGE_EVENTS_PATH, ingestUsageEvents } from './usage.js';
import { composeWeekly, levelTestLevels } from './level-tests.js';
import { approvedWeeklyLevelTest } from './level-tests-llm.js';
import { registerAdminLevelTestGenerationRoutes, registerAdminLevelTestReviewRoutes } from './http/routes/admin-level-tests.js';
import { registerAdminLevelTestAttemptRoutes } from './http/routes/admin-level-test-attempts.js';
import { log } from './log.js';
import { registerAdminLevelTestLlmConfigRoutes } from './http/routes/admin-level-test-llm-config.js';
import { paliqueMetrics } from './metrics.js';
import { PROGRESS_BODY_LIMIT, PROGRESS_PATH, mergeProgress, mergeProgressV2, listProgress, listProgressV2 } from './progress.js';
import type { SubscriptionBilling } from './subscription-billing.js';
import type { LevelTestSchedulerConfig } from './level-tests-scheduler.js';

export interface Services { db: Database; auth: AuthConfig; payments?: SandboxPayments; attestor?: TrialAttestor; minuteAttestor?: MinuteAttestor; guestMinuteAttestor?: GuestMinuteAttestor; appleRevoker?: AppleRevoker; hosted?: HostedVoice; accessRequests?: AccessRequests; aiReports?: AIReports;
  onStartupDiagnostic?: (diagnostic: StartupDiagnostic) => void | Promise<void>;
  hostedHelpers?: HostedHelpers;
  minuteCommerce?: { purchases: MinutePurchases; aiPurchases?: AIValuePurchases; packPurchases?: PackPurchases; fulfillment?: PurchaseFulfillmentRouter;
    stripe?: StripeMinuteProvider; play?: PlayMinuteProvider };
  accounts?: { admission: AuthAdmission; identityVerifier?: typeof verifyIdentity },
  tokenProxy?: TokenProxy,
  subscriptions?: SubscriptionBilling,
  admin?: { services: AdminAuthServices; admission: AuthAdmission };
  generationStatus?: { config: LevelTestSchedulerConfig; isStarted: () => boolean };
  llmTestTimeoutMs?: number;
  environment?: NodeJS.ProcessEnv }
const accountPaths = new Set(['/v1/auth/challenge', '/v1/auth/exchange', '/v1/auth/sign-out', '/v1/account', '/v1/wallet', '/v1/minutes/welcome', '/v1/minutes/link-guest',
  '/v1/minutes/orders', '/v1/minutes/orders/by-key/:key', '/v1/minutes/orders/:id', '/v1/minutes/orders/:id/play', '/v1/minutes/play/recover',
  '/v1/subscriptions/plans', '/v1/subscriptions/mine', '/v1/subscriptions/checkout']);
// These routes carry no client network identity: requiring trusted-proxy headers would 503 them,
// and behind a proxy they all share one socket address, so they also skip the shared limiter.
const proxyExemptPaths = new Set(['/payment-return', '/readyz', '/v1/pricing', '/v1/minutes/products', '/v1/level-tests/current', '/v1/level-tests/generated', '/v1/level-tests/hybrid']);
const objectBody = (request: FastifyRequest): Record<string, unknown> => {
  if (!request.body || typeof request.body !== 'object' || Array.isArray(request.body) || Buffer.isBuffer(request.body)) throw new ServiceError('invalid_request');
  return request.body as Record<string, unknown>;
};
const stringField = (body: Record<string, unknown>, field: string, max = 1024) => {
  const value = body[field];
  if (typeof value !== 'string' || !value || value.length > max) throw new ServiceError('invalid_request');
  return value;
};
const uuid = (text: string) => {
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(text)) throw new ServiceError('invalid_request');
  return text;
};
// Per-request span hangs off the request object via a symbol: it never collides
// with Fastify's own request keys and cannot leak into serialized bodies.
const REQUEST_SPAN = Symbol('request-span');
const REQUEST_CONTEXT_TOKEN = Symbol('request-context-token');
const REQUEST_ERROR_CODE = Symbol('request-error-code');
const requestRoute = (request: FastifyRequest): string => request.routeOptions.url ?? 'unmatched';

export function createApp(services: Services) {
  const { db } = services;
  const environment = services.environment ?? process.env;
  const orderStatus = async (account: string, id: string) => {
    const commerce = services.minuteCommerce;
    if (!commerce) throw new ServiceError('minute_purchases_unavailable', 503);
    // Same pre-lookup filter as MinutePurchases.status()/AIValuePurchases.status(): a soft-deleted
    // account's orders must not route, so lookups report purchase_not_found instead.
    const kind = (await db.query(`SELECT o.entitlement_kind FROM minute_purchase_orders o JOIN accounts a ON a.id=o.account_id
      WHERE o.id=$1 AND o.account_id=$2 AND a.deleted_at IS NULL`, [id, account])).rows[0]?.entitlement_kind;
    if (kind === 'ai_value') {
      if (!commerce.aiPurchases) throw new ServiceError('ai_value_purchases_unavailable', 503);
      return commerce.aiPurchases.status(account, id);
    }
    if (kind === 'byok_unlock' || kind === 'token_pack') {
      if (!commerce.packPurchases) throw new ServiceError('pack_purchases_unavailable', 503);
      return commerce.packPurchases.status(account, id);
    }
    return commerce.purchases.status(account, id);
  };
  const app = Fastify({ logger: false, bodyLimit: 262_144, routerOptions: { maxParamLength: 128 },
    requestTimeout: 15_000, trustProxy: false, genReqId: () => randomUUID() });
  // No request bodies, Authorization headers, tokens, transcripts, or Stripe payloads are logged.
  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'buffer' }, (request, body, done) => {
    if ([STRIPE_WEBHOOK_PATH, STRIPE_MINUTES_WEBHOOK_PATH].includes(request.routeOptions.url ?? '')) return done(null, body);
    try { done(null, JSON.parse(body.toString())); } catch { done(new ServiceError('invalid_json')); }
  });
  const windows = new Map<string, { until: number; count: number }>();
  app.addHook('onRequest', async (request, reply) => {
    const span = trace.getTracer('palique-api').startSpan(`${request.method} ${requestRoute(request)}`, {
      kind: SpanKind.SERVER,
      attributes: { 'http.request.method': request.method, 'http.route': requestRoute(request),
        'palique.request_id': request.id },
    });
    (request as unknown as Record<symbol, Span | undefined>)[REQUEST_SPAN] = span;
    const contextToken = context.attach(trace.setSpan(context.active(), span));
    (request as unknown as Record<symbol, ContextManagementToken | undefined>)[REQUEST_CONTEXT_TOKEN] = contextToken;
    reply.header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff');
    // Fastify decodes static route names. Security checks must use the matched route too.
    const path = request.routeOptions.url ?? request.url.split('?')[0]!;
    if (path === '/v1/guest/minutes' && services.guestMinuteAttestor?.requiresTrustedAdmission) {
      if (!services.accounts) throw new ServiceError('guest_minutes_unavailable', 503);
      try { await services.accounts.admission.enter('guest', request.headers, request.raw.socket.remoteAddress ?? request.ip); }
      catch (error) {
        if (error instanceof ServiceError) { if (error.status === 429) reply.header('Retry-After', '3600'); throw error; }
        throw new ServiceError('guest_minutes_unavailable', 503);
      }
      return;
    }
    if (accountPaths.has(path)) {
      if (!services.accounts || (!hasGoogleSignIn(services.auth) && !(services.auth.appleClientID && services.appleRevoker))) throw new ServiceError('accounts_unavailable', 503);
      try {
        await services.accounts.admission.enter(path === '/v1/auth/challenge' ? 'challenge' : path === '/v1/auth/exchange' ? 'exchange' : 'account',
          request.headers, request.raw.socket.remoteAddress ?? request.ip);
      } catch (error) {
        if (error instanceof ServiceError) { if (error.status === 429) reply.header('Retry-After', '3600'); throw error; }
        throw new ServiceError('accounts_unavailable', 503);
      }
      return;
    }
    // Admin routes carry their own admission caps and never fall through to the shared limiter.
    if (path.startsWith('/v1/admin/')) {
      if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
      const operation = path === '/v1/admin/auth/challenge' || path === '/v1/admin/auth/exchange' || path === '/v1/admin/auth/session' ? 'admin' : 'admin-api';
      try {
        await services.admin.admission.enter(operation, request.headers, request.raw.socket.remoteAddress ?? request.ip);
      } catch (error) {
        if (error instanceof ServiceError) { if (error.status === 429) reply.header('Retry-After', '3600'); throw error; }
        throw new ServiceError('admin_panel_unavailable', 503);
      }
      return;
    }
    // This endpoint has separate durable admission limits; Caddy's shared address is not its visitor identity.
    if (path === ACCESS_REQUEST_PATH || path === AI_REPORT_PATH || path === '/healthz') return;
    // Signature-authenticated provider webhooks are delivered server-to-server without trusted-proxy
    // headers, and proxy headers would 503 them. They also stay outside the shared in-memory
    // limiter so a client 429 storm cannot delay payment confirmation.
    if (path === STRIPE_WEBHOOK_PATH || path === STRIPE_MINUTES_WEBHOOK_PATH) return;
    // Proxy-exempt public routes share the proxy's single socket address, so they stay outside
    // the shared limiter too: readiness probes, the catalog and payment return must not be
    // strangled by one abusive client. A durable per-client limiter remains follow-up work.
    if (proxyExemptPaths.has(path)) return;
    let networkKey = request.ip;
    const proxy = services.accounts?.admission.config ?? services.accessRequests?.config;
    if (proxy) {
      let network: string;
      // Local development (ACCOUNTS_ALLOW_LOCAL_LOOPBACK / local waitlist origin):
      // a direct loopback client has no proxy to sign its headers, so its socket
      // address IS its network identity. Production never sees loopback clients.
      const remoteAddress = request.raw.socket.remoteAddress ?? request.ip;
      // Lab mode: direct clients without proxy headers are keyed by socket
      // address — loopback, or any private address so a physical device on the
      // same WiFi can participate (the phone arrives from the LAN, not loopback).
      const allowDirect = 'allowLocalLoopback' in proxy && proxy.allowLocalLoopback === true &&
        isPrivateAddress(remoteAddress);
      try { network = trustedClientNetwork(request.headers, remoteAddress, proxy.proxyToken, allowDirect); }
      catch {
        paliqueMetrics.requestsRejected.add(1, { reason: 'trusted_proxy' });
        throw new ServiceError('trusted_proxy_required', 503);
      }
      networkKey = createHmac('sha256', Buffer.from(proxy.hmacKey, 'hex')).update(network).digest('hex');
    }
    const now = Date.now();
    if (windows.size > 10_000) for (const [key, value] of windows) if (value.until < now) windows.delete(key);
    let slot = windows.get(networkKey);
    if (!slot || slot.until < now) {
      if (windows.size >= 20_000) { paliqueMetrics.requestsRejected.add(1, { reason: 'rate_limit' }); throw new ServiceError('rate_limit', 429); }
      slot = { until: now + 60_000, count: 0 }; windows.set(networkKey, slot);
    }
    if (++slot.count > 120) { paliqueMetrics.requestsRejected.add(1, { reason: 'rate_limit' }); throw new ServiceError('rate_limit', 429); }
  });
  app.setErrorHandler((error, request, reply) => {
    const purchaseReconciliation = error && typeof error === 'object' && 'code' in error && 'message' in error && error.code === 'P0001' &&
        error.message === 'minute_purchase_reconciliation_required';
    const candidate = error && typeof error === 'object' && 'statusCode' in error ? error.statusCode : null;
    const status = purchaseReconciliation ? 409 : error instanceof ServiceError ? error.status : typeof candidate === 'number' && candidate >= 400 && candidate < 500 ? candidate : 500;
    const code = purchaseReconciliation ? 'minute_purchase_reconciliation_required' : error instanceof ServiceError ? error.code : status < 500 ? 'invalid_request' : 'service_unavailable';
    const diagnostic = startupDiagnostic(request.method, request.routeOptions.url, request.id, status, code, error);
    if (diagnostic) {
      log.warn('startup_diagnostic', { request_id: request.id, reference: diagnostic.reference,
        operation: diagnostic.operation, status: diagnostic.status, reason: diagnostic.reason });
      reply.header('X-App-Error-Reference', diagnostic.reference);
      try { void Promise.resolve(services.onStartupDiagnostic?.(diagnostic)).catch(() => {}); } catch { /* Diagnostics cannot change a request's outcome. */ }
    }
    if (error instanceof HelperSessionLimitError) {
      if (error.retryable) reply.header('Retry-After', String(Math.ceil(error.retryAfterMilliseconds! / 1000)));
      return reply.code(status).send({ error: { code, retryable: error.retryable,
        ...(error.retryable ? { retryAfterMilliseconds: error.retryAfterMilliseconds } : {}) } });
    }
    // The response hook emits one safe failure event for both thrown errors and
    // direct 5xx replies, without changing their existing bodies.
    if (status >= 500) (request as unknown as Record<symbol, string | undefined>)[REQUEST_ERROR_CODE] = code;
    reply.code(status).send({ error: { code, ...(status >= 500 ? { requestId: request.id } : {}) } });
  });
  app.addHook('onSend', async (request, reply, payload) => {
    if (reply.statusCode >= 500) reply.header('X-Request-Id', request.id);
    return payload;
  });
  app.addHook('onResponse', async (request, reply) => {
    const route = requestRoute(request);
    const durationSeconds = reply.elapsedTime / 1000;
    if (reply.statusCode >= 500) {
      log.error('request_failed', { request_id: request.id, method: request.method, route,
        status: reply.statusCode,
        error_code: (request as unknown as Record<symbol, string | undefined>)[REQUEST_ERROR_CODE] ?? 'server_error' });
    }
    const span = (request as unknown as Record<symbol, Span | undefined>)[REQUEST_SPAN];
    if (span) {
      span.setAttribute('http.response.status_code', reply.statusCode);
      if (reply.statusCode >= 500) span.setStatus({ code: SpanStatusCode.ERROR });
      span.end();
    }
    (request as unknown as Record<symbol, ContextManagementToken | undefined>)[REQUEST_CONTEXT_TOKEN]?.dispose();
    paliqueMetrics.requestDuration.record(durationSeconds, {
      'http.request.method': request.method, 'http.route': route, 'http.response.status_code': String(reply.statusCode) });
    log.info('http_request', { request_id: request.id,
      trace_id: span?.spanContext().traceId ?? undefined,
      method: request.method, route,
      status: reply.statusCode, duration_ms: Math.round(reply.elapsedTime) });
  });
  registerLevelTestAttemptRoutes(app, db);
  const featureState=()=>{
    const hostedVoice=Boolean(services.hosted?.available && (!services.hosted.minuteFunded || services.hostedHelpers));
    const livePayments=['stripe','play'].some(provider=>services.minuteCommerce?.aiPurchases?.products(provider as 'stripe'|'play').some(product=>product.environment==='live'));
    return {hostedVoice,guestMinutes:Boolean(hostedVoice && services.hosted?.publicMinuteAccess && services.guestMinuteAttestor),livePayments};
  };
  app.get('/healthz', async () => ({ ok: true, stage: 'commercial-foundation', ...featureState() }));
  app.get('/readyz', async () => {
    await db.query('SELECT 1'); return { database: true, ...featureState() };
  });
  app.get('/v1/pricing', async () => ({ currency: 'USD', rateVersion: RATE_VERSION, moneyUnit: 'nanoUSD',
    nanoUSDPerDollar: '1000000000', creditNanoUSD: '10000000', serviceFeePercent: (await aiPricingPolicy(db)).serviceFeeBasisPoints / 100,
    voice: { model: 'gpt-live-1', perMinuteNanoUSD: '50000000', billingUnit: 'active-session-seconds' },
    text: { model: 'gpt-5.6-luna', inputPerTokenNanoUSD: '200', cachedInputPerTokenNanoUSD: '20', outputPerTokenNanoUSD: '1200' },
    searchPerCallNanoUSD: '10000000', paymentFees: 'quoted separately at checkout', hostedVoiceAvailable: featureState().hostedVoice,
    consumerUnit: 'prepaid-ai-value', consumerBillingBasis: 'actual-ai-usage', freeAllowanceUnit:'conversation-minutes',
    paidMinuteEstimatesOnly:true,minutePacks: [], minutePurchasesAvailable: false }));
  // Weekly level-verification test (level-tests v2): static composed content,
  // no auth and no user data — the exam is graded on device. Same deterministic
  // rotation as the apps' bundled pools (golden tests pin the equality); an
  // ETag lets cached clients skip the body until the week rolls over.
  app.get('/v1/level-tests/current', async (request, reply) => {
    const query = request.query as Record<string, unknown>;
    if (typeof query.language !== 'string' || typeof query.level !== 'string')
      throw new ServiceError('invalid_request');
    const composed = composeWeekly(query.language, query.level);
    if (!composed) throw new ServiceError('level_test_unavailable', 404);
    const etag = `"${createHash('sha256').update(JSON.stringify(composed)).digest('hex')}"`;
    reply.header('ETag', etag).header('Cache-Control', 'public, max-age=3600');
    if (request.headers['if-none-match'] === etag) return reply.code(304).send();
    return composed;
  });
  // LLM-only: an approved generated week or 404 — never a deterministic fallback.
  const queryVariant = (value: unknown): number => {
    if (typeof value !== 'string' || value === '') return 1 + Math.floor(Math.random() * 3);
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed >= 1 && parsed <= 5 ? parsed : 1;
  };
  app.get('/v1/level-tests/generated', async (request, reply) => {
    const query = request.query as Record<string, unknown>;
    if (typeof query.language !== 'string' || typeof query.level !== 'string')
      throw new ServiceError('invalid_request');
    const variant = queryVariant(query.variant);
    const composed = await approvedWeeklyLevelTest(db, query.language, query.level, variant);
    if (!composed) throw new ServiceError('level_test_not_generated', 404);
    const etag = `"${createHash('sha256').update(JSON.stringify(composed)).digest('hex')}"`;
    reply.header('ETag', etag).header('Cache-Control', 'public, max-age=600');
    if (request.headers['if-none-match'] === etag) return reply.code(304).send();
    return composed;
  });
  // Hybrid: the generated week when one exists, else the deterministic composition.
  app.get('/v1/level-tests/hybrid', async (request, reply) => {
    const query = request.query as Record<string, unknown>;
    if (typeof query.language !== 'string' || typeof query.level !== 'string')
      throw new ServiceError('invalid_request');
    const variant = queryVariant(query.variant);
    const generated = await approvedWeeklyLevelTest(db, query.language, query.level, variant);
    if (generated) {
      const etag = `"${createHash('sha256').update(JSON.stringify(generated)).digest('hex')}"`;
      reply.header('ETag', etag).header('Cache-Control', 'public, max-age=600');
      if (request.headers['if-none-match'] === etag) return reply.code(304).send();
      return { ...generated, source: 'llm' };
    }
    const composed = composeWeekly(query.language, query.level);
    if (!composed) throw new ServiceError('level_test_unavailable', 404);
    const etag = `"${createHash('sha256').update(JSON.stringify(composed)).digest('hex')}"`;
    reply.header('ETag', etag).header('Cache-Control', 'public, max-age=600');
    if (request.headers['if-none-match'] === etag) return reply.code(304).send();
    return { ...composed, source: 'deterministic' };
  });
  app.route({ method: ['POST', 'OPTIONS'], url: ACCESS_REQUEST_PATH, bodyLimit: 1024,
    onRequest: async (request, reply) => {
      const access = services.accessRequests;
      if (!access) throw new ServiceError('access_requests_unavailable', 503);
      const origin = access.allowedOrigin(request.headers.origin);
      reply.header('Access-Control-Allow-Origin', origin).header('Vary', 'Origin');
      if (request.method === 'OPTIONS') {
        const requested = request.headers['access-control-request-headers'];
        if (request.headers['access-control-request-method'] !== 'POST' ||
            (requested !== undefined && (typeof requested !== 'string' || requested.toLowerCase().split(',').some(header => header.trim() !== 'content-type'))))
          throw new ServiceError('invalid_preflight');
        return reply.header('Access-Control-Allow-Methods', 'POST').header('Access-Control-Allow-Headers', 'Content-Type')
          .header('Access-Control-Max-Age', '600').code(204).send();
      }
      // Caddy overwrites these headers. A direct request cannot invent its network address.
      access.clientAddress(request.headers, request.raw.socket.remoteAddress ?? request.ip, origin);
      if (request.headers['content-type']?.split(';')[0]?.trim().toLowerCase() !== 'application/json') throw new ServiceError('invalid_content_type', 415);
    },
    handler: async (request, reply) => {
      const access = services.accessRequests!;
      try {
        const origin = access.allowedOrigin(request.headers.origin);
        await access.submit(request.body, access.clientAddress(request.headers, request.raw.socket.remoteAddress ?? request.ip, origin));
      } catch (error) {
        if (error instanceof ServiceError) {
          if (error.status === 429) reply.header('Retry-After', '3600');
          throw error;
        }
        throw new ServiceError('access_requests_unavailable', 503);
      }
      return reply.code(202).send({ accepted: true });
    }
  });
  app.get('/v1/auth/providers', async () => ({ google: Boolean(services.accounts && services.auth.googleClientID),
    googleAndroid: Boolean(services.accounts && services.auth.googleAndroidServerClientID && services.auth.googleAndroidClientIDs?.length),
    apple: Boolean(services.accounts && services.auth.appleClientID && services.appleRevoker) }));
  // ---- Admin panel ----
  const adminOnly = async (request: FastifyRequest) => {
    if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
    return adminAuthenticate(services.admin.services.db, request.headers);
  };
  const assertAdminCsrf = (request: FastifyRequest) => {
    if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
    assertAdminOrigin(request.headers, services.admin.services.config.allowedOrigin, request.method);
  };
  const assertAdminPageOrigin = (request: FastifyRequest) => {
    if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
    const origin = request.headers.origin;
    if (origin !== undefined && origin !== services.admin.services.config.allowedOrigin)
      throw new ServiceError('admin_origin_required', 403);
  };
  app.post('/v1/admin/auth/challenge', { bodyLimit: 1024 }, async request => {
    assertAdminCsrf(request);
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    // The challenge stays a single-use gate even for password login: it is
    // rate-limited per operator IP and burned on success.
    return createChallenge(db);
  });
  app.post('/v1/admin/auth/login', { bodyLimit: 4_096 }, async (request, reply) => {
    assertAdminCsrf(request);
    if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
    const body = objectBody(request);
    if (Object.keys(body).some(key => !['username', 'password', 'challengeID'].includes(key)))
      throw new ServiceError('invalid_request');
    const result = await localAdminLogin(services.admin.services,
      { username: stringField(body, 'username', 64), password: stringField(body, 'password', 1024) },
      uuid(stringField(body, 'challengeID', 36)));
    reply.header('Set-Cookie', adminCookieHeader(result.token));
    return { adminID: result.adminID };
  });
  app.post('/v1/admin/auth/session', { bodyLimit: 1024 }, async (request, reply) => {
    assertAdminCsrf(request);
    if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
    const result = await exchangeSessionToken(services.admin.services, stringField(objectBody(request), 'bearer', 128));
    reply.header('Set-Cookie', adminCookieHeader(result.token));
    return { adminID: result.adminID };
  });
  app.post('/v1/admin/auth/sign-out', { bodyLimit: 1024 }, async (request, reply) => {
    assertAdminCsrf(request);
    if (!services.admin) throw new ServiceError('admin_panel_unavailable', 503);
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    await adminSignOut(services.admin.services, request.headers);
    reply.header('Set-Cookie', adminClearCookieHeader());
    return { signedOut: true };
  });
  app.get('/v1/admin/me', async request => {
    const admin = await adminOnly(request);
    return { adminID: admin.adminID, email: admin.email };
  });
  app.get('/v1/admin/browse', async request => {
    await adminOnly(request);
    return { tables: browseSchema() };
  });
  app.get('/v1/admin/accounts/:id', async request => {
    await adminOnly(request);
    return accountCloseoutReport(db, uuid((request.params as { id: string }).id));
  });
  const browseSchemaCache = browseSchema();
  app.get('/v1/admin/browse/:table', async request => {
    await adminOnly(request);
    const tableName = (request.params as { table: string }).table;
    if (!(tableName in BROWSE_TABLES)) throw new ServiceError('table_not_browsable', 404);
    const built = buildBrowseQuery(tableName, request.query as Record<string, unknown>);
    const admin = services.admin!;
    const [rows, totals] = await Promise.all([
      admin.services.db.query(built.sql, built.values), admin.services.db.query(built.countSql, built.values)
    ]);
    return { table: tableName, total: Number(totals.rows[0]!.total), limit: built.limit, offset: built.offset,
      columns: browseSchemaCache[tableName], rows: rows.rows };
  });
  app.get('/v1/admin/metrics', async request => {
    await adminOnly(request);
    return adminSnapshot(db);
  });
  app.get('/v1/admin/metrics/timeseries', async request => {
    await adminOnly(request);
    const query = request.query as Record<string, unknown>;
    const set = typeof query.set === 'string' ? query.set : 'overview';
    if (!(TIMESERIES_SETS as readonly string[]).includes(set)) throw new ServiceError('invalid_request');
    const days = query.days === undefined ? 30 : Number(query.days);
    return timeseries(db, set, days);
  });
  app.get('/v1/admin/metrics/usage', async request => {
    await adminOnly(request);
    const query = request.query as Record<string, unknown>;
    return usageOverview(db, query.days === undefined ? 30 : Number(query.days));
  });
  // ---- Audited panel actions (every mutation writes admin_actions) ----
  const adminActor = (admin: { adminID: string; email: string | null }) => `admin:${admin.email ?? admin.adminID}`;
  app.get('/v1/admin/policy/welcome', async request => {
    await adminOnly(request);
    return welcomePolicy(db);
  });
  app.get('/v1/admin/plans', async request => {
    await adminOnly(request);
    return { config: await subscriptionConfig(db), tiers: await subscriptionTiers(db) };
  });
  app.put('/v1/admin/plans', { bodyLimit: 8192 }, async (request, reply) => {
    assertAdminCsrf(request);
    const admin = await adminOnly(request), body = objectBody(request);
    const allowed = ['version', 'reason', 'freeDripEnabled', 'freeDripMinutes', 'freeDripRolloverCapMinutes',
      'trialDays', 'lifetimeDripBudgetMinutes', 'tiers'];
    if (Object.keys(body).some(key => !allowed.includes(key))) throw new ServiceError('invalid_request');
    const reason = stringField(body, 'reason', 500);
    const result = await updateSubscriptionConfig(db, admin.adminID, body as unknown as Parameters<typeof updateSubscriptionConfig>[2]);
    return reply.send(result);
  });
  app.put('/v1/admin/policy/welcome', { bodyLimit: 4096 }, async (request, reply) => {
    assertAdminCsrf(request);
    const admin = await adminOnly(request), body = objectBody(request);
    if (Object.keys(body).some(key => !['version', 'welcomeEnabled', 'welcomeMinutes', 'reason'].includes(key)))
      throw new ServiceError('invalid_request');
    const reason = stringField(body, 'reason', 500);
    const policy = { version: body.version, welcomeEnabled: body.welcomeEnabled, welcomeMinutes: body.welcomeMinutes } as
      Parameters<typeof updateWelcomePolicy>[1];
    const after = await updateWelcomePolicy(db, policy, adminActor(admin), reason,
      (sql: PoolClient) => recordAdminAction(sql, admin.adminID, 'policy.update', 'minute_policy', reason,
        { welcomeEnabled: policy.welcomeEnabled, welcomeMinutes: policy.welcomeMinutes }));
    return reply.send(after);
  });
  app.post('/v1/admin/campaigns', { bodyLimit: 1_048_576 }, async (request, reply) => {
    assertAdminCsrf(request);
    const admin = await adminOnly(request), body = objectBody(request);
    if (Object.keys(body).some(key => !['audience', 'minutesPerUser', 'maxTotalMinutes', 'reason'].includes(key)))
      throw new ServiceError('invalid_request');
    const reason = stringField(body, 'reason', 500);
    const audience = body.audience;
    const campaignID = randomUUID();
    const summary = await prepareMinuteCampaign(db, {
      id: campaignID, actor: adminActor(admin), reason,
      minutesPerUser: body.minutesPerUser as number, maxTotalMinutes: body.maxTotalMinutes as number, audience: audience as 'all-current-users' | string[]
    }, (sql: PoolClient) => recordAdminAction(sql, admin.adminID, 'campaign.prepare', campaignID, reason,
      { minutesPerUser: body.minutesPerUser, maxTotalMinutes: body.maxTotalMinutes,
        audienceSize: Array.isArray(audience) ? audience.length : 'all-current-users' }));
    return reply.code(201).send(summary);
  });
  app.post('/v1/admin/campaigns/:id/apply', { bodyLimit: 4096 }, async (request, reply) => {
    assertAdminCsrf(request);
    const admin = await adminOnly(request), body = objectBody(request);
    const confirmation = stringField(body, 'confirmation', 64), reason = stringField(body, 'reason', 500);
    if (!/^[a-f0-9]{64}$/.test(confirmation)) throw new ServiceError('campaign_confirmation_required');
    let outcome;
    do { outcome = await applyMinuteCampaign(db, uuid((request.params as { id: string }).id), confirmation); }
    while (outcome.pending > 0);
    await transaction(db, async sql => recordAdminAction(sql, admin.adminID, 'campaign.apply',
      (request.params as { id: string }).id, reason, outcome));
    return reply.send(outcome);
  });
  app.delete('/v1/admin/access-requests', { bodyLimit: 4096 }, async request => {
    assertAdminCsrf(request);
    const admin = await adminOnly(request), body = objectBody(request);
    if (Object.keys(body).some(key => !['email', 'reason'].includes(key))) throw new ServiceError('invalid_request');
    const email = normalizeAccessEmail(body.email);
    const reason = stringField(body, 'reason', 500);
    return transaction(db, async sql => {
      const deleted = await sql.query('DELETE FROM access_requests WHERE email=$1', [email]);
      if (!deleted.rowCount) throw new ServiceError('access_request_not_found', 404);
      await recordAdminAction(sql, admin.adminID, 'access_request.delete', email, reason, { email });
      return { deleted: true };
    });
  });
  app.post('/v1/admin/hosted-sessions/:id/close', { bodyLimit: 4096 }, async (request, reply) => {
    assertAdminCsrf(request);
    const admin = await adminOnly(request);
    if (!services.hosted) throw new ServiceError('hosted_voice_not_ready', 503);
    const reason = stringField(objectBody(request), 'reason', 500);
    const sessionID = uuid((request.params as { id: string }).id);
    const outcome = await services.hosted.adminClose(sessionID);
    await transaction(db, async sql => recordAdminAction(sql, admin.adminID, 'hosted_session.close', sessionID, reason,
      { state: outcome.state }));
    return reply.send(outcome);
  });
  const adminLevelTestRouteDependencies = { db, env: environment,
    assertPageOrigin: assertAdminPageOrigin, assertCsrf: assertAdminCsrf,
    authenticateAdmin: adminOnly, generationStatus: services.generationStatus };
  // Generation routes retain their original registration position and root hooks.
  registerAdminLevelTestGenerationRoutes(app, adminLevelTestRouteDependencies);
  // Student exam result reads stay on the root Fastify instance so they inherit
  // the same admission, tracing, and error policies as every other admin route.
  registerAdminLevelTestAttemptRoutes(app, { db, assertPageOrigin: assertAdminPageOrigin, authenticateAdmin: adminOnly });
  registerAdminLevelTestReviewRoutes(app, adminLevelTestRouteDependencies);
  registerAdminLevelTestLlmConfigRoutes(app, { db, env: environment, assertCsrf: assertAdminCsrf,
    authenticateAdmin: adminOnly, testTimeoutMs: services.llmTestTimeoutMs });
  app.get('/v1/feedback/capabilities', async () => ({ aiReports: Boolean(services.aiReports?.config) }));
  app.post(AI_REPORT_PATH, { bodyLimit: AI_REPORT_BODY_LIMIT }, async (request, reply) => {
    const reports = services.aiReports;
    if (!reports?.config) throw new ServiceError('ai_reports_unavailable', 503);
    const network = reportNetwork(request.headers, request.raw.socket.remoteAddress ?? request.ip, reports.config);
    try { return reply.code(202).send(await reports.submit(objectBody(request), network)); }
    catch (error) {
      if (error instanceof ServiceError && error.status === 429) reply.header('Retry-After', '3600');
      throw error;
    }
  });
  app.post('/v1/auth/challenge', { bodyLimit: 1024 }, async request => {
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    return createChallenge(db);
  });
  app.post('/v1/auth/exchange', { bodyLimit: 20_000 }, async request => {
    const body = objectBody(request), provider = stringField(body, 'provider', 10);
    if (Object.keys(body).some(key => !['provider', 'idToken', 'challengeID', 'expectedAccountID'].includes(key))) throw new ServiceError('invalid_request');
    if (provider !== 'google' && provider !== 'apple') throw new ServiceError('invalid_identity_provider');
    // Apple account creation cannot be enabled before account deletion can revoke Apple authorization.
    if (provider === 'apple' && !services.appleRevoker) throw new ServiceError('apple_sign_in_not_ready', 503);
    const expectedAccountID = body.expectedAccountID === undefined ? undefined : uuid(stringField(body, 'expectedAccountID', 36));
    return exchangeIdentity(db, provider, stringField(body, 'idToken', 16_384), uuid(stringField(body, 'challengeID', 36)), services.auth, services.accounts?.identityVerifier, expectedAccountID);
  });
  app.get('/v1/account', async request => accountProfile(db, request.headers.authorization));
  // Content-free usage telemetry from the apps. Guests are included on purpose:
  // onboarding is where the funnel matters. The catalog is closed server-side.
  app.post(USAGE_EVENTS_PATH, { bodyLimit: USAGE_EVENTS_BODY_LIMIT }, async (request, reply) => {
    const account = await authenticate(db, request.headers.authorization, true);
    return reply.code(202).send(await ingestUsageEvents(db, account, objectBody(request)));
  });
  // Per-user learning progress (lessons, themes, exercises, listening). Carries
  // content slugs on purpose; the merge is monotonic so replays are harmless.
  app.post(PROGRESS_PATH, { bodyLimit: PROGRESS_BODY_LIMIT }, async (request, reply) => {
    const account = await authenticate(db, request.headers.authorization, true);
    const body = objectBody(request);
    if (body.version === 2) {
      if (environment.PROGRESS_SYNC_V2_ENABLED !== 'true') throw new ServiceError('progress_sync_version_unavailable', 503);
      return reply.code(202).send(await mergeProgressV2(db, account, body));
    }
    return reply.code(202).send(await mergeProgress(db, account, body));
  });
  app.get(PROGRESS_PATH, async (request, reply) => {
    const account = await authenticate(db, request.headers.authorization, true);
    const query = request.query as Record<string, unknown>;
    if (Object.hasOwn(query, 'version')) {
      if (query.version !== '2') throw new ServiceError('invalid_progress_version');
      if (environment.PROGRESS_SYNC_V2_ENABLED !== 'true') throw new ServiceError('progress_sync_version_unavailable', 503);
      if (Object.keys(query).some(key => !['version', 'cursor'].includes(key)) ||
          (query.cursor !== undefined && typeof query.cursor !== 'string')) throw new ServiceError('invalid_progress_cursor');
      return reply.send(await listProgressV2(db, account, query.cursor));
    }
    return listProgress(db, account);
  });
  app.post('/v1/proxy/responses', { bodyLimit: 32_768 }, async (request, reply) => {
    const proxy = services.tokenProxy;
    if (!proxy) throw new ServiceError('proxy_unavailable', 503);
    const account = await authenticate(db, request.headers.authorization);
    const unlock = (await db.query(`SELECT unlocked_at FROM entitlements WHERE account_id=$1`, [account])).rows[0]?.unlocked_at;
    if (!unlock) throw new ServiceError('unlock_required', 403);
    const body = objectBody(request);
    const { status, payload } = await proxy.relay(account, body);
    reply.code(status);
    return payload;
  });
  app.get('/v1/minutes', async request => {
    const account = await authenticate(db, request.headers.authorization, true);
    // Opportunistic free drip: idempotent per period, cheap, never blocks the read.
    await runDripForAccount(db, account).catch(() => undefined);
    const balance = await conversationBalance(db, account,
      services.hosted?.publicMinuteAccess===true, services.hosted?.publicPaidAccess ? { enabled: true,
        estimatedNanoUSDPerMinute: services.hosted.estimatedNanoUSDPerMinute, minimumSessionNanoUSD: services.hosted.minimumPaidSessionNanoUSD } : undefined);
    const [subscription, catalog] = await Promise.all([activeSubscription(db, account), subscriptionCatalog(db)]);
    const tier = subscription ? subscription.tier : 'free';
    const features = subscription ? catalog.tiers.find(entry => entry.tier === subscription.tier)?.features ?? {} : {};
    return { ...balance, subscription: { tier, status: subscription?.status ?? 'none',
      trialing: subscription?.status === 'trialing', periodEnd: subscription?.periodEnd ?? null,
      features, drip: { enabled: catalog.config.freeDripEnabled, minutesPerMonth: catalog.config.freeDripMinutes } } };
  });
  app.get('/v1/subscriptions/plans', async () => subscriptionCatalog(db));
  app.get('/v1/subscriptions/mine', async request => {
    const account = await authenticate(db, request.headers.authorization);
    const subscription = await activeSubscription(db, account);
    return { subscription: subscription ? { tier: subscription.tier, status: subscription.status,
      store: subscription.store, periodStart: subscription.periodStart, periodEnd: subscription.periodEnd,
      cancelAtPeriodEnd: subscription.cancelAtPeriodEnd } : null };
  });
  app.post('/v1/subscriptions/checkout', { bodyLimit: 1024 }, async (request, reply) => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.subscriptions) throw new ServiceError('subscription_unavailable', 503);
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string' || key.length < 8 || key.length > 128) throw new ServiceError('idempotency_key_required');
    const result = await services.subscriptions.checkout(account, stringField(objectBody(request), 'tier', 16), key);
    reply.header('cache-control', 'no-store');
    return result;
  });
  app.post('/v1/guest/minutes', { bodyLimit: 1024 }, async request => {
    const proof = objectBody(request);
    try { return { available: true, ...await startGuestMinutes(db, proof, services.guestMinuteAttestor ?? new UnconfiguredGuestMinuteAttestor()) }; }
    catch (error) {
      if (error instanceof ServiceError) {
        if (['welcome_minutes_unavailable', 'welcome_funding_budget_reached', 'trial_attestation_unavailable'].includes(error.code))
          return { available: false, reason: 'temporarily_unavailable', remainingMilliseconds: 0 };
        if (['sign_in_to_continue', 'trial_already_claimed'].includes(error.code))
          return { available: false, reason: 'sign_in_required', remainingMilliseconds: 0 };
      }
      throw error;
    }
  });
  app.post('/v1/minutes/link-guest', { bodyLimit: 1024 }, async request => {
    const account = await authenticate(db, request.headers.authorization), body = objectBody(request);
    if (Object.keys(body).some(key => !['guestAccessToken','deferPending','guestAccountID'].includes(key)) ||
      (body.deferPending!==undefined&&typeof body.deferPending!=='boolean')) throw new ServiceError('invalid_request');
    return linkGuestMinutes(db,account,body.guestAccessToken===undefined&&body.deferPending===true?undefined:stringField(body,'guestAccessToken',43),body.deferPending===true,body.guestAccountID===undefined?undefined:uuid(stringField(body,'guestAccountID',36)));
  });
  app.post('/v1/minutes/welcome', { bodyLimit: 20_000 }, async request => {
    const account = await authenticate(db, request.headers.authorization);
    try { return { available: true, ...await claimWelcomeMinutes(db, account, objectBody(request), services.minuteAttestor ?? new UnconfiguredMinuteAttestor()) }; }
    catch (error) {
      if (error instanceof ServiceError && ['welcome_minutes_unavailable', 'welcome_funding_budget_reached', 'trial_attestation_unavailable'].includes(error.code))
        return { available: false, reason: 'temporarily_unavailable', grantedMilliseconds: 0 };
      throw error;
    }
  });
  app.get('/v1/minutes/products', async request => {
    const provider = (request.query as Record<string, unknown>).provider;
    if (provider !== 'stripe' && provider !== 'play') throw new ServiceError('invalid_purchase_provider');
    const commerce = services.minuteCommerce;
    if (commerce?.aiPurchases || commerce?.packPurchases) {
      const products = [...(commerce.aiPurchases?.products(provider) ?? []), ...(commerce.packPurchases?.products(provider) ?? [])];
      return { available: products.length > 0,
        billingBasis: products.length > 0 && products.every(product => product.entitlementKind !== 'ai_value') ? 'prepaid-service-credit' : 'actual-ai-usage',
        products: products.map(({ merchant: _merchant, provider: _provider, ...product }) => product) };
    }
    const products = commerce?.purchases.products(provider) ?? [];
    return { available: products.length > 0, billingBasis: 'connected-conversation-time', products: products.map(product => ({
      sku: product.sku, providerProduct: product.providerProduct, minutes: product.minutes,
      currency: product.currency, totalMinor: product.totalMinor, environment: product.environment
    })) };
  });
  app.post('/v1/minutes/orders', { bodyLimit: 1024 }, async request => {
    const account = await authenticate(db, request.headers.authorization), body = objectBody(request);
    if (Object.keys(body).some(key => !['provider','sku'].includes(key))) throw new ServiceError('invalid_request');
    const provider = stringField(body, 'provider', 10), key = request.headers['idempotency-key'];
    if (provider !== 'stripe' && provider !== 'play') throw new ServiceError('invalid_purchase_provider');
    if (typeof key !== 'string') throw new ServiceError('idempotency_key_required');
    const commerce = services.minuteCommerce;
    if (!commerce || !commerce[provider]) throw new ServiceError('minute_purchases_unavailable', 503);
    const sku = stringField(body, 'sku', 128);
    // A pack SKU is owned by the pack catalog even when pack sales are disabled,
    // so the dedicated unavailable error surfaces instead of a catalog miss.
    const packs = commerce.packPurchases;
    const service = packs && packs.hasProduct(provider, sku) ? packs : (commerce.aiPurchases ?? commerce.purchases);
    const order = await service.createOrder(account, provider, sku, key);
    const payment = provider === 'stripe' ? await commerce.stripe!.checkout(account, order.orderID)
      : await commerce.play!.prepare(account, order.orderID);
    if ('entitlementKind' in order) {
      const { merchant: _merchant, provider: _provider, ...quoted } = order;
      return { ...quoted, payment };
    }
    return { orderID: order.orderID, minutes: order.minutes, currency: order.currency, totalMinor: order.totalMinor, payment };
  });
  app.get('/v1/minutes/orders/by-key/:key', async request => {
    const account = await authenticate(db, request.headers.authorization);
    const query = request.query as Record<string, unknown>;
    if (Object.keys(query).some(key => key !== 'provider')) throw new ServiceError('invalid_request');
    if (query.provider !== 'stripe') throw new ServiceError('invalid_purchase_provider');
    if (!services.minuteCommerce) throw new ServiceError('minute_purchases_unavailable', 503);
    return stripeOrderByKey(db, account, (request.params as { key: string }).key);
  });
  app.get('/v1/minutes/orders/:id', async request => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.minuteCommerce) throw new ServiceError('minute_purchases_unavailable', 503);
    return orderStatus(account, uuid((request.params as { id: string }).id));
  });
  app.post('/v1/minutes/orders/:id/play', { bodyLimit: 8192 }, async request => {
    const account = await authenticate(db, request.headers.authorization), body = objectBody(request);
    if (Object.keys(body).some(key => key !== 'purchaseToken')) throw new ServiceError('invalid_request');
    if (!services.minuteCommerce?.play) throw new ServiceError('minute_purchases_unavailable', 503);
    const orderID = uuid((request.params as { id: string }).id), purchaseToken = stringField(body, 'purchaseToken', 4096);
    if (!/^[\x21-\x7e]+$/.test(purchaseToken)) throw new ServiceError('invalid_request');
    await orderStatus(account, orderID);
    return (services.minuteCommerce.fulfillment ?? services.minuteCommerce.purchases).reconcile('play', { kind: 'client', accountID: account,
      orderID, purchaseToken });
  });
  app.post(STRIPE_MINUTES_WEBHOOK_PATH, { bodyLimit: STRIPE_WEBHOOK_BODY_LIMIT }, async request => {
    if (!services.minuteCommerce?.stripe) throw new ServiceError('minute_purchases_unavailable', 503);
    const signature = request.headers['stripe-signature'];
    if (!Buffer.isBuffer(request.body) || typeof signature !== 'string') throw new ServiceError('invalid_webhook_signature');
    // Subscription events share the endpoint and secret; anything they don't
    // claim falls through to the minutes reconcile path unchanged.
    if (services.subscriptions) {
      const event = services.subscriptions.transport.verifyEvent(request.body, signature);
      if (await services.subscriptions.handleEvent(event)) return { received: true };
    }
    await (services.minuteCommerce.fulfillment ?? services.minuteCommerce.purchases).reconcile('stripe', { kind: 'webhook', raw: request.body, signature });
    return { received: true };
  });
  app.post('/v1/minutes/play/recover', { bodyLimit: 8192 }, async request => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.minuteCommerce?.play) throw new ServiceError('minute_purchases_unavailable', 503);
    const body = objectBody(request);
    if (Object.keys(body).length !== 1 || typeof body.purchaseToken !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(body.purchaseToken))
      throw new ServiceError('invalid_request');
    return (services.minuteCommerce.fulfillment ?? services.minuteCommerce.purchases).reconcile('play', { kind: 'recovery', accountID: account, purchaseToken: body.purchaseToken });
  });
  app.get('/v1/wallet', async request => {
    const wallet = (await db.query(`SELECT w.balance_nano,w.reserved_nano,e.unlocked_at,e.unlock_source FROM auth_sessions s JOIN accounts a ON a.id=s.account_id
      JOIN wallets w ON w.account_id=a.id LEFT JOIN entitlements e ON e.account_id=a.id WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL AND a.deleted_at IS NULL`,
    [bearerHash(request.headers.authorization)])).rows[0];
    if (!wallet) throw new ServiceError('sign_in_required', 401);
    return { currency: 'USD', balanceNanoUSD: wallet.balance_nano, reservedNanoUSD: wallet.reserved_nano,
      availableNanoUSD: (BigInt(wallet.balance_nano) - BigInt(wallet.reserved_nano)).toString(),
      unlocked: Boolean(wallet.unlocked_at), unlockSource: wallet.unlocked_at ? wallet.unlock_source ?? null : null };
  });
  app.post('/v1/auth/sign-out', { bodyLimit: 1024 }, async request => {
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    await signOut(db, request.headers.authorization);
    return { signedOut: true };
  });
  app.delete('/v1/account', { bodyLimit: 5120 }, async request => {
    const account = await authenticate(db, request.headers.authorization);
    const body = objectBody(request);
    if (Object.keys(body).some(key => key !== 'appleAuthorizationCode')) throw new ServiceError('invalid_request');
    const code = body.appleAuthorizationCode === undefined ? undefined : stringField(body, 'appleAuthorizationCode', 4096);
    const result = await deleteAccount(db, account, services.appleRevoker, code, request.headers.authorization);
    return { deleted: true, retained: result.retainedFinancialRecords ? 'Required financial records, linked to an opaque account ID.' : null };
  });
  app.post('/v1/checkout', async request => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.payments) throw new ServiceError('checkout_not_configured', 503);
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string' || key.length < 8 || key.length > 128) throw new ServiceError('idempotency_key_required');
    return services.payments.checkout(db, account, stringField(objectBody(request), 'product', 64), key);
  });
  app.post(STRIPE_WEBHOOK_PATH, { bodyLimit: STRIPE_WEBHOOK_BODY_LIMIT }, async request => {
    if (!services.payments) throw new ServiceError('checkout_not_configured', 503);
    const signature = request.headers['stripe-signature'];
    if (!Buffer.isBuffer(request.body) || typeof signature !== 'string') throw new ServiceError('invalid_webhook_signature');
    const event = services.payments.verify(request.body, signature);
    await applyStripeEvent(db, event);
    return { received: true };
  });
  app.post('/v1/trial/eligibility', async request => trialEligibility(db, request.body, services.attestor ?? new UnconfiguredAttestor()));
  app.get('/v1/live/capabilities', async request => {
    if (!services.hosted?.minuteFunded || !services.hosted.available || !services.hostedHelpers || !request.headers.authorization) return { hostedMinutes: false };
    const account = await authenticate(db, request.headers.authorization, true);
    return { hostedMinutes: services.hosted.allows(account) && services.hostedHelpers.allows(account), experimental: true };
  });
  app.post('/v1/live/sessions', async request => {
    if (!services.hosted?.available) throw new ServiceError('hosted_voice_not_ready', 503);
    if (services.hosted.minuteFunded && !services.hostedHelpers) throw new ServiceError('hosted_helpers_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, services.hosted.minuteFunded), body = objectBody(request);
    if (Object.keys(body).some(key => !['sdp','language','instructions','history','requestedMilliseconds'].includes(key))) throw new ServiceError('invalid_request');
    if (body.requestedMilliseconds !== undefined && (typeof body.requestedMilliseconds !== 'number' ||
        !Number.isSafeInteger(body.requestedMilliseconds) || body.requestedMilliseconds < 60_000 || body.requestedMilliseconds > 3_600_000))
      throw new ServiceError('invalid_request');
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string') throw new ServiceError('idempotency_key_required');
    return services.hosted.create(account, key, stringField(body, 'sdp', 65_536), stringField(body, 'language', 10),
      { instructions: body.instructions, history: body.history }, body.requestedMilliseconds as number | undefined);
  });
  app.get('/v1/live/sessions/:id', async request => {
    if (!services.hosted) throw new ServiceError('hosted_voice_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, services.hosted.minuteFunded);
    return services.hosted.status(account, uuid((request.params as { id: string }).id));
  });
  app.get('/v1/live/sessions/current', async request => {
    if (!services.hosted?.minuteFunded) throw new ServiceError('hosted_voice_not_ready', 503);
    return services.hosted.current(await authenticate(db, request.headers.authorization, true));
  });
  app.post('/v1/live/sessions/:id/close', async request => {
    if (!services.hosted) throw new ServiceError('hosted_voice_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, services.hosted.minuteFunded);
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    return services.hosted.close(account, uuid((request.params as { id: string }).id));
  });
  app.post('/v1/live/sessions/:id/helpers', { bodyLimit: HOSTED_HELPER_BODY_LIMIT }, async request => {
    if (!services.hosted?.minuteFunded || !services.hostedHelpers) throw new ServiceError('hosted_helpers_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, true);
    return services.hostedHelpers.request(account, uuid((request.params as { id: string }).id), request.body);
  });
  app.get('/payment-return', async (_request, reply) => reply.type('text/html').send('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Palique sandbox</title><body><h1>Return to Palique</h1><p>This is a sandbox payment test. The app checks payment confirmation independently.</p></body></html>'));
  return app;
}

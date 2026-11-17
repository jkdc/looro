import { fileURLToPath } from 'node:url';
import { finalizeDeferredGuestLinks } from './guest-minutes.js';
import { createApp } from './app.js';
import { connectDatabase, type Database } from './db.js';
import { createApiLifecycle } from './api-lifecycle.js';
import { catalogFromEnvironment, SandboxPayments } from './payments.js';
import { hasGoogleSignIn, pruneAuthenticationRecords } from './auth.js';
import { readFile } from 'node:fs/promises';
import { AppleTokenRevoker } from './apple-revocation.js';
import { HostedVoice } from './hosted-voice.js';
import { OpenAILiveProvider } from './live-provider.js';
import { AccessRequests, accessRequestConfig, pruneAccessRequests } from './access-requests.js';
import { AuthAdmission, accountAdmissionConfig } from './auth-admission.js';
import { TokenProxy, tokenProxyConfig } from './token-proxy.js';
import { resolveLlmConfig } from './llm-config.js';
import { proxyQuotaConfig, runDailyQuotaGrants } from './proxy-quota.js';
import { adminAuthConfig, pruneAdminRecords, type AdminAuthServices } from './admin-auth.js';
import { AIReports, aiReportConfig, pruneAIReports } from './feedback.js';
import { pruneUsageEvents } from './usage.js';
import { configuredMinuteCommerce } from './minute-commerce-config.js';
import { HostedHelpers } from './hosted-helpers.js';
import { OpenAIHostedResponses } from './hosted-responses-transport.js';
import { InstallationGuestMinuteAttestor } from './guest-minutes.js';
import { SubscriptionBilling } from './subscription-billing.js';
import { createLevelTestScheduler, parseLevelTestSchedulerConfig, runLevelTestCombinations } from './level-tests-scheduler.js';
import { runLevelTestGenerationJob } from './level-tests-generation-jobs.js';
import { createDailyScheduler, type DailyScheduler } from './daily-scheduler.js';
import { createDailyContentWorkerScheduler, type DailyContentWorkerScheduler } from './daily-content-worker-scheduler.js';
import { createDailyReviewWorkerScheduler, type DailyReviewWorkerScheduler } from './daily-review-worker-scheduler.js';
import { log } from './log.js';
import { addDatabasePoolObservability, initGenerationMetrics, initObservability, shutdownObservability, startGenerationMetricsRefresh } from './observability.js';
import { migrate, migrationsOnBootEnabled } from './migrate.js';

export async function main(env: NodeJS.ProcessEnv = process.env): Promise<void> {
  const databaseURL = env.DATABASE_URL;
  if (!databaseURL) { console.error('DATABASE_URL is required.'); process.exit(1); }

  let database: Database | undefined;
  let stopGenerationMetricsRefresh: (() => void) | undefined;
  let hosted: HostedVoice | undefined;
  let hostedHelpers: HostedHelpers | undefined;
  let minuteCommerce: Awaited<ReturnType<typeof configuredMinuteCommerce>> | undefined;
  let generationScheduler: ReturnType<typeof createLevelTestScheduler> | undefined;
  let dailyScheduler: DailyScheduler | undefined;
  let dailyContentWorker: DailyContentWorkerScheduler | undefined;
  let dailyReviewWorker: DailyReviewWorkerScheduler | undefined;
  let tokenProxy: TokenProxy | undefined;
  let app: ReturnType<typeof createApp> | undefined;
  let cleanup: ReturnType<typeof setInterval> | undefined;
  let guestLinkCleanup: ReturnType<typeof setInterval> | undefined;
  let quotaCleanup: ReturnType<typeof setInterval> | undefined;
  let guestLinkFlight: Promise<unknown> | undefined;

  const lifecycle = createApiLifecycle({
    listen: async () => {
      if (!app) throw new Error('API app was not constructed.');
      await app.listen({ port: Number(env.PORT ?? 8080), host: '0.0.0.0' });
    },
    startWorkers: () => {
      minuteCommerce?.runner.start();
      generationScheduler?.start();
      dailyScheduler?.start();
      dailyContentWorker?.start();
      dailyReviewWorker?.start();
      log.info('api_started');
    },
    shutdownSteps: [
      () => {
        if (cleanup) clearInterval(cleanup);
        if (guestLinkCleanup) clearInterval(guestLinkCleanup);
        if (quotaCleanup) clearInterval(quotaCleanup);
        stopGenerationMetricsRefresh?.();
      },
      async () => { await generationScheduler?.stop(); },
      async () => { await dailyContentWorker?.stop(); },
      async () => { await dailyReviewWorker?.stop(); },
      () => { dailyScheduler?.stop(); },
      async () => { await guestLinkFlight; },
      async () => { await tokenProxy?.stop(); },
      async () => { await app?.close(); },
      async () => { await hosted?.stop(); },
      async () => { await minuteCommerce?.runner.stop(); },
      async () => { await shutdownObservability(); },
      async () => { await database?.end(); },
    ],
  });

  const closeOnSignal = async () => {
    await lifecycle.shutdown();
    process.exit(0);
  };

  try {
    initObservability(env);
    const db = connectDatabase(databaseURL);
    database = db;
    // Opt-in for single-container deployments (see migrationsOnBootEnabled).
    if (migrationsOnBootEnabled(env)) await migrate(db);
    dailyScheduler = createDailyScheduler(db);
    dailyContentWorker = createDailyContentWorkerScheduler(db,env);
    dailyReviewWorker = createDailyReviewWorkerScheduler(db,env);
    addDatabasePoolObservability(db);
    await initGenerationMetrics(db);
    stopGenerationMetricsRefresh = startGenerationMetricsRefresh(db);

    const generationConfig = parseLevelTestSchedulerConfig(env);
    const origin = new URL(env.PUBLIC_ORIGIN ?? 'http://localhost:8080');
    if (origin.username || origin.password || (origin.protocol !== 'https:' && !(origin.protocol === 'http:' && origin.hostname === 'localhost')))
      throw new Error();
    const key = env.STRIPE_TEST_SECRET_KEY, secret = env.STRIPE_TEST_WEBHOOK_SECRET;
    const payments = key && secret ? new SandboxPayments(key, secret, catalogFromEnvironment(env), origin.origin) : undefined;
    const appleClient = env.APPLE_CLIENT_ID, appleTeam = env.APPLE_TEAM_ID,
      appleKey = env.APPLE_KEY_ID, appleFile = env.APPLE_PRIVATE_KEY_PATH;
    const appleRevoker = appleClient && appleTeam && appleKey && appleFile ? new AppleTokenRevoker(db,
      { clientID: appleClient, teamID: appleTeam, keyID: appleKey, privateKeyPEM: await readFile(appleFile, 'utf8') }) : undefined;
    await appleRevoker?.validateConfiguration();
    minuteCommerce = await configuredMinuteCommerce(db, env, { onFailure: code => log.error('minute_commerce_failure', { code }) });
    const accessMode = env.HOSTED_VOICE_ACCESS ?? 'restricted-test';
    if (!['restricted-test', 'public-minutes'].includes(accessMode)) throw new Error('Invalid hosted access mode.');
    const publicMinuteAccess = accessMode === 'public-minutes';
    if (env.HOSTED_PAID_VALUE_ENABLED && !['true', 'false'].includes(env.HOSTED_PAID_VALUE_ENABLED))
      throw new Error('Invalid paid AI value gate.');
    const publicPaidAccess = env.HOSTED_PAID_VALUE_ENABLED === 'true';
    if (publicPaidAccess && (!publicMinuteAccess || env.HOSTED_HELPERS_EXPERIMENTAL !== 'true' ||
        env.HOSTED_VOICE_EXPERIMENTAL !== 'true')) throw new Error('Paid AI value requires public hosted voice and teaching.');
    if (minuteCommerce?.salesEnabled && !publicPaidAccess) throw new Error('AI value sales require a configured paid conversation service.');
    if (env.GUEST_MINUTES_ENABLED && !['true', 'false'].includes(env.GUEST_MINUTES_ENABLED))
      throw new Error('Invalid guest minute gate.');
    if (env.HOSTED_HELPERS_EXPERIMENTAL && !['true', 'false'].includes(env.HOSTED_HELPERS_EXPERIMENTAL))
      throw new Error('Invalid helper gate.');
    if (env.HOSTED_HELPERS_EXPERIMENTAL === 'true' &&
      (env.HOSTED_VOICE_EXPERIMENTAL !== 'true' || env.HOSTED_VOICE_BILLING_UNIT !== 'milliseconds'))
      throw new Error('Hosted helpers require minute-funded voice.');
    if (env.HOSTED_VOICE_EXPERIMENTAL === 'true') {
      const billingUnit = env.HOSTED_VOICE_BILLING_UNIT ?? 'nanoUSD';
      if (billingUnit !== 'nanoUSD' && billingUnit !== 'milliseconds') throw new Error('Invalid hosted billing unit.');
      const accounts = new Set((env.HOSTED_VOICE_ACCOUNT_ALLOWLIST ?? '').split(',').filter(Boolean));
      if ([...accounts].some(account => !/^[a-f0-9-]{36}$/.test(account))) throw new Error();
      const lifetimeFundingCapNano = BigInt(env.HOSTED_VOICE_LIFETIME_CAP_NANO ?? '0');
      if (env.HOSTED_HELPERS_EXPERIMENTAL === 'true') {
        hostedHelpers = new HostedHelpers(db, new OpenAIHostedResponses(env.OPENAI_API_KEY ?? ''), {
          accountAllowlist: accounts, aggregateFundingCapNano: lifetimeFundingCapNano, publicMinuteAccess, publicPaidAccess,
          helperBudgetNanoPerMinute: BigInt(env.HOSTED_HELPER_BUDGET_PER_MINUTE_NANO ?? '50000000'),
          maxRequestsPerMinute: Number(env.HOSTED_HELPER_REQUESTS_PER_MINUTE ?? '24'),
          maxSearchesPerSession: Number(env.HOSTED_HELPER_SEARCHES_PER_SESSION ?? '0'),
          maxConcurrentPerSession: 2, maxConcurrentGlobal: 10, postSessionMilliseconds: 120_000,
          inputFramingTokenAllowance: 4096, searchInputTokenAllowance: 1_050_000, timeoutMilliseconds: 30_000,
        });
        await hostedHelpers.expireBudgets();
      }
      hosted = new HostedVoice(db, new OpenAILiveProvider(env.OPENAI_API_KEY ?? ''),
        { accountAllowlist: accounts, billingUnit, lifetimeFundingCapNano, publicMinuteAccess, publicPaidAccess, helpers: hostedHelpers,
          onStartupFailure: diagnostic => log.warn('live_startup_failed', diagnostic) });
      await hosted.start();
    }
    const accessConfig = accessRequestConfig(env);
    const accessRequests = accessConfig ? new AccessRequests(db, accessConfig) : undefined;
    const accountsConfig = accountAdmissionConfig(env);
    const accounts = accountsConfig ? { admission: new AuthAdmission(db, accountsConfig) } : undefined;
    const adminConfig = adminAuthConfig(env);
    const admin = adminConfig ? (() => {
      const admissionConfig = { hmacKey: adminConfig.hmacKey, proxyToken: adminConfig.proxyToken,
        allowLocalLoopback: adminConfig.allowLocalLoopback };
      const services: AdminAuthServices = { db, config: adminConfig };
      return { services, admission: new AuthAdmission(db, admissionConfig) };
    })() : undefined;
    if (env.GUEST_MINUTES_ENABLED === 'true' && (!accounts || !hosted?.publicMinuteAccess || !hostedHelpers))
      throw new Error('Guest minutes require public minute voice, teaching and trusted admission.');
    const guestMinuteAttestor = env.GUEST_MINUTES_ENABLED === 'true' ? new InstallationGuestMinuteAttestor() : undefined;
    const reportConfig = aiReportConfig(env);
    const aiReports = reportConfig ? new AIReports(db, reportConfig) : undefined;
    await pruneAccessRequests(db);
    await pruneAuthenticationRecords(db);
    await pruneAIReports(db);
    await pruneUsageEvents(db);
    await pruneAdminRecords(db);
    const googleAndroidClientIDs = (env.GOOGLE_ANDROID_CLIENT_IDS ?? '').split(',').map(id => id.trim()).filter(Boolean);
    const googleAndroidServerClientID = env.GOOGLE_ANDROID_SERVER_CLIENT_ID;
    if (Boolean(googleAndroidServerClientID) !== Boolean(googleAndroidClientIDs.length) || googleAndroidClientIDs.length > 10 ||
      [googleAndroidServerClientID, ...googleAndroidClientIDs].filter(Boolean).some(id => !/^[A-Za-z0-9-]+\.apps\.googleusercontent\.com$/.test(id!)))
      throw new Error('Android Google identity configuration is incomplete.');
    if (accounts && !hasGoogleSignIn({ googleClientID: env.GOOGLE_CLIENT_ID, googleAndroidServerClientID, googleAndroidClientIDs }) &&
      !(appleClient && appleRevoker)) throw new Error('No account identity provider configured.');
    if (generationConfig.enabled) generationScheduler = createLevelTestScheduler({ config: generationConfig, runWeek: async (weekId, signal, timeoutMs) => {
      await runLevelTestCombinations(generationConfig, weekId, signal, async (languageID, level, jobWeek, jobSignal) => {
        const result = await runLevelTestGenerationJob(db, env, { languageID, level, weekIndex: jobWeek, signal: jobSignal, origin: 'automatic_worker' });
        if (result.status === 'failed' || result.status === 'unknown')
          log.warn('level_test_generation_job', { language: languageID, level, week: jobWeek, status: result.status,
            error_code: result.errors?.[0] ?? 'generation_failed' });
      }, (languageID, level, code) => log.warn('level_test_generation_job', { language: languageID, level, week: weekId,
        status: 'failed', error_code: code }), undefined, timeoutMs);
    } });
    tokenProxy = new TokenProxy(db, tokenProxyConfig(env), () => resolveLlmConfig(db, env));
    const proxyQuota=proxyQuotaConfig(env);
    app = createApp({ db, environment: env,
      generationStatus: { config: generationConfig, isStarted: () => generationScheduler?.isStarted() ?? false },
      auth: { googleClientID: env.GOOGLE_CLIENT_ID, appleClientID: appleClient,
        googleAndroidServerClientID, googleAndroidClientIDs }, payments, appleRevoker, hosted, hostedHelpers,
      minuteCommerce, accessRequests, accounts, admin, aiReports, guestMinuteAttestor,
      subscriptions: minuteCommerce?.stripe ? new SubscriptionBilling(db, minuteCommerce.stripe.transport,
        { origin: origin.origin.replace(/\/$/, '') }) : undefined,
      tokenProxy });

    cleanup = setInterval(() => {
      void pruneAuthenticationRecords(db).catch(() => { log.error('retention_cleanup_failed'); });
      void pruneAccessRequests(db).catch(() => { log.error('retention_cleanup_failed'); });
      void pruneAIReports(db).catch(() => { log.error('retention_cleanup_failed'); });
      void pruneUsageEvents(db).catch(() => { log.error('retention_cleanup_failed'); });
      void pruneAdminRecords(db).catch(() => { log.error('retention_cleanup_failed'); });
      void hostedHelpers?.expireBudgets().catch(() => { log.error('retention_cleanup_failed'); });
    }, 15 * 60_000);
    cleanup.unref();
    const retryGuestLinks = () => {
      if (!guestLinkFlight) guestLinkFlight = finalizeDeferredGuestLinks(db)
        .catch(() => { log.error('guest_allowance_retry_failed'); }).finally(() => { guestLinkFlight = undefined; });
    };
    guestLinkCleanup = setInterval(retryGuestLinks, 60_000);
    guestLinkCleanup.unref();
    retryGuestLinks();
    // Daily subscription-quota grants: idempotent by ledger reference, so a
    // failed or partial sweep retries on the next tick and only a completed
    // day is marked done (B2). Late signups are covered by the immediate
    // first grant inside grant-quota.
    if (tokenProxy || proxyQuota.dailyInputTokens > 0 || proxyQuota.dailyOutputTokens > 0) {
      let lastCompletedDay = '';
      const runQuota = () => {
        const day = new Date().toISOString().slice(0, 10);
        if (day === lastCompletedDay) return;
        void runDailyQuotaGrants(db, proxyQuota)
          .then(() => { lastCompletedDay = day; })
          .catch(() => { log.warn('quota_grant_retry_scheduled'); });
      };
      runQuota();
      quotaCleanup = setInterval(runQuota, 60_000);
      quotaCleanup.unref();
    }
    process.on('SIGTERM', closeOnSignal);
    process.on('SIGINT', closeOnSignal);
    await lifecycle.start();
  } catch (err) {
    // Startup failures were previously swallowed silently; surface the cause
    // with scalar fields (log.ts filters sensitive field names) so operators
    // can diagnose without exec access to the crashed container.
    const detail = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
    const kind = err instanceof Error ? err.name : typeof err;
    log.error('startup_failed', { detail, kind });
    try { await lifecycle.shutdown(); } catch { /* Keep the process failure visible without skipping remaining cleanup steps. */ }
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) void main();

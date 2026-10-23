const events = [];
const failure = process.env.MAIN_WIRING_FAILURE;
const record = event => events.push(event);
const failAt = name => {
  if (failure === name) throw new Error(`controlled startup failure: ${name}`);
};

const processOn = process.on.bind(process);
process.on = function (event, ...args) {
  if (event === 'SIGTERM' || event === 'SIGINT') record(`signal_handler_registered:${event}`);
  return processOn(event, ...args);
};
process.once('exit', () => console.log(`MAIN_WIRING_EVENTS=${JSON.stringify(events)}`));

globalThis.setInterval = () => ({ unref() { record('timer_unref'); } });
globalThis.clearInterval = () => record('timers_clear');

export function connectDatabase() {
  record('database_open');
  return { async end() { record('database_close'); }, async query() { return { rows: [] }; } };
}
export function createApiLifecycle({ listen, startWorkers, shutdownSteps }) {
  let shutdownPromise;
  const shutdown = () => shutdownPromise ??= (async () => {
    for (const step of shutdownSteps) {
      try { await step(); } catch { /* Keep attempting later cleanup steps. */ }
    }
  })();
  return {
    async start() {
      try { await listen(); startWorkers(); }
      catch (error) { await shutdown(); throw error; }
    },
    shutdown,
  };
}
export function catalogFromEnvironment() { return {}; }
export class SandboxPayments {}
export function hasGoogleSignIn() { return false; }
export async function pruneAuthenticationRecords() { record('prune_authentication'); failAt('prune_authentication'); }
export class AppleTokenRevoker {}
export class HostedVoice {}
export class OpenAILiveProvider {}
export class AccessRequests {}
export function accessRequestConfig() { return undefined; }
export function pruneAccessRequests() { record('prune_access_requests'); }
export class AuthAdmission {}
export function accountAdmissionConfig() { return undefined; }
export class TokenProxy {}
export function tokenProxyConfig() { return undefined; }
export function adminAuthConfig() { return undefined; }
export function pruneAdminRecords() { record('prune_admin_records'); }
export class AIReports {}
export function aiReportConfig() { return undefined; }
export function pruneAIReports() { record('prune_ai_reports'); }
export function pruneUsageEvents() { record('prune_usage_events'); }
export class HostedHelpers {}
export class OpenAIHostedResponses {}
export class InstallationGuestMinuteAttestor {}
export class SubscriptionBilling {}
export function createLevelTestScheduler() {
  return { start() { record('generation_worker_start'); }, async stop() { record('generation_worker_stop'); }, isStarted() { return true; } };
}
export async function runLevelTestCombinations() {}
export async function runLevelTestGenerationJob() {}
export function initObservability() { record('observability_init'); }
export function addDatabasePoolObservability() { record('database_observability_add'); }
export async function initGenerationMetrics() { record('generation_metrics_init'); failAt('generation_metrics'); }
export function startGenerationMetricsRefresh() {
  record('metrics_refresh_start');
  return () => record('metrics_refresh_stop');
}
export async function shutdownObservability() { record('observability_shutdown'); }
export function parseLevelTestSchedulerConfig() { return { enabled: true }; }
export async function configuredMinuteCommerce() {
  return { salesEnabled: false, runner: {
    start() { record('minute_worker_start'); },
    async stop() { record('minute_worker_stop'); },
  } };
}
export async function finalizeDeferredGuestLinks() { record('guest_links_finalize'); }
export function createApp() {
  record('app_construct');
  failAt('app_construction');
  return {
    async listen() { record('http_listen'); failAt('http_listen'); },
    async close() { record('http_close'); },
  };
}
export const log = {
  info(name) {
    record(name);
    if (name === 'api_started' && process.env.MAIN_WIRING_SIGNAL)
      setImmediate(() => process.emit(process.env.MAIN_WIRING_SIGNAL));
  },
  warn(name) { record(name); },
  error(name) { record(name); },
};

import type { Database } from './db.js';
import { ServiceError } from './errors.js';

// Fixed read-only analytics. No client text ever becomes SQL: only the set name
// (validated) and the clamped day count reach the queries.
export const TIMESERIES_SETS = ['overview', 'growth', 'voice', 'economy'] as const;
export type TimeseriesSet = (typeof TIMESERIES_SETS)[number];

interface SeriesMetric { name: string; label: string; unit: 'count' | 'minor' | 'milliseconds' | 'nano'; sql: string }

// $1 is the clamped day count; every metric returns one row per UTC day, zero-filled.
const daily = (inner: string, value = 'count(*)::int') =>
  `SELECT d::date AS day, COALESCE(m.value, 0) AS value
   FROM generate_series(now()::date - ($1::int - 1), now()::date, interval '1 day') d
   LEFT JOIN (SELECT ${inner} GROUP BY 1) m ON m.day = d::date ORDER BY d::date`;

const SERIES: Record<TimeseriesSet, SeriesMetric[]> = {
  overview: [
    { name: 'signups', label: 'New accounts', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM accounts WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'sessions', label: 'Hosted sessions', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM hosted_sessions WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'orders', label: 'Purchase orders', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM minute_purchase_orders WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'revenue_minor', label: 'Order volume (minor units)', unit: 'minor',
      sql: daily(`created_at::date AS day, COALESCE(sum(total_minor), 0)::bigint AS value FROM minute_purchase_orders WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'waitlist', label: 'Waitlist requests', unit: 'count',
      sql: daily(`requested_at::date AS day, count(*)::int AS value FROM access_requests WHERE requested_at >= now()::date - ($1::int - 1)`) }
  ],
  growth: [
    { name: 'signups', label: 'New accounts', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM accounts WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'guests', label: 'New guest accounts', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM accounts WHERE is_guest AND created_at >= now()::date - ($1::int - 1)`) },
    { name: 'waitlist', label: 'Waitlist requests', unit: 'count',
      sql: daily(`requested_at::date AS day, count(*)::int AS value FROM access_requests WHERE requested_at >= now()::date - ($1::int - 1)`) },
    { name: 'welcome_claims', label: 'Welcome minute grants', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM minute_entries WHERE kind = 'welcome' AND created_at >= now()::date - ($1::int - 1)`) },
    { name: 'ai_reports', label: 'AI reports', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM ai_feedback_reports WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'active_accounts', label: 'Accounts with ledger activity', unit: 'count',
      sql: daily(`created_at::date AS day, count(DISTINCT account_id)::int AS value FROM minute_entries WHERE created_at >= now()::date - ($1::int - 1)`) }
  ],
  voice: [
    { name: 'sessions', label: 'Hosted sessions created', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM hosted_sessions WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'closed', label: 'Hosted sessions closed', unit: 'count',
      sql: daily(`close_requested_at::date AS day, count(*)::int AS value FROM hosted_sessions WHERE close_requested_at >= now()::date - ($1::int - 1)`) },
    { name: 'charged_ms', label: 'Charged conversation time (ms)', unit: 'milliseconds',
      sql: daily(`created_at::date AS day, COALESCE(sum(charged_ms), 0)::bigint AS value FROM hosted_sessions WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'helpers', label: 'Helper requests', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM hosted_helper_requests WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'helper_cost_nano', label: 'Helper cost (nanoUSD)', unit: 'nano',
      sql: daily(`created_at::date AS day, COALESCE(sum(cost_nano), 0)::bigint AS value FROM hosted_helper_requests WHERE created_at >= now()::date - ($1::int - 1)`) }
  ],
  economy: [
    { name: 'orders', label: 'Purchase orders', unit: 'count',
      sql: daily(`created_at::date AS day, count(*)::int AS value FROM minute_purchase_orders WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'revenue_minor', label: 'Order volume (minor units)', unit: 'minor',
      sql: daily(`created_at::date AS day, COALESCE(sum(total_minor), 0)::bigint AS value FROM minute_purchase_orders WHERE created_at >= now()::date - ($1::int - 1)`) },
    { name: 'minutes_granted_ms', label: 'Minutes granted (ms)', unit: 'milliseconds',
      sql: daily(`created_at::date AS day, COALESCE(sum(balance_delta_ms), 0)::bigint AS value FROM minute_entries WHERE balance_delta_ms > 0 AND created_at >= now()::date - ($1::int - 1)`) },
    { name: 'minutes_consumed_ms', label: 'Minutes consumed (ms)', unit: 'milliseconds',
      sql: daily(`created_at::date AS day, COALESCE(-sum(balance_delta_ms), 0)::bigint AS value FROM minute_entries WHERE balance_delta_ms < 0 AND created_at >= now()::date - ($1::int - 1)`) },
    { name: 'sandbox_grants_ms', label: 'Legacy sandbox grants (ms)', unit: 'milliseconds',
      sql: daily(`created_at::date AS day, COALESCE(sum(sandbox_delta_ms), 0)::bigint AS value FROM minute_entries WHERE sandbox_delta_ms > 0 AND created_at >= now()::date - ($1::int - 1)`) }
  ]
};

export interface TimeseriesPoint { day: string; value: number }
export interface Timeseries { set: TimeseriesSet; days: number; series: Array<{ name: string; label: string; unit: string; points: TimeseriesPoint[] }> }

export async function timeseries(db: Database, set: string, requestedDays: number): Promise<Timeseries> {
  if (!(SERIES as Record<string, unknown>)[set]) throw new ServiceError('invalid_request');
  const days = Number.isSafeInteger(requestedDays) ? Math.min(Math.max(requestedDays, 1), 90) : 30;
  const metrics = SERIES[set as TimeseriesSet]!;
  const results = await Promise.all(metrics.map(async metric => ({
    ...metric, points: (await db.query(metric.sql, [days])).rows.map(row => ({
      day: (row.day as Date).toISOString().slice(0, 10), value: Number(row.value)
    }))
  })));
  return { set: set as TimeseriesSet, days, series: results.map(({ name, label, unit, points }) => ({ name, label, unit, points })) };
}

export interface AdminSnapshot { generatedAt: string; accounts: { total: number; guests: number; deleted: number };
  liveAppSessions: number; minuteWallets: { balanceMs: number; reservedMs: number };
  activeAccounts30d: number; hostedSessions: Record<string, number>; helperRequests: { total: number; cost30dNano: number };
  orders: Array<{ provider: string; environment: string; entitlementKind: string; count: number; totalMinor: number }>;
  waitlist: number; aiReports: { total: number; last30d: number }; campaigns: { applied: number; prepared: number };
  adminActions: number }

export async function snapshot(db: Database): Promise<AdminSnapshot> {
  const [accounts, sessions, minutes, active, hosted, helpers, orders, waitlist, reports, campaigns, actions] =
    await Promise.all([
      db.query(`SELECT count(*) AS total, count(*) FILTER (WHERE is_guest) AS guests,
        count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted FROM accounts`),
      db.query('SELECT count(*) AS count FROM auth_sessions WHERE expires_at > now() AND revoked_at IS NULL'),
      db.query('SELECT COALESCE(sum(balance_ms), 0) AS balance, COALESCE(sum(reserved_ms), 0) AS reserved FROM minute_wallets'),
      db.query(`SELECT count(DISTINCT account_id) AS count FROM minute_entries WHERE created_at >= now() - interval '30 days'`),
      db.query('SELECT state, count(*) AS count FROM hosted_sessions GROUP BY state'),
      db.query(`SELECT count(*) AS total, COALESCE(sum(cost_nano) FILTER (WHERE created_at >= now() - interval '30 days'), 0) AS cost30
        FROM hosted_helper_requests`),
      db.query(`SELECT provider, environment, entitlement_kind, count(*) AS count, COALESCE(sum(total_minor), 0) AS total_minor
        FROM minute_purchase_orders GROUP BY 1, 2, 3`),
      db.query('SELECT count(*) AS count FROM access_requests'),
      db.query(`SELECT count(*) AS total, count(*) FILTER (WHERE created_at >= now() - interval '30 days') AS last30
        FROM ai_feedback_reports`),
      db.query(`SELECT count(*) FILTER (WHERE state = 'applied') AS applied,
        count(*) FILTER (WHERE state <> 'applied') AS prepared FROM minute_campaigns`),
      db.query('SELECT count(*) AS count FROM admin_actions')
    ]);
  const hostedStates: Record<string, number> = {};
  for (const row of hosted.rows) hostedStates[String(row.state)] = Number(row.count);
  return {
    generatedAt: new Date().toISOString(),
    accounts: { total: Number(accounts.rows[0]!.total), guests: Number(accounts.rows[0]!.guests), deleted: Number(accounts.rows[0]!.deleted) },
    liveAppSessions: Number(sessions.rows[0]!.count),
    minuteWallets: { balanceMs: Number(minutes.rows[0]!.balance), reservedMs: Number(minutes.rows[0]!.reserved) },
    activeAccounts30d: Number(active.rows[0]!.count),
    hostedSessions: hostedStates,
    helperRequests: { total: Number(helpers.rows[0]!.total), cost30dNano: Number(helpers.rows[0]!.cost30) },
    orders: orders.rows.map(row => ({ provider: String(row.provider), environment: String(row.environment),
      entitlementKind: String(row.entitlement_kind), count: Number(row.count), totalMinor: Number(row.total_minor) })),
    waitlist: Number(waitlist.rows[0]!.count),
    aiReports: { total: Number(reports.rows[0]!.total), last30d: Number(reports.rows[0]!.last30) },
    campaigns: { applied: Number(campaigns.rows[0]!.applied), prepared: Number(campaigns.rows[0]!.prepared) },
    adminActions: Number(actions.rows[0]!.count)
  };
}

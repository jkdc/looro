import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export const USAGE_EVENTS_PATH = '/v1/usage/events';
export const USAGE_EVENTS_BODY_LIMIT = 65_536;
export const USAGE_EVENT_BATCH = 100;
export const USAGE_RETENTION_DAYS = 180;

// Prop shapes are closed: enums, small ints, booleans and catalog slugs only.
// Free text has no shape it can fit, so the table cannot grow user content.
// int props are coarse buckets decided by the client (duration quintiles of a
// session in order <30s..30m+, turns <3..60+, score quintiles 0..4).
type PropSpec =
  | { readonly kind: 'enum'; readonly values: readonly string[]; readonly optional?: true }
  | { readonly kind: 'int'; readonly min: number; readonly max: number; readonly optional?: true }
  | { readonly kind: 'flag'; readonly optional?: true }
  | { readonly kind: 'slug'; readonly max: number; readonly optional?: true };

const buckets = (max: number) => ({ kind: 'int', min: 0, max }) as const;
const slug = { kind: 'slug', max: 64 } as const;
const optional = <T extends PropSpec>(rule: T): T => ({ ...rule, optional: true });

export const USAGE_CATALOG = {
  app_open: {},
  session_started: { input: { kind: 'enum', values: ['voice', 'typed'] },
    provider: { kind: 'enum', values: ['hosted', 'byok'] },
    engine: optional({ kind: 'enum', values: ['openai', 'deepseek', 'glm'] }) },
  session_ended: { durationBucket: buckets(5), turnsBucket: buckets(5), themeID: optional(slug) },
  session_resumed: {},
  theme_selected: { themeID: slug },
  lesson_started: { courseID: slug, lessonID: slug },
  lesson_completed: { courseID: slug, lessonID: slug },
  assessment_completed: { courseID: optional(slug), passed: { kind: 'flag' } },
  practice_completed: { mode: { kind: 'enum', values: ['recall', 'word'] }, scoreBucket: buckets(4) },
  word_lookup: { source: { kind: 'enum', values: ['talk', 'words', 'lesson'] } },
  replay_used: {},
  tts_speed_changed: { rate: { kind: 'enum', values: ['0.6', '0.8', '1.0', '1.2'] } },
  help_requested: {},
  report_requested: {},
  paywall_viewed: { surface: { kind: 'enum', values: ['talk', 'settings'] } }
} as const satisfies Record<string, Record<string, PropSpec>>;

export type UsageEventName = keyof typeof USAGE_CATALOG;
export const USAGE_EVENT_NAMES = Object.keys(USAGE_CATALOG) as UsageEventName[];

const USAGE_PLATFORMS = ['android', 'ios'] as const;
const UUID_V4 = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const SLUG = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const VERSION = /^[\x20-\x7e]{1,32}$/;
const FUTURE_SKEW_MILLISECONDS = 48 * 3_600_000;

interface UsageEventInput { id: string; name: UsageEventName; at: Date; props: Record<string, unknown> }
export interface UsageBatch { platform: (typeof USAGE_PLATFORMS)[number]; appVersion: string; events: UsageEventInput[] }

// Throws are inline (not a never-returning helper): tsgo's control-flow
// analysis keeps type narrowing only when the throw sits in the branch itself.
const invalid = 'invalid_usage_batch';

export function parseUsageBatch(body: unknown, now = Date.now()): UsageBatch {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Buffer.isBuffer(body)) throw new ServiceError(invalid);
  const input = body as Record<string, unknown>;
  if (Object.keys(input).some(key => !['platform', 'appVersion', 'events'].includes(key))) throw new ServiceError(invalid);
  const platform = input.platform, version = input.appVersion, rawEvents = input.events;
  if (typeof platform !== 'string' || !(USAGE_PLATFORMS as readonly string[]).includes(platform)) throw new ServiceError(invalid);
  if (typeof version !== 'string' || !VERSION.test(version)) throw new ServiceError(invalid);
  if (!Array.isArray(rawEvents) || rawEvents.length < 1 || rawEvents.length > USAGE_EVENT_BATCH) throw new ServiceError(invalid);
  const events = rawEvents.map(entry => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new ServiceError(invalid);
    const source = entry as Record<string, unknown>;
    if (Object.keys(source).some(key => !['id', 'name', 'at', 'props'].includes(key))) throw new ServiceError(invalid);
    const id = source.id, name = source.name, at = source.at, rawProps = source.props;
    if (typeof id !== 'string' || !UUID_V4.test(id)) throw new ServiceError(invalid);
    if (typeof name !== 'string' || !USAGE_EVENT_NAMES.includes(name as UsageEventName)) throw new ServiceError(invalid);
    if (typeof at !== 'number' || !Number.isSafeInteger(at) || at <= 0 || at > now + FUTURE_SKEW_MILLISECONDS) throw new ServiceError(invalid);
    const spec = USAGE_CATALOG[name as UsageEventName];
    const props = rawProps === undefined ? {} : rawProps;
    if (!props || typeof props !== 'object' || Array.isArray(props) ||
      Object.keys(props as Record<string, unknown>).some(key => !(key in spec))) throw new ServiceError(invalid);
    for (const [key, rule] of Object.entries(spec) as [string, PropSpec][]) {
      const value = (props as Record<string, unknown>)[key];
      if (value === undefined) { if (!rule.optional) throw new ServiceError(invalid); continue; }
      if (rule.kind === 'flag' ? typeof value !== 'boolean'
        : rule.kind === 'int' ? (typeof value !== 'number' || !Number.isSafeInteger(value) || value < rule.min || value > rule.max)
        : rule.kind === 'enum' ? (typeof value !== 'string' || !rule.values.includes(value))
        : (typeof value !== 'string' || value.length > rule.max || !SLUG.test(value))) throw new ServiceError(invalid);
    }
    return { id: id.toLowerCase(), name: name as UsageEventName, at: new Date(at), props: props as Record<string, unknown> };
  });
  return { platform: platform as UsageBatch['platform'], appVersion: version, events };
}

/** Client retries replay the same event ids; the original row always wins. */
export async function ingestUsageEvents(db: Database, account: string, body: unknown): Promise<{ accepted: number }> {
  const batch = parseUsageBatch(body);
  // $1 account, $2 platform, $3 appVersion, then four per event: client id, name, props, at.
  // The primary key is minted server-side; idempotency keys off client_event_id alone.
  const values: unknown[] = [account, batch.platform, batch.appVersion];
  const rows = batch.events.map((event, index) => {
    const base = 4 + index * 4;
    values.push(event.id, event.name, JSON.stringify(event.props), event.at);
    return `(gen_random_uuid(),$${base}::uuid,$1,$${base + 1},$2,$3,$${base + 2}::jsonb,$${base + 3}::timestamptz)`;
  });
  await transaction(db, async sql => {
    await sql.query(`INSERT INTO usage_events(id,client_event_id,account_id,name,platform,app_version,props,client_at)
      VALUES ${rows.join(',')} ON CONFLICT (client_event_id) DO NOTHING`, values);
  });
  return { accepted: batch.events.length };
}

export async function pruneUsageEvents(db: Database): Promise<number> {
  return (await db.query('DELETE FROM usage_events WHERE created_at < now() - make_interval(days => $1)',
    [USAGE_RETENTION_DAYS])).rowCount ?? 0;
}

import type { Database } from './db.js';
import { ServiceError } from './errors.js';
import { paliqueMetrics } from './metrics.js';

/** Purpose-based routing and the request policy for the token proxy
    (change proxy-api-provider). The purpose header is client advice for
    model selection and cost only — auth, quota and metering stay
    account-scoped; the client never picks what it is charged for. */

export const PROXY_PURPOSES = ['typed_reply', 'meaning', 'assessment', 'lookup', 'topic', 'help', 'scenario_review', 'delegation'] as const;
export type ProxyPurpose = (typeof PROXY_PURPOSES)[number];

export const isProxyPurpose = (value: string): value is ProxyPurpose =>
  (PROXY_PURPOSES as readonly string[]).includes(value);

export interface PurposeRoute { purpose: string; model: string; displayName: string; outputCap: number }

/** The enabled route for a purpose, or null when none is configured. */
export async function purposeRoute(db: Database, purpose: ProxyPurpose): Promise<PurposeRoute | null> {
  const row = (await db.query(
    'SELECT purpose,model,display_name,output_cap FROM proxy_purpose_routes WHERE purpose=$1 AND enabled', [purpose])).rows[0];
  if (!row) return null;
  return { purpose: row.purpose as string, model: row.model as string, displayName: row.display_name as string, outputCap: row.output_cap as number };
}

/** Model labels for the policy endpoint, keyed by purpose (codepoint order). */
export async function purposeRouteTable(db: Database): Promise<Record<string, string>> {
  const rows = (await db.query(
    'SELECT purpose,display_name FROM proxy_purpose_routes WHERE enabled ORDER BY purpose')).rows;
  const models: Record<string, string> = {};
  for (const row of rows) models[row.purpose as string] = row.display_name as string;
  return models;
}

const ALLOWED_FIELDS = new Set(['messages', 'response_format', 'temperature']);
/** Client-supplied but server-overridden: accepted and replaced, never
    forwarded, never a violation — the server owns these decisions. */
const OVERRIDDEN_FIELDS = new Set(['model', 'max_tokens', 'stream']);

/** Closed metric label set: the field is one of our own allowlist keys, or
    'other'. Never echo client-controlled names into telemetry. */
const policyFieldLabel = (field: string): string =>
  ALLOWED_FIELDS.has(field) || OVERRIDDEN_FIELDS.has(field) ? field : 'other';

export const policyRejected = (field: string) =>
  paliqueMetrics.proxyPolicyRejected.add(1, { field: policyFieldLabel(field) });

const rejectField = (field: string): never => {
  policyRejected(field);
  throw new ServiceError('request_policy_violation', 400);
};

export interface CanonicalProxyRequest { body: Record<string, unknown>; outputCap: number }

/** Builds the forwarded request from the server-owned policy: allowlist of
    caller fields (client model/cap/stream are accepted and overridden),
    model and output cap from the route, streaming forced off. What is priced
    (the reservation estimate) is built from the same values that are
    forwarded. */
export function canonicalProxyRequest(input: Record<string, unknown>, route: PurposeRoute): CanonicalProxyRequest {
  for (const key of Object.keys(input)) {
    if (!ALLOWED_FIELDS.has(key) && !OVERRIDDEN_FIELDS.has(key)) rejectField(key);
  }
  const messages = input.messages;
  if (!Array.isArray(messages) || messages.length === 0 ||
      !messages.every(message => message !== null && typeof message === 'object')) rejectField('messages');
  const body: Record<string, unknown> = { messages, model: route.model, max_tokens: route.outputCap, stream: false };
  if (input.response_format !== undefined) {
    const format = input.response_format;
    if (format === null || typeof format !== 'object' || Array.isArray(format) ||
        (format as Record<string, unknown>).type !== 'json_object') rejectField('response_format');
    body.response_format = format;
  }
  if (input.temperature !== undefined) {
    const temperature = input.temperature;
    if (typeof temperature !== 'number' || !Number.isFinite(temperature) || temperature < 0 || temperature > 2) rejectField('temperature');
    body.temperature = temperature;
  }
  return { body, outputCap: route.outputCap };
}

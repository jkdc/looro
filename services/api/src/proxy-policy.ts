import { ServiceError } from './errors.js';
import { paliqueMetrics } from './metrics.js';

/** The request policy for the token proxy (change proxy-model-from-panel).
    The forwarded model and output cap come from the per-call provider
    resolution, never from the caller; the policy only decides which caller
    fields may ride along. */

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
    model and output cap from the resolved provider config, streaming forced
    off. What is priced (the reservation estimate) is built from the same
    values that are forwarded. */
export function canonicalProxyRequest(input: Record<string, unknown>, model: string, outputCap: number): CanonicalProxyRequest {
  for (const key of Object.keys(input)) {
    if (!ALLOWED_FIELDS.has(key) && !OVERRIDDEN_FIELDS.has(key)) rejectField(key);
  }
  const messages = input.messages;
  if (!Array.isArray(messages) || messages.length === 0 ||
      !messages.every(message => message !== null && typeof message === 'object')) rejectField('messages');
  const body: Record<string, unknown> = { messages, model, max_tokens: outputCap, stream: false };
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
  return { body, outputCap };
}

// Structured JSON logging to stdout with a closed, scalar-only field set.
// No request bodies, Authorization headers, tokens, transcripts, or secrets can
// reach stdout through this module: the field type admits no objects or arrays.
export type LogFields = Record<string, string | number | boolean | null | undefined>;

const write = (level: 'info' | 'warn' | 'error', event: string, fields?: LogFields): void => {
  const line: Record<string, unknown> = { timestamp: new Date().toISOString(), level, event };
  const reserved = new Set(['timestamp', 'level', 'event']);
  const sensitive = /(?:^|_)(?:account_id|api_key|authorization|body|cookie|credential|email|headers?|ip|message|password|path|phone|query|secret|stack|token|url|user_id)(?:_|$)|^error$/i;
  for (const [key, value] of Object.entries(fields ?? {})) {
    if (reserved.has(key) || sensitive.test(key) || value === undefined) continue;
    line[key] = value;
  }
  const text = JSON.stringify(line);
  if (level === 'error') console.error(text);
  else if (level === 'warn') console.warn(text);
  else console.info(text);
};

export const log = {
  info: (event: string, fields?: LogFields) => write('info', event, fields),
  warn: (event: string, fields?: LogFields) => write('warn', event, fields),
  error: (event: string, fields?: LogFields) => write('error', event, fields),
};

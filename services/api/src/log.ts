// Structured JSON logging to stdout with a closed, scalar-only field set.
// No request bodies, Authorization headers, tokens, transcripts, or secrets can
// reach stdout through this module: the field type admits no objects or arrays.
export type LogFields = Record<string, string | number | boolean | null | undefined>;

const write = (level: 'info' | 'warn' | 'error', event: string, fields?: LogFields): void => {
  const line: Record<string, unknown> = { timestamp: new Date().toISOString(), level, event };
  for (const [key, value] of Object.entries(fields ?? {})) if (value !== undefined) line[key] = value;
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

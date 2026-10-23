import { pathToFileURL } from 'node:url';
import { fileURLToPath } from 'node:url';
import { dirname, resolve as resolvePath } from 'node:path';

const stubs = pathToFileURL(resolvePath(dirname(fileURLToPath(import.meta.url)), 'main-wiring-stubs.mjs')).href;

export async function resolve(specifier, context, nextResolve) {
  const parentPath = context.parentURL ? new URL(context.parentURL).pathname : '';
  const entryPath = process.env.MAIN_WIRING_ENTRY ? new URL(process.env.MAIN_WIRING_ENTRY).pathname : '';
  const isMainEntry = parentPath === entryPath || parentPath.endsWith('/src/main.ts') || parentPath.endsWith('/dist/src/main.js');
  if (isMainEntry && specifier.startsWith('.'))
    return { url: stubs, shortCircuit: true };
  return nextResolve(specifier, context);
}

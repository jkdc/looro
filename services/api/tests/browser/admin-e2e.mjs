// T11 browser E2E (task 5): drives the real admin panel (served by Caddy from
// this repo) against the isolated compose stack in this directory — real API,
// real PostgreSQL, stub LLM provider, seeded drafts. Asserts the T11 acceptance
// surface exactly as specified:
//   - login with local operator credentials;
//   - effective saved configuration rendered (never env defaults), masked key;
//   - save → reload shows persisted values;
//   - save-first test semantics (dirty form disables the connection test with
//     an explicit warning);
//   - saved-configuration connection test against the stub provider;
//   - private draft listing, audited approve and reject with mandatory reason;
//   - scheduler status distinguishes configured (off) from in-process activity;
//   - after approval the public catalog serves the week (bounded response).
//
// Runner: Playwright comes from an existing local playwright-core install and
// the browser from the already-populated ~/.cache/ms-playwright cache — the
// script downloads nothing. See README.md for exact reproduction steps.
import { createRequire } from 'node:module';
import { readdir, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const PANEL = process.env.E2E_PANEL_ORIGIN ?? 'http://127.0.0.1:8090';
const API = process.env.E2E_API_ORIGIN ?? 'http://127.0.0.1:8081';
const EVIDENCE = process.env.E2E_EVIDENCE_DIR ?? 'e2e-evidence/t11-browser-e2e';

const require = createRequire(import.meta.url);
const playwrightRoots = [
  process.env.PLAYWRIGHT_CORE_PATH,
  join(homedir(), '.hermes/hermes-agent/node_modules/playwright-core'),
].filter(Boolean);
let chromium;
for (const root of playwrightRoots) {
  if (existsSync(root)) { chromium = require(root).chromium; break; }
}
if (!chromium) { console.error('playwright-core not found; set PLAYWRIGHT_CORE_PATH'); process.exit(1); }

const cacheDir = join(homedir(), '.cache/ms-playwright');
const builds = existsSync(cacheDir)
  ? (await readdir(cacheDir)).filter(name => /^chromium-\d+$/.test(name)).sort().reverse() : [];
if (!builds.length) { console.error('no cached chromium in ~/.cache/ms-playwright'); process.exit(1); }
const executablePath = join(cacheDir, builds[0], 'chrome-linux64', 'chrome');

const failures = [];
const check = async (name, assertion) => {
  try { await assertion(); console.log(`PASS  ${name}`); }
  catch (error) { failures.push(name); console.error(`FAIL  ${name}: ${error.message}`); }
};

await mkdir(EVIDENCE, { recursive: true });
const browser = await chromium.launch({ executablePath, headless: true });
const page = await browser.newPage();
const shot = name => page.screenshot({ path: join(EVIDENCE, `${name}.png`), fullPage: true });
// Toasts self-remove after ~4s; drain them so a stale toast can never
// satisfy the next assertion.
const drainToasts = async () => {
  await page.waitForFunction(() => document.querySelectorAll('.alert').length === 0,
    undefined, { timeout: 6_000 }).catch(() => {});
};
const expectText = async (selector, needle) => {
  const text = await page.textContent(selector);
  if (!text || !text.includes(needle))
    throw new Error(`"${needle}" not found in ${selector} (got: ${JSON.stringify(text?.slice(0, 120))})`);
};

try {
  // 1. Local operator login.
  await page.goto(`${PANEL}/login.html`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', 'e2e-operator');
  await page.fill('#password', 'e2e-browser-password');
  await shot('01-login');
  await page.click('#login-btn');
  await page.waitForURL(`${PANEL}/`, { timeout: 10_000 });
  await page.waitForSelector('#nav', { timeout: 10_000 });
  await check('login establishes an operator session', async () => {
    if (!page.url().startsWith(PANEL)) throw new Error(`unexpected url ${page.url()}`);
  });
  await shot('02-dashboard');

  // 2. Effective configuration view (T11 "Generación de exámenes").
  await page.click('a[href="#level-tests"]');
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('effective config renders server values', async () => {
    const provider = await page.inputValue('#lt-provider');
    const baseUrl = await page.inputValue('#lt-base-url');
    if (provider !== 'openai') throw new Error(`provider=${provider}`);
    if (!/openai\.com/.test(baseUrl)) throw new Error(`baseUrl=${baseUrl} (env default expected before first save)`);
  });
  await check('connection test disabled without a persisted key', async () => {
    if (await page.isEnabled('#lt-test')) throw new Error('test enabled although keyHint is empty');
  });
  await shot('03-effective-config');

  // 3. Save persisted configuration against the stub provider.
  await page.fill('#lt-model', 'stub-model');
  await page.fill('#lt-base-url', 'http://stub-llm:9999/v1');
  await page.fill('#lt-api-key', 'sk-e2e-stub-key-1234567890');
  await page.selectOption('#lt-verify', 'flag');
  await page.fill('#lt-variants', '2');
  await page.click('#lt-save');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('save persists and reload shows stored values with masked key', async () => {
    if ((await page.inputValue('#lt-model')) !== 'stub-model') throw new Error('model not reloaded');
    const variantValue = await page.inputValue('#lt-variants');
    if (variantValue !== '2') throw new Error(`variants=${variantValue}`);
    const placeholder = await page.getAttribute('#lt-api-key', 'placeholder');
    if (!placeholder?.startsWith('Guardada:')) throw new Error(`placeholder=${placeholder}`);
  });
  await shot('04-saved-config');

  await drainToasts();

  // 4. Save-first test semantics: a dirty form disables the connection test.
  await page.fill('#lt-model', 'stub-model-dirty');
  await check('dirty form disables connection test with explicit warning', async () => {
    if (await page.isEnabled('#lt-test')) throw new Error('test enabled while dirty');
    await expectText('#lt-dirty', 'Hay cambios sin guardar');
  });
  await shot('05-dirty-warning');
  await page.fill('#lt-model', 'stub-model');
  await check('connection test uses the SAVED configuration', async () => {
    if (!(await page.isEnabled('#lt-test'))) throw new Error('test still disabled after reverting the edit');
  });
  await drainToasts();
  await page.click('#lt-test');
  await page.waitForSelector('.alert-success', { timeout: 15_000 });
  await check('connection test succeeds against the stub provider', async () => {
    await expectText('.alert-success', 'Conexión correcta');
  });
  await drainToasts();
  await shot('06-connection-test');

  // 5. Scheduler status: configured OFF must be visible and distinct from
  // in-process activity.
  await check('scheduler configured=false and process activity=false', async () => {
    const body = await page.textContent('body');
    if (!/Configurado\s*\n?\s*No/.test(body)) throw new Error('configured badge not showing "No"');
    if (!/Scheduler activo en este proceso\s*\n?\s*No/.test(body)) throw new Error('in-process badge not showing "No"');
  });

  // 6. Private draft moderation with mandatory audited reason.
  await check('draft listing shows the two seeded drafts', async () => {
    const body = await page.textContent('body');
    if (!body.includes('draft') || !body.includes('A1') || !body.includes('A2'))
      throw new Error('seeded draft rows not visible');
  });
  await page.click('tr:has-text("A1") [data-draft-approve]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'ap');
  await page.click('#reason-ok');
  await check('approval requires a real reason', async () => {
    await page.waitForSelector('.alert-warning', { timeout: 5_000 });
    await expectText('.alert-warning', 'Motivo obligatorio');
  });
  await drainToasts();
  await page.click('tr:has-text("A1") [data-draft-approve]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Aprobado tras revisión E2E de navegador');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('approved draft leaves the private moderation list', async () => {
    // The private list carries draft/rejected rows only: an approved week is
    // published, not pending moderation.
    await page.waitForFunction(
      () => ![...document.querySelectorAll('tr')].some(tr => tr.textContent?.includes('A1')),
      undefined, { timeout: 10_000 });
    const body = await page.textContent('body');
    if (!body.includes('A2')) throw new Error('the pending A2 draft disappeared from the list');
  });
  await shot('07-draft-approved');
  await drainToasts();

  await page.click('tr:has-text("A2") [data-draft-reject]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Rechazado en E2E: contenido insuficiente');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('rejection toast confirms', async () => {
    await expectText('.alert-success', 'Borrador rechazado');
  });
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('draft rejected and rejection retained', async () => {
    const row = await page.textContent('tr:has-text("A2")');
    if (!row.includes('rejected')) throw new Error(`A2 row state: ${row.slice(0, 120)}`);
  });
  await shot('08-draft-rejected');

  // 7. Approval publishes into the public catalog (bounded response check).
  const response = await fetch(`${API}/v1/level-tests/generated?language=en&level=A1&variant=1`);
  await check('approved week serves publicly with identity', async () => {
    if (response.status !== 200) throw new Error(`HTTP ${response.status}`);
    const body = await response.json();
    if (typeof body.examId !== 'string' || !body.examId.length) throw new Error('examId missing');
    if (!/^[a-f0-9]{64}$/.test(body.contentHash ?? '')) throw new Error(`contentHash=${body.contentHash}`);
    if (body.contentId !== `sha256:${body.contentHash}`) throw new Error('contentId does not match contentHash');
    if (!Array.isArray(body.items) || body.items.length !== 25) throw new Error(`items=${body.items?.length}`);
    if (typeof body.thresholds?.overall !== 'number') throw new Error('thresholds missing');
  });
  const rejected = await fetch(`${API}/v1/level-tests/generated?language=en&level=A2&variant=1`);
  await check('rejected week never serves publicly', async () => {
    if (rejected.status !== 404) throw new Error(`HTTP ${rejected.status} for rejected week`);
  });
  await shot('09-public-catalog-checked');
} catch (error) {
  failures.push(`scenario aborted: ${error.message}`);
  await shot('99-aborted').catch(() => {});
} finally {
  await browser.close();
}

if (failures.length) {
  console.error(`\n${failures.length} FAILED check(s):\n - ${failures.join('\n - ')}`);
  process.exit(1);
}
console.log('\nT11 browser E2E: all checks passed.');

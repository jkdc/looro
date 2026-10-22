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
  await page.selectOption('#lt-model', 'gpt-5.6-luna');
  await page.fill('#lt-base-url', 'http://stub-llm:9999/v1');
  await page.fill('#lt-api-key', 'sk-e2e-stub-key-1234567890');
  await page.selectOption('#lt-verify', 'flag');
  await page.fill('#lt-variants', '2');
  await page.click('#lt-save');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('save persists and reload shows stored values with masked key', async () => {
    if ((await page.inputValue('#lt-model')) !== 'gpt-5.6-luna') throw new Error('model not reloaded');
    const variantValue = await page.inputValue('#lt-variants');
    if (variantValue !== '2') throw new Error(`variants=${variantValue}`);
    const placeholder = await page.getAttribute('#lt-api-key', 'placeholder');
    if (!placeholder?.startsWith('Guardada:')) throw new Error(`placeholder=${placeholder}`);
  });
  await shot('04-saved-config');

  await drainToasts();

  // 4. Dirty state shows the pending-exams link; the connection test stays
  // available because it only ever exercises the SAVED configuration.
  await page.selectOption('#lt-model', 'gpt-5.5');
  await check('dirty form links to pending exams and keeps the test available', async () => {
    await expectText('#lt-dirty', 'Hay cambios sin guardar');
    if (!(await page.isEnabled('#lt-test'))) throw new Error('test disabled while dirty');
  });
  await shot('05-dirty-warning');
  await page.selectOption('#lt-model', 'gpt-5.6-luna');
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

  // 6. Generated-exams listing: seeded drafts visible with the regenerate
  // affordance on current-week unpublished rows, audited moderation.
  await check('weeks listing shows the two seeded drafts with filters', async () => {
    await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
    const body = await page.textContent('#exam-weeks-card');
    if (!body.includes('draft') || !body.includes('A1') || !body.includes('A2'))
      throw new Error('seeded draft rows not visible');
    if (!body.includes('Exámenes generados')) throw new Error('listing card title missing');
  });
  await check('current-week draft offers regeneration', async () => {
    if (!(await page.isVisible('tr:has-text("A1") [data-week-regenerate]')))
      throw new Error('Regenerar button missing on current-week draft');
  });
  await page.click('tr:has-text("A1") [data-week-approve]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'ap');
  await page.click('#reason-ok');
  await check('approval requires a real reason', async () => {
    await page.waitForSelector('.alert-warning', { timeout: 5_000 });
    await expectText('.alert-warning', 'Motivo obligatorio');
  });
  await drainToasts();
  await page.click('tr:has-text("A1") [data-week-approve]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Aprobado tras revisión E2E de navegador');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('approved row shows the approved badge in the full listing', async () => {
    await page.waitForFunction(
      () => [...document.querySelectorAll('tr')].some(tr => tr.textContent?.includes('A1') && tr.textContent?.includes('approved')),
      undefined, { timeout: 10_000 });
    if (!(await page.isVisible('tr:has-text("A1") [data-week-approve]')))
      throw new Error('approve still offered for an approved row');
  });
  await shot('07-draft-approved');
  await drainToasts();

  await page.click('tr:has-text("A2") [data-week-reject]');
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

  // 8. Regeneration of the current unpublished week with the jobs monitor.
  await drainToasts();
  await page.click('tr:has-text("A2") [data-week-regenerate]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Regenerado en E2E contra el stub');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('regeneration accepted and jobs monitor renders the run', async () => {
    await expectText('.alert-success', 'Regeneración iniciada');
    // The stub provider answers instantly; wait out any running state.
    await page.waitForFunction(() => {
      const monitor = document.querySelector('#jobs-monitor');
      return monitor && monitor.textContent?.includes('finished');
    }, undefined, { timeout: 120_000 });
    const monitor = await page.textContent('#jobs-monitor');
    if (!monitor.includes('A2')) throw new Error('A2 job row missing from the monitor');
  });
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('regenerated week replaces the rejected row with a fresh draft', async () => {
    const card = await page.textContent('#exam-weeks-card');
    if (!card.includes('A2')) throw new Error('A2 rows missing after regeneration');
  });
  await shot('10-regeneration-monitor');

  // 9. Results section: seeded student attempt, filters, detail modal and the
  // per-student deep link.
  await page.click('a[href="#exam-results"]');
  await page.waitForSelector('#wa-apply', { timeout: 10_000 });
  await check('results summary renders the seeded attempt', async () => {
    const body = await page.textContent('body');
    if (!body.includes('student@example.test')) throw new Error('student row missing');
    if (!body.includes('84 %')) throw new Error('overall score missing');
    if (!body.includes('passed')) throw new Error('passed badge missing');
  });
  await shot('11-results-section');
  await page.fill('#wa-email', 'student');
  await page.click('#wa-apply');
  await page.waitForSelector('#wa-apply', { timeout: 10_000 });
  await check('email filter keeps the matching attempt', async () => {
    const body = await page.textContent('body');
    if (!body.includes('student@example.test')) throw new Error('filtered row missing');
  });
  const attemptHandle = await page.getAttribute('[data-attempt-detail]', 'data-attempt-detail');
  await page.fill('#wa-email', 'nobody@nowhere.test');
  await page.click('#wa-apply');
  await page.waitForSelector('#wa-apply', { timeout: 10_000 });
  await check('non-matching filter shows the empty state', async () => {
    await expectText('body', 'Sin resultados para este filtro');
  });
  await drainToasts();
  await page.fill('#wa-email', '');
  await page.click('#wa-apply');
  await page.waitForSelector('#wa-apply', { timeout: 10_000 });
  await page.click('[data-attempt-detail]');
  await page.waitForSelector('#detail-modal.show', { timeout: 5_000 });
  await check('attempt detail modal shows the per-question review', async () => {
    await expectText('#detail-body', 'questionReviews');
    await expectText('#detail-body', 'overall');
  });
  await shot('12-attempt-detail');
  await page.click('#detail-close');
  const accountId = attemptHandle?.split(':')[0];
  if (accountId) {
    await page.goto(`${PANEL}/#exam-results?account=${accountId}`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#wa-apply', { timeout: 10_000 });
    await check('per-student deep link filters by account', async () => {
      const body = await page.textContent('body');
      if (!body.includes('Alumno:') || !body.includes('student@example.test'))
        throw new Error('per-student header or filtered rows missing');
    });
    await shot('13-per-student-view');
  } else {
    failures.push('per-student deep link skipped: no attempt handle found');
  }
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

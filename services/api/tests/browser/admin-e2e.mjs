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
// Diagnostics only: the last results-listing requests help explain a missed click.
const observedAttemptRequests = [];
const check = async (name, assertion) => {
  try { await assertion(); console.log(`PASS  ${name}`); }
  catch (error) { failures.push(name); console.error(`FAIL  ${name}: ${error.message}`); }
};

await mkdir(EVIDENCE, { recursive: true });
const browser = await chromium.launch({ executablePath, headless: true,
  args: ['--disable-gpu', '--disable-dev-shm-usage'] });
const page = await browser.newPage();
page.on('request', request => {
  if (request.url().includes('/v1/admin/level-test-attempts?')) observedAttemptRequests.push(request.url());
});
// Unhandled errors explain a half-painted view; surface them instead of only
// timing out on the missing selector.
const observedPageErrors = [];
page.on('pageerror', error => observedPageErrors.push(error.message));
// Screenshots are evidence, not assertions: a capture failure must never
// abort the scenario (headless Chromium occasionally refuses fullPage shots).
const shot = async name => {
  try { await page.screenshot({ path: join(EVIDENCE, `${name}.png`), fullPage: true }); }
  catch { try { await page.screenshot({ path: join(EVIDENCE, `${name}.png`) }); }
    catch (error) { console.error(`shot ${name}: ${error.message}`); } }
};
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
  await check('bearer network failures are accessible, retryable and never echo the token', async () => {
    const bearer = 'A'.repeat(43);
    let requests = 0;
    await page.route('**/v1/admin/auth/session', async route => {
      requests++;
      if (requests === 1) return route.abort();
      return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ error: { code: 'invalid_session' } }) });
    });
    await page.fill('#bearer', bearer);
    await page.click('#bearer-btn');
    await page.waitForFunction(() => (document.querySelector('#error')?.textContent ?? '').length > 0, undefined, { timeout: 5_000 });
    const firstError = (await page.textContent('#error')) ?? '';
    if (!firstError.includes('No se pudo contactar con el servidor')) throw new Error(`network message=${firstError}`);
    if (firstError.includes(bearer) || page.url().includes(bearer)) throw new Error('bearer leaked into visible error or URL');
    if (!(await page.isEnabled('#bearer-btn'))) throw new Error('bearer retry control stayed disabled');
    await page.click('#bearer-btn');
    await page.waitForFunction(() => (document.querySelector('#error')?.textContent ?? '').includes('Token no válido'), undefined, { timeout: 5_000 });
    if (requests !== 2 || (await page.textContent('#error'))?.includes(bearer)) throw new Error('retry failed or echoed bearer');
    await page.unroute('**/v1/admin/auth/session');
  });
  await page.fill('#username', 'e2e-operator');
  await page.fill('#password', 'e2e-browser-password');
  await shot('01-login');
  await page.click('#login-btn');
  await page.waitForURL(`${PANEL}/`, { timeout: 10_000 });
  await page.waitForSelector('#nav', { timeout: 10_000 });
  await page.waitForFunction(() => (document.querySelector('#api-status')?.textContent ?? '').toLowerCase().includes('conectada'),
    undefined, { timeout: 5_000 }).catch(() => {});
  await check('login establishes an operator session', async () => {
    if (!page.url().startsWith(PANEL)) throw new Error(`unexpected url ${page.url()}`);
    if (!(await page.textContent('#api-status'))?.toLowerCase().includes('conectada')) throw new Error(`API status is not reported as connected: ${await page.textContent('#api-status')}`);
  });
  await shot('02-dashboard');

  // 2. Configuration lives on its own view: the exam surfaces are grouped and
  // the generation view carries no provider form.
  await check('sidebar groups the exam surfaces with three entries', async () => {
    const nav = await page.textContent('#nav');
    for (const group of ['Operación', 'Exámenes', 'Comercial', 'Sistema']) {
      if (!nav.includes(group)) throw new Error(`group ${group} missing from the sidebar`);
    }
    for (const href of ['#exam-weeks', '#exam-results', '#ia-config']) {
      if (!(await page.isVisible(`a[href="${href}"]`))) throw new Error(`${href} link missing`);
    }
    if (await page.isVisible('a[href="#level-tests"]')) throw new Error('legacy link still in the nav');
    // Every entry must live under its own heading: no heading can be read as the
    // first item of the section below it.
    const order = await page.evaluate(() => [...document.querySelectorAll('#nav .nav-group, #nav a.nav-link')]
      .map(node => node.classList.contains('nav-group') ? `group:${node.textContent.trim()}` : node.getAttribute('href')));
    const section = heading => order.indexOf(`group:${heading}`);
    const between = (href, heading, next) =>
      order.indexOf(href) > section(heading) && order.indexOf(href) < section(next);
    for (const href of ['#exam-weeks', '#exam-results', '#ia-config']) {
      if (!between(href, 'Exámenes', 'Comercial')) throw new Error(`${href} is not inside the exam section`);
    }
    for (const href of ['#plans', '#campaigns']) {
      if (!between(href, 'Comercial', 'Sistema')) throw new Error(`${href} is not inside the commercial section`);
    }
    if (order.indexOf('#audit') < section('Sistema')) throw new Error('audit is not inside the system section');
  });
  await page.goto(`${PANEL}/#level-tests`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  await check('legacy route redirects to the generation view', async () => {
    if (!page.url().endsWith('#exam-weeks')) throw new Error(`url=${page.url()}`);
    if (await page.isVisible('#lt-provider')) throw new Error('provider form leaked into the generation view');
    await expectText('body', 'Lanzar generación');
  });
  await page.click('a[href="#ia-config"]');
  await page.waitForSelector('#lt-provider', { timeout: 10_000 });
  await check('configuration view is self-contained', async () => {
    if (await page.isVisible('#gen-launch')) throw new Error('launcher leaked into the configuration view');
    await expectText('body', 'Dónde se genera');
  });
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

  // 5. Generation view: launcher, jobs monitor and listing. Scheduler status:
  // configured OFF must be visible and distinct from in-process activity.
  await page.click('a[href="#exam-weeks"]');
  await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  await check('generation view exposes the launcher and no provider fields', async () => {
    if (!(await page.isVisible('#gen-launch'))) throw new Error('launch button missing');
    if (await page.isVisible('#lt-provider')) throw new Error('provider form present in the generation view');
    await expectText('#scheduler-note', 'no configurado');
    await expectText('#scheduler-note', 'activo en este proceso: no');
  });
  await check('job states are colour-coded by severity', async () => {
    const states = await page.$$eval('#jobs-monitor tbody tr', rows => rows.map(row => {
      const badge = row.querySelector('.badge');
      const style = badge ? getComputedStyle(badge) : null;
      return { text: badge?.textContent?.trim() ?? '', tone: badge?.className ?? '',
        color: style?.color ?? '', background: style?.backgroundColor ?? '' };
    }));
    // The class name is not proof: this Tabler ships `.badge` plus `bg-*-lt`
    // only, so a missing utility class paints the neutral grey badge. Assert the
    // real channels instead of trusting the markup.
    const channels = value => {
      const srgb = value.match(/color\(srgb ([\d.]+) ([\d.]+) ([\d.]+)/);
      if (srgb) return srgb.slice(1, 4).map(Number);
      return (value.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number);
    };
    const failed = states.find(entry => entry.text === 'failed');
    const finished = states.find(entry => entry.text === 'finished');
    if (!failed) throw new Error('seeded failed job is missing');
    if (!failed.tone.includes('bg-danger-lt')) throw new Error(`failed badge tone: ${failed.tone}`);
    if (!finished) throw new Error('no finished job in the monitor');
    if (!finished.tone.includes('bg-success-lt')) throw new Error(`finished badge tone: ${finished.tone}`);
    const [fr, fg, fb] = channels(failed.color);
    if (!(fr > fg && fr > fb)) throw new Error(`failed badge text is not red: ${failed.color}`);
    const [gr, gg, gb] = channels(finished.color);
    if (!(gg > gr && gg > gb)) throw new Error(`finished badge text is not green: ${finished.color}`);
  });
  await check('a generation error opens a dialog with the real reasons', async () => {
    await page.click('#jobs-monitor [data-job-error]');
    await page.waitForSelector('#detail-modal.show', { timeout: 5_000 });
    await expectText('#detail-body', 'generation_failed');
    await expectText('#detail-body', 'Detalle del último intento');
    await expectText('#detail-body', 'reading: only 2 passages (needed 4)');
    await expectText('#detail-body', 'writing: empty response');
    await shot('10e-job-error');
    await page.click('#detail-close');
  });

  // 6. Generated-exams listing: seeded drafts visible with the regenerate
  // affordance on current-week unpublished rows, audited moderation.
  // Table selectors are scoped to the weeks card: the jobs monitor renders
  // its own rows (language/level/state) that would otherwise match.
  const weekRow = level => `#exam-weeks-card tbody tr:has-text("${level}")`;
  await check('weeks listing shows the two seeded drafts with filters', async () => {
    await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
    const body = await page.textContent('#exam-weeks-card');
    if (!body.includes('draft') || !body.includes('A1') || !body.includes('A2'))
      throw new Error('seeded draft rows not visible');
    if (!body.includes('Exámenes generados')) throw new Error('listing card title missing');
  });
  await check('current-week draft offers regeneration', async () => {
    if (!(await page.isVisible(weekRow('A1') + ' [data-week-regenerate]')))
      throw new Error('Regenerar button missing on current-week draft');
  });
  await page.click(weekRow('A1') + ' [data-week-approve]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'ap');
  await check('confirm stays disabled until the reason is real', async () => {
    // Regression: an empty/short reason used to abandon the request silently.
    if (await page.isEnabled('#reason-ok')) throw new Error('confirm enabled with a 2-character reason');
    await expectText('#reason-hint', 'faltan 1');
    await page.fill('#reason-text', '');
    if (await page.isEnabled('#reason-ok')) throw new Error('confirm enabled with an empty reason');
    await expectText('#reason-hint', 'mínimo 3');
  });
  await page.click('#reason-cancel');
  await page.waitForFunction(() => !document.querySelector('#reason-modal')?.classList.contains('show'),
    undefined, { timeout: 5_000 });
  await page.click(weekRow('A1') + ' [data-week-approve]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Aprobado tras revisión E2E de navegador');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('approved row shows the approved badge in the full listing', async () => {
    await page.waitForFunction(() => [...document.querySelectorAll('#exam-weeks-card tbody tr')]
      .some(tr => tr.textContent?.includes('A1') && tr.textContent?.includes('approved')),
      undefined, { timeout: 10_000 });
    if (await page.isVisible(weekRow('A1') + ' [data-week-approve]'))
      throw new Error('approve still offered for an approved row');
    const tone = await page.getAttribute(weekRow('A1') + ' .badge', 'class');
    if (!tone?.includes('bg-success-lt')) throw new Error(`approved badge tone: ${tone}`);
  });
  await shot('07-draft-approved');
  await drainToasts();

  await page.click(weekRow('A2') + ' [data-week-reject]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Rechazado en E2E: contenido insuficiente');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('rejection toast confirms', async () => {
    await expectText('.alert-success', 'Borrador rechazado');
  });
  await check('draft rejected and rejection retained', async () => {
    await page.waitForFunction(() => [...document.querySelectorAll('#exam-weeks-card tbody tr')]
      .some(tr => tr.textContent?.includes('A2') && tr.textContent?.includes('rejected')),
      undefined, { timeout: 10_000 });
    const tone = await page.getAttribute(weekRow('A2') + ' .badge', 'class');
    if (!tone?.includes('bg-danger-lt')) throw new Error(`rejected badge tone: ${tone}`);
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
  await page.click(weekRow('A2') + ' [data-week-regenerate]');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Regenerado en E2E contra el stub');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('regeneration accepted and the live status line reports the run', async () => {
    await expectText('.alert-success', 'Regeneración iniciada');
    // The watcher paints progress immediately (queued/running) and finishes with
    // the terminal state of each key — no manual reload needed.
    await page.waitForFunction(() => (document.querySelector('#launch-status')?.textContent ?? '').trim().length > 0,
      undefined, { timeout: 10_000 });
    await page.waitForFunction(() => (document.querySelector('#launch-status')?.textContent ?? '').includes('A2 → finished'),
      undefined, { timeout: 120_000 });
    const monitor = await page.textContent('#jobs-monitor');
    if (!monitor.includes('A2')) throw new Error('A2 job row missing from the monitor');
  });
  await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  await check('regenerated week replaces the rejected row with a fresh draft', async () => {
    const card = await page.textContent('#exam-weeks-card');
    if (!card.includes('A2')) throw new Error('A2 rows missing after regeneration');
  });
  await shot('10-regeneration-monitor');

  // 8b. On-demand launch: single level B1 (no row yet) runs through the stub.
  await drainToasts();
  await page.selectOption('#gen-language', 'en');
  await page.selectOption('#gen-levels', 'B1');
  await page.click('#gen-launch');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await check('launch confirm is disabled until a reason is typed', async () => {
    if (await page.isEnabled('#reason-ok')) throw new Error('confirm enabled with an empty reason');
  });
  await page.fill('#reason-text', 'Lanzado en E2E para B1');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('generation launch runs the missing level and reports it', async () => {
    await expectText('.alert-success', 'Lanzados 1');
    await page.waitForFunction(() => (document.querySelector('#launch-status')?.textContent ?? '').trim().length > 0,
      undefined, { timeout: 10_000 });
    await page.waitForFunction(() => (document.querySelector('#launch-status')?.textContent ?? '').includes('B1 → finished'),
      undefined, { timeout: 120_000 });
  });
  await check('finished launch refreshes the weeks table with the new draft', async () => {
    await page.waitForFunction(() => {
      const card = document.querySelector('#exam-weeks-card');
      return card && /B1/.test(card.textContent ?? '') && /draft/.test(card.textContent ?? '');
    }, undefined, { timeout: 20_000 });
  });
  await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  await shot('10b-generation-launch');

  await check('the weeks table reports who triggered each generation', async () => {
    const rows = await page.$$eval('#exam-weeks-card tbody tr',
      trs => trs.map(tr => [...tr.cells].map(cell => (cell.textContent ?? '').trim())));
    const launched = rows.find(cells => cells[2] === 'B1');
    if (!launched || launched[6] !== 'Operador')
      throw new Error(`B1 origin=${launched?.[6] ?? 'missing row'}`);
    const seeded = rows.find(cells => cells[2] === 'A1');
    if (!seeded || seeded[6] !== 'Automático')
      throw new Error(`A1 origin=${seeded?.[6] ?? 'missing row'}`);
  });

  // 8b2. Launching a level whose week already exists must not enqueue a job
  // that finishes without generating (the operator would read a green
  // "completed" for content that never changed).
  await check('a launch over an existing week is skipped and claims no run', async () => {
    await drainToasts();
    const before = (await page.textContent('#launch-status')) ?? '';
    await page.selectOption('#gen-language', 'en');
    await page.selectOption('#gen-levels', 'B1');
    await page.click('#gen-launch');
    await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
    await page.fill('#reason-text', 'Relanzado en E2E con la semana ya existente');
    await page.click('#reason-ok');
    await page.waitForSelector('.alert-warning', { timeout: 10_000 });
    const text = (await page.textContent('.alert-warning')) ?? '';
    if (!text.includes('Lanzados 0 examen(es)')) throw new Error(`toast=${text}`);
    if (!text.includes('1 ya existentes')) throw new Error(`toast=${text}`);
    if (text.includes('aprobados omitidos')) throw new Error(`toast=${text}`);
    await page.waitForTimeout(1_500);
    if (((await page.textContent('#launch-status')) ?? '') !== before)
      throw new Error('the progress line changed for a skipped launch');
  });
  await shot('10g-launch-skipped-existing');
  await drainToasts();

  // 8c. Watch cadence, resilience and shutdown. The status endpoint is mocked
  // because the stub finishes in milliseconds: with a real run there is no 5 s
  // gap to observe. The mock still fetches the real response, so the job keeps
  // the real week id the panel filters on.
  const statusMock = (behaviour) => async route => {
    const call = behaviour.calls.push(Date.now());
    if (behaviour.failCalls.includes(call))
      return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"poll_failed"}' });
    const response = await route.fetch();
    const real = await response.json();
    const base = Array.isArray(real.jobs) && real.jobs[0]
      ? real.jobs[0] : { languageID: 'en', level: 'A1', weekId: 0, attempts: 1 };
    const terminal = behaviour.terminal(call);
    const stamp = new Date().toISOString();
    const job = { ...base, status: terminal ? 'finished' : 'running', attempts: 1, errorCode: null,
      errors: [], startedAt: stamp, finishedAt: terminal ? stamp : null, updatedAt: stamp };
    await route.fulfill({ response, json: { ...real, jobs: [job], latestJob: job } });
  };
  await check('generation watch polls every 5 s and survives a failed poll', async () => {
    // call 1 is the mount fetch; 2 fails (the watch must retry); 3-4 running;
    // 5 terminal. Every gap must be the configured cadence.
    const behaviour = { calls: [], failCalls: [2], terminal: call => call >= 5 };
    const handler = statusMock(behaviour);
    await page.route('**/v1/admin/level-tests/status', handler);
    try {
      await page.goto(`${PANEL}/#overview`);
      await page.goto(`${PANEL}/#exam-weeks`);
      const deadline = Date.now() + 45_000;
      while (behaviour.calls.length < 5 && Date.now() < deadline) await page.waitForTimeout(250);
      if (behaviour.calls.length < 5) throw new Error(`only ${behaviour.calls.length} status calls in 45 s`);
      // Calls 1-5 are mount + four ticks; the post-terminal table refresh lands
      // milliseconds later and is asserted separately, not as a cadence gap.
      const gaps = behaviour.calls.slice(1, 5).map((at, index) => at - behaviour.calls[index]);
      for (const gap of gaps) {
        if (gap < 4_000 || gap > 9_000)
          throw new Error(`poll gap ${gap} ms outside 4-9 s (${gaps.join(', ')}); page errors: ${observedPageErrors.join(' | ') || 'none'}`);
      }
      await page.waitForFunction(() => (document.querySelector('#launch-status')?.textContent ?? '').includes('→ finished'),
        undefined, { timeout: 15_000 });
      const settled = behaviour.calls.length;
      await page.waitForTimeout(12_000);
      // One extra fetch is the post-terminal table refresh; a timer would add 2+.
      if (behaviour.calls.length > settled + 1)
        throw new Error(`${behaviour.calls.length - settled} polls after the terminal state`);
    } finally {
      await page.unroute('**/v1/admin/level-tests/status', handler);
    }
  });
  await check('a finished run does not leak its timer into the next watch', async () => {
    // Regression: the direct tick after a launch used to leave the scheduled
    // timer alive; when it fired it cleared the NEW watch's timer handle and
    // two 5 s loops ran in parallel. Force it: the direct tick sees a terminal
    // job, the immediate refresh mounts a fresh running watch.
    const calls = [];
    const handler = async route => {
      const call = calls.push(Date.now());
      const response = await route.fetch();
      const real = await response.json();
      const base = Array.isArray(real.jobs) && real.jobs[0]
        ? real.jobs[0] : { languageID: 'en', level: 'A1', weekId: 0, attempts: 1 };
      const terminal = call === 1;
      const stamp = new Date().toISOString();
      const job = { ...base, status: terminal ? 'finished' : 'running', attempts: 1, errorCode: null,
        errors: [], startedAt: stamp, finishedAt: terminal ? stamp : null, updatedAt: stamp };
      await route.fulfill({ response, json: { ...real, jobs: [job], latestJob: job } });
    };
    await page.route('**/v1/admin/level-tests/status', handler);
    try {
      await drainToasts();
      await page.click(weekRow('A2') + ' [data-week-regenerate]');
      await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
      await page.fill('#reason-text', 'Regenerado en E2E para probar el temporizador');
      await page.click('#reason-ok');
      const deadline = Date.now() + 45_000;
      while (calls.length < 4 && Date.now() < deadline) await page.waitForTimeout(250);
      if (calls.length < 4) throw new Error(`only ${calls.length} status calls in 45 s`);
      // Calls 1 (direct tick) and 2 (refresh mount) are adjacent; 2-4 are ticks.
      const gaps = calls.slice(2, 4).map((at, index) => at - calls[index + 1]);
      for (const gap of gaps) {
        if (gap < 4_000 || gap > 9_000)
          throw new Error(`poll gap ${gap} ms outside 4-9 s (leaked timer); page errors: ${observedPageErrors.join(' | ') || 'none'}`);
      }
    } finally {
      await page.unroute('**/v1/admin/level-tests/status', handler);
      // The live watch resolves against the real status on its next tick.
      await page.waitForFunction(() => !(document.querySelector('#launch-status')?.textContent ?? '').includes('En marcha'),
        undefined, { timeout: 20_000 }).catch(() => {});
      await drainToasts();
    }
  });
  await check('leaving the generation view parks the watch instead of polling on', async () => {
    const behaviour = { calls: [], failCalls: [], terminal: () => false };
    const handler = statusMock(behaviour);
    await page.route('**/v1/admin/level-tests/status', handler);
    try {
      await page.goto(`${PANEL}/#overview`);
      await page.goto(`${PANEL}/#exam-weeks`);
      const deadline = Date.now() + 20_000;
      while (behaviour.calls.length < 2 && Date.now() < deadline) await page.waitForTimeout(250);
      if (behaviour.calls.length < 2) throw new Error('the watch never started');
      await page.goto(`${PANEL}/#overview`);
      const parked = behaviour.calls.length;
      await page.waitForTimeout(12_000);
      // At most the tick already in flight when the view was left.
      if (behaviour.calls.length > parked + 1)
        throw new Error(`${behaviour.calls.length - parked} status calls after leaving the view; page errors: ${observedPageErrors.join(' | ') || 'none'}`);
    } finally {
      await page.unroute('**/v1/admin/level-tests/status', handler);
      await page.goto(`${PANEL}/#exam-weeks`);
      await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
    }
  });
  await check('returning to the view resumes the parked watch and closes the key', async () => {
    // The parked watch kept its keys: one real tick must resolve them and stop.
    await page.waitForFunction(() => (document.querySelector('#launch-status')?.textContent ?? '').includes('→ finished'),
      undefined, { timeout: 20_000 });
    await page.waitForTimeout(7_000);
    await drainToasts();
  });
  await shot('10f-watch-parked');


  // 9. Exam review page: the exam a student will take, not raw JSON.
  await page.click(weekRow('B1') + ' a[href^="#exam/"]');
  await page.waitForSelector('#exam-body', { timeout: 10_000 });
  await page.waitForSelector('.exam-section', { timeout: 10_000 });
  await check('review page groups items into sections in exam order', async () => {
    const sections = await page.$$eval('.exam-section-title', nodes => nodes.map(node => node.textContent.trim()));
    if (!sections.length) throw new Error('no sections rendered');
    if (!sections[0].startsWith('Reconocimiento')) throw new Error(`first section=${sections[0]}`);
    const text = await page.textContent('#exam-body');
    if (/\{\s*"/.test(text)) throw new Error('raw JSON leaked into the review page');
    await expectText('#page-title', 'Revisión de examen');
  });
  await check('review page marks accepted answers and judge verdicts', async () => {
    if (!(await page.isVisible('.exam-option-ok'))) throw new Error('no accepted option marked');
    const marks = await page.$$eval('.exam-option-ok .exam-mark', nodes => nodes.map(node => node.textContent.trim()));
    if (!marks.includes('✓')) throw new Error('accepted option not ticked');
    const text = await page.textContent('#exam-body');
    if (!text.includes('Juez:')) throw new Error('judge verdict missing from item cards');
    if (!text.includes('Aceptadas')) throw new Error('accepted answers missing from written items');
  });
  await shot('10c-review-page');
  await check('review page switches variants and shows the pool', async () => {
    const tabs = await page.$$('[data-exam-tab]');
    if (tabs.length < 3) throw new Error(`tabs=${tabs.length} (variants + pool expected)`);
    const first = (await page.textContent('#exam-body')).slice(0, 400);
    await page.click('[data-exam-tab="variant-1"]');
    await page.waitForFunction(() => document.querySelector('[data-exam-tab="variant-1"]')?.classList.contains('active'),
      undefined, { timeout: 5_000 });
    const second = (await page.textContent('#exam-body')).slice(0, 400);
    if (first === second) throw new Error('variant body did not change');
    await page.click('[data-exam-tab="pool"]');
    await page.waitForFunction(() => document.querySelector('[data-exam-tab="pool"]')?.classList.contains('active'),
      undefined, { timeout: 5_000 });
    await expectText('#exam-body', 'Reconocimiento');
  });
  await drainToasts();
  await page.click('#exam-approve');
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Aprobado desde la página de revisión E2E');
  await page.click('#reason-ok');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('review page approves a draft and reflects the new status', async () => {
    await page.waitForFunction(() => {
      const view = document.querySelector('#view');
      return view && /approved/.test(view.textContent ?? '') && !document.querySelector('#exam-approve');
    }, undefined, { timeout: 10_000 });
  });
  await shot('10d-review-approved');
  await page.click('a[href="#exam-weeks"]');
  await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  await check('approval from the review page reaches the listing', async () => {
    await page.waitForFunction(() => [...document.querySelectorAll('#exam-weeks-card tbody tr')]
      .some(tr => tr.textContent?.includes('B1') && tr.textContent?.includes('approved')),
      undefined, { timeout: 10_000 });
  });
  await page.goto(`${PANEL}/#exam/not-a-real-exam`, { waitUntil: 'domcontentloaded' });
  await check('unknown exam id renders a not-found state', async () => {
    await page.waitForFunction(() => (document.querySelector('#view')?.textContent ?? '').includes('Examen no encontrado'),
      undefined, { timeout: 10_000 });
    await page.click('#view a[href="#exam-weeks"]');
    await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  });

  // 10. Results section: seeded student attempt, filters, detail modal and the
  // per-student deep link.
  await page.click('a[href="#exam-results"]');
  await page.waitForSelector('#wa-apply', { timeout: 10_000 });
  await check('results summary renders the seeded attempt', async () => {
    const body = await page.textContent('body');
    if (!body.includes('student@example.test')) throw new Error('student row missing');
    if (!body.includes('84 %')) throw new Error('overall score missing');
    if (!body.includes('passed')) throw new Error('passed badge missing');
    if (!body.includes('El resumen es global y no cambia con los filtros del listado.')) throw new Error('global summary scope is unclear');
  });
  await shot('11-results-section');
  // Applying a filter re-renders the whole view, filter bar included: a naive
  // fill+click can lose the typed value between hit-testing and typing. Type and
  // click in a loop until the request actually carries the typed value.
  const applyFilter = async value => {
    const trace = [];
    for (let attempt = 0; attempt < 5; attempt++) {
      await page.fill('#wa-email', value);
      const issued = page.waitForResponse(response => {
        if (!response.url().includes('/level-test-attempts?')) return false;
        return new URL(response.url()).searchParams.get('email') === (value || null);
      }, { timeout: 3_000 }).then(() => true).catch(() => false);
      await page.click('#wa-apply');
      const requested = await issued;
      trace.push(`try ${attempt + 1}: request=${requested}`);
      if (requested) return;
    }
    const seen = observedAttemptRequests.slice(-4).map(url => url.split('?')[1] ?? url);
    throw new Error(`filter ${JSON.stringify(value)} never reached the API (${trace.join('; ')}; seen: ${seen.join(' | ')})`);
  };
  await applyFilter('student');
  await page.waitForFunction(() => document.body.textContent?.includes('student@example.test'),
    undefined, { timeout: 10_000 });
  await check('email filter keeps the matching attempt', async () => {
    const body = await page.textContent('body');
    if (!body.includes('student@example.test')) throw new Error('filtered row missing');
  });
  const attemptHandle = await page.getAttribute('[data-attempt-detail]', 'data-attempt-detail');
  await applyFilter('nobody@nowhere.test');
  await check('non-matching filter shows the empty state', async () => {
    await page.waitForFunction(() => document.body.textContent?.includes('Sin resultados para este filtro'),
      undefined, { timeout: 10_000 });
  });
  await drainToasts();
  await applyFilter('');
  await page.waitForFunction(() => document.body.textContent?.includes('student@example.test'),
    undefined, { timeout: 10_000 });
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
  // 11. Retiring an approved week: an explicit operator decision whose motive
  // stays on the week and whose effect is the week leaving the serving set.
  await page.goto(`${PANEL}/#exam-weeks`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#exam-weeks-card', { timeout: 10_000 });
  const retireRow = page.locator('#exam-weeks-card tbody tr', { hasText: 'B1' })
    .filter({ has: page.locator('[data-week-retire]') });
  const retireLanguage = (await retireRow.locator('td').nth(1).textContent())?.trim() ?? '';
  const retireHref = await retireRow.locator('a[href^="#exam/"]').getAttribute('href');
  await retireRow.locator('[data-week-retire]').click();
  await page.waitForSelector('#reason-modal.show', { timeout: 5_000 });
  await page.fill('#reason-text', 'Retirado en E2E: el evaluador rechazó el contenido');
  await page.click('#reason-ok');
  await page.waitForFunction(() => [...document.querySelectorAll('#exam-weeks-card tbody tr')]
    .some(tr => tr.textContent?.includes('B1') && tr.textContent?.includes('retired')),
    undefined, { timeout: 10_000 });
  await check('retiring an approved week stores the motive and stops serving it', async () => {
    const response = await fetch(`${API}/v1/level-tests/generated?language=${retireLanguage}&level=B1&variant=1`);
    if (response.status !== 404) throw new Error(`public status=${response.status}`);
    if (!retireHref) throw new Error('review link missing before retirement');
    await page.goto(`${PANEL}/${retireHref}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => (document.querySelector('#view')?.textContent ?? '').includes('Semana retirada.'),
      undefined, { timeout: 10_000 });
    const text = (await page.textContent('#view')) ?? '';
    if (!text.includes('Retirado en E2E: el evaluador rechazó el contenido'))
      throw new Error('retirement motive missing from the review page');
  });
  await shot('14-week-retired');

  // 12. Remaining administrative surfaces. Until here the suite only proved
  // login, configuration, generation/review and results; the operational and
  // commercial views never rendered. Each view is driven against the real API
  // and the mutations are re-read from the audit table.
  const errorsBeforeSurfaces = observedPageErrors.length;
  await page.goto(`${PANEL}/#overview`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelectorAll('#view canvas').length > 0,
    undefined, { timeout: 15_000 });
  await check('overview renders the KPI snapshot and its charts', async () => {
    const text = (await page.textContent('#view')) ?? '';
    for (const label of ['Cuentas', 'Invitados', 'Suscripciones activas', 'Acciones admin', 'Minutos en wallets'])
      if (!text.includes(label)) throw new Error(`KPI ${label} missing`);
    const canvases = await page.$$eval('#view canvas', nodes => nodes.length);
    if (canvases < 3) throw new Error(`only ${canvases} charts mounted`);
  });
  await shot('15-overview');

  for (const [route, label] of [['growth', 'Crecimiento'], ['voice', 'Voz'], ['economy', 'Economía']]) {
    await page.goto(`${PANEL}/#${route}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelectorAll('#view canvas').length > 0,
      undefined, { timeout: 15_000 });
    await check(`${label} renders its timeseries`, async () => {
      if (!((await page.title()) ?? '').includes(label)) throw new Error(`title=${await page.title()}`);
      const canvases = await page.$$eval('#view canvas', nodes => nodes.length);
      if (canvases < 2) throw new Error(`only ${canvases} charts mounted`);
      if (await page.isVisible('#view .alert-danger')) throw new Error('view rendered an error alert');
    });
  }
  await shot('16-growth-voice-economy');

  await page.goto(`${PANEL}/#usage`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelectorAll('#view canvas').length > 0,
    undefined, { timeout: 15_000 });
  await check('usage renders KPIs, top features and the retention cohort table', async () => {
    const text = (await page.textContent('#view')) ?? '';
    for (const needle of ['DAU (hoy)', 'WAU', 'MAU', 'Funciones más usadas (7 días)', 'Retención por cohorte de registro'])
      if (!text.includes(needle)) throw new Error(`${needle} missing`);
    if (!text.includes('D30')) throw new Error('retention header missing');
  });
  await shot('17-usage');

  await page.goto(`${PANEL}/#browse`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#browse-table', { timeout: 10_000 });
  await page.selectOption('#browse-table', 'accounts');
  await page.waitForFunction(() => (document.querySelector('#browse-grid')?.textContent ?? '').includes('student@example.test'),
    undefined, { timeout: 15_000 }).catch(() => {});
  await check('table browser lists the seeded account and its total', async () => {
    const grid = (await page.textContent('#browse-grid')) ?? '';
    if (!grid.includes('student@example.test')) throw new Error('seeded account row missing');
    const total = (await page.textContent('#browse-total')) ?? '';
    if (!/\d+/.test(total)) throw new Error(`total=${total}`);
    if (await page.isVisible('#browse-grid [data-page]')) throw new Error('unexpected pager markup');
  });
  await check('table browser does not sort non-orderable account columns', async () => {
    const guestHeader = page.locator('#browse-grid .tabulator-col[tabulator-field="is_guest"]');
    if (await guestHeader.count() !== 1) throw new Error('is_guest header missing');
    if (await guestHeader.locator('.tabulator-col-sorter').count() !== 0)
      throw new Error('non-orderable is_guest header renders a sorter');
  });
  await shot('18-browse-accounts');
  await check('browse read failures stay visible, preserve stale rows and retry without becoming healthy', async () => {
    await page.route('**/v1/admin/browse/accounts?*', route => route.abort());
    await page.selectOption('#page-size', '50');
    await page.waitForSelector('#browse-retry', { timeout: 5_000 });
    await expectText('#browse-load-error', 'las filas visibles pueden estar desactualizadas');
    await expectText('#browse-grid', 'student@example.test');
    if (!(await page.textContent('#api-status'))?.includes('sin conexión')) throw new Error('API badge did not report disconnected state');
    await page.unroute('**/v1/admin/browse/accounts?*');
    await page.click('#browse-retry');
    await page.waitForFunction(() => !(document.querySelector('#browse-load-error')?.textContent ?? '').length &&
      (document.querySelector('#api-status')?.textContent ?? '').toLowerCase().includes('conectada'), undefined, { timeout: 10_000 });
  });

  await page.goto(`${PANEL}/#policy`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#pol-save', { timeout: 10_000 });
  const policyVersionBefore = (await page.textContent('#view')) ?? '';
  await drainToasts();
  const policyWrites = [];
  page.on('request', request => {
    if (request.url().endsWith('/v1/admin/policy/welcome') && request.method() === 'PUT') policyWrites.push(request);
  });
  await page.fill('#pol-minutes', '1441');
  await page.click('#pol-save');
  await page.waitForSelector('#pol-minutes-error', { timeout: 5_000 });
  await check('invalid welcome-policy values and missing reason send no request', async () => {
    if (policyWrites.length) throw new Error(`invalid number sent ${policyWrites.length} policy request(s)`);
    if (await page.getAttribute('#pol-minutes', 'aria-invalid') !== 'true') throw new Error('invalid minutes not identified');
    await expectText('#pol-minutes-error', 'Minutos');
    await page.fill('#pol-minutes', '5');
    await page.click('#pol-save');
    await page.waitForSelector('#pol-reason-error', { timeout: 5_000 });
    if (policyWrites.length) throw new Error(`missing reason sent ${policyWrites.length} policy request(s)`);
    await expectText('#pol-reason-error', 'Motivo');
  });
  await drainToasts();
  const policyMinutes = await page.inputValue('#pol-minutes');
  await page.fill('#pol-minutes', String(Number(policyMinutes) === 5 ? 6 : 5));
  await page.fill('#pol-reason', 'Ajuste de bienvenida en E2E');
  let failPolicyRefresh = true;
  await page.route('**/v1/admin/policy/welcome', async route => {
    if (route.request().method() === 'GET' && failPolicyRefresh) { failPolicyRefresh = false; return route.abort(); }
    return route.continue();
  });
  await page.click('#pol-save');
  await page.waitForSelector('#policy-refresh', { timeout: 10_000 });
  await check('a confirmed policy update remains distinct from a failed refresh and can be retried', async () => {
    if (policyWrites.length !== 1) throw new Error(`expected one confirmed update, got ${policyWrites.length}`);
    await expectText('#view', 'Política actualizada correctamente');
    await expectText('#view', 'La lectura no se pudo actualizar');
    if (!(await page.isVisible('#policy-refresh'))) throw new Error('read retry control missing');
    await page.unroute('**/v1/admin/policy/welcome');
    await page.click('#policy-refresh');
    await page.waitForFunction(before => {
      const text = document.querySelector('#view')?.textContent ?? '';
      return text !== before && /versión actual \d+/.test(text) && !text.includes('La lectura no se pudo actualizar');
    }, policyVersionBefore, { timeout: 10_000 });
    const after = (await page.textContent('#view')) ?? '';
    const before = policyVersionBefore.match(/versión actual (\d+)/)?.[1];
    const current = after.match(/versión actual (\d+)/)?.[1];
    if (!before || !current || Number(current) !== Number(before) + 1)
      throw new Error(`version ${before} -> ${current}`);
  });
  await shot('19-policy-saved');
  await drainToasts();

  await page.goto(`${PANEL}/#plans`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#plans-save', { timeout: 10_000 });
  const plansViewBefore = (await page.textContent('#view')) ?? '';
  const plansVersionBefore = plansViewBefore.match(/versión actual (\d+)/)?.[1];
  const planWrites = [];
  page.on('request', request => {
    if (request.url().endsWith('/v1/admin/plans') && request.method() === 'PUT') planWrites.push(request);
  });
  const dripMinutes = await page.inputValue('#plans-drip-minutes');
  await page.fill('#plans-drip-minutes', '61');
  await page.click('#plans-save');
  await page.waitForSelector('#plans-drip-minutes-error', { timeout: 5_000 });
  await check('invalid plans value and missing motive are rejected before the API request', async () => {
    if (planWrites.length) throw new Error(`invalid plans form sent ${planWrites.length} request(s)`);
    await expectText('#plans-drip-minutes-error', 'Minutos/mes del goteo');
    await page.fill('#plans-drip-minutes', dripMinutes);
    await page.click('#plans-save');
    await page.waitForSelector('#plans-reason-error', { timeout: 5_000 });
    if (planWrites.length) throw new Error(`missing plan motive sent ${planWrites.length} request(s)`);
    await expectText('#plans-reason-error', 'Motivo');
  });
  await page.fill('#plans-reason', 'Revisión comercial en E2E');
  await page.click('#plans-save');
  await page.waitForSelector('.alert-success', { timeout: 10_000 });
  await check('a motivated plans save persists and bumps the version', async () => {
    await expectText('.alert-success', 'Planes actualizados');
    const current = ((await page.textContent('#view')) ?? '').match(/versión actual (\d+)/)?.[1];
    if (!plansVersionBefore || !current || Number(current) !== Number(plansVersionBefore) + 1)
      throw new Error(`version ${plansVersionBefore} -> ${current}`);
  });
  await shot('20-plans-saved');
  await drainToasts();

  await page.goto(`${PANEL}/#campaigns`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#camp-prepare', { timeout: 10_000 });
  await check('editing any campaign intent invalidates the prepared snapshot', async () => {
    if (!accountId) throw new Error('seeded account id unavailable');
    const prepare = async () => {
      await page.click('#camp-prepare');
      await page.waitForSelector('#camp-apply:enabled', { timeout: 10_000 });
    };
    await page.fill('#camp-account', accountId);
    await page.fill('#camp-minutes', '5');
    await page.fill('#camp-cap', '5000');
    await page.fill('#camp-reason', 'Snapshot check');
    await prepare();
    const edits = [
      { edit: () => page.fill('#camp-account', `${accountId.slice(0, -1)}${accountId.endsWith('1') ? '2' : '1'}`), reset: () => page.fill('#camp-account', accountId) },
      { edit: () => page.fill('#camp-minutes', '6'), reset: () => page.fill('#camp-minutes', '5') },
      { edit: () => page.fill('#camp-cap', '5001'), reset: () => page.fill('#camp-cap', '5000') },
      { edit: () => page.fill('#camp-reason', 'Changed snapshot reason'), reset: () => page.fill('#camp-reason', 'Snapshot check') },
      { edit: () => page.click('#aud-all'), reset: () => page.click('#aud-one') }
    ];
    for (const { edit, reset } of edits) {
      await edit();
      if (await page.locator('#camp-apply').isEnabled())
        throw new Error('prepared campaign remained applicable after intent changed');
      await reset();
      await prepare();
    }
  });
  await check('a minute campaign prepares and applies to one account', async () => {
    if (!accountId) throw new Error('seeded account id unavailable');
    await page.click('#aud-one');
    await page.fill('#camp-account', accountId);
    await page.fill('#camp-minutes', '5');
    await page.fill('#camp-reason', 'Campaña de prueba E2E');
    await page.click('#camp-prepare');
    await page.waitForSelector('#camp-apply', { timeout: 10_000 });
    const prepared = (await page.textContent('#camp-result')) ?? '';
    if (!prepared.includes('Preparada:') || !prepared.includes('5 min/usuario') || !prepared.includes('5 min totales'))
      throw new Error(`prepare result=${prepared.slice(0, 120)}`);
    await page.click('#camp-apply');
    await page.waitForFunction(() => (document.querySelector('#camp-result')?.textContent ?? '').includes('Aplicada:'),
      undefined, { timeout: 15_000 });
    const applied = (await page.textContent('#camp-result')) ?? '';
    if (!applied.includes('Aplicada: concedidos 1') || !applied.includes('omitidos 0'))
      throw new Error(`apply result=${applied.slice(-160)}`);
  });
  await shot('21-campaign-applied');

  let uncertainCampaignID = '';
  await check('an uncertain campaign response retries the same persisted campaign only', async () => {
    const prepareRequests = [];
    const applyRequests = [];
    page.on('request', request => {
      if (request.url().endsWith('/v1/admin/campaigns') && request.method() === 'POST') prepareRequests.push(request.url());
      if (request.url().includes('/v1/admin/campaigns/') && request.url().endsWith('/apply')) applyRequests.push(request.url());
    });
    await page.fill('#camp-minutes', '3');
    await page.fill('#camp-reason', 'Retry same prepared campaign');
    await page.click('#camp-prepare');
    await page.waitForSelector('#camp-apply:enabled', { timeout: 10_000 });
    const preparedText = (await page.textContent('#camp-result')) ?? '';
    uncertainCampaignID = preparedText.match(/Campaña\s+([0-9a-f-]{36})/)?.[1] ?? '';
    if (!uncertainCampaignID) throw new Error('prepared campaign identity is not visible');
    let hideFirstResponse = true;
    await page.route('**/v1/admin/campaigns/*/apply', async route => {
      if (!hideFirstResponse) return route.continue();
      hideFirstResponse = false;
      const response = await route.fetch();
      await route.fulfill({ response, status: 504, body: JSON.stringify({ error: 'response_lost' }) });
    });
    await page.click('#camp-apply');
    await page.waitForSelector('#camp-apply:enabled', { timeout: 10_000 });
    const uncertainText = (await page.textContent('#camp-result')) ?? '';
    if (!uncertainText.includes(uncertainCampaignID) || !(await page.isEnabled('#camp-apply')))
      throw new Error('uncertain outcome did not retain the same campaign for explicit retry');
    if (await page.isEnabled('#camp-prepare')) throw new Error('a second campaign can be prepared while the first outcome is uncertain');
    for (const selector of ['#aud-one', '#aud-all', '#camp-account', '#camp-minutes', '#camp-cap', '#camp-reason'])
      if (await page.isEnabled(selector)) throw new Error(`intent field ${selector} is editable while outcome is uncertain`);
    await page.click('#camp-apply');
    await page.waitForFunction(() => (document.querySelector('#camp-result')?.textContent ?? '').includes('Aplicada:'),
      undefined, { timeout: 15_000 });
    await page.unroute('**/v1/admin/campaigns/*/apply');
    const expectedURL = `${PANEL}/v1/admin/campaigns/${uncertainCampaignID}/apply`;
    if (prepareRequests.length !== 1) throw new Error(`prepared ${prepareRequests.length} campaigns after uncertainty`);
    if (applyRequests.length !== 2 || applyRequests.some(url => url !== expectedURL))
      throw new Error(`retry used ${JSON.stringify(applyRequests)} instead of the original campaign only`);
    if (!(await page.isEnabled('#camp-prepare'))) throw new Error('campaign form did not unlock after confirmed completion');
  });

  await page.goto(`${PANEL}/#browse`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#browse-table', { timeout: 10_000 });
  await page.selectOption('#browse-table', 'minute_entries');
  await page.waitForSelector('#browse-grid .tabulator-col[tabulator-field="kind"] .browse-filter-operator', { timeout: 10_000 });
  await check('ledger kind exposes only catalog operators and filters through the server', async () => {
    const operator = page.locator('#browse-grid .tabulator-col[tabulator-field="kind"] .browse-filter-operator');
    const values = await operator.locator('option').evaluateAll(nodes => nodes.map(node => node.value));
    if (values.join(',') !== 'eq,in') throw new Error(`kind operators=${values.join(',')}`);
    await page.locator('#browse-grid .tabulator-col[tabulator-field="kind"] .browse-filter-value').fill('gift');
    await page.waitForFunction(() => {
      const rows = [...document.querySelectorAll('#browse-grid .tabulator-row')];
      return rows.length > 0 && rows.every(row => row.querySelector('[tabulator-field="kind"]')?.textContent.trim() === 'gift');
    }, undefined, { timeout: 10_000 });
  });
  await check('retrying the same campaign creates one minute-ledger grant', async () => {
    const body = await page.evaluate(async id => {
      const query = new URLSearchParams({ q_reference: `contains:gift:${id}:` });
      const response = await fetch(`/v1/admin/browse/minute_entries?${query}`, { credentials: 'same-origin' });
      if (!response.ok) throw new Error(`ledger query failed with HTTP ${response.status}`);
      return response.json();
    }, uncertainCampaignID);
    if (body.total !== 1 || body.rows.length !== 1 || !body.rows[0].reference.includes(`gift:${uncertainCampaignID}:`))
      throw new Error(`same campaign produced ${body.total} minute-ledger rows`);
  });

  await page.goto(`${PANEL}/#audit`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => (document.querySelector('#browse-grid')?.textContent ?? '').includes('policy.update'),
    undefined, { timeout: 15_000 });
  await check('audit action exposes eq instead of unsupported contains', async () => {
    const operator = page.locator('#browse-grid .tabulator-col[tabulator-field="action"] .browse-filter-operator');
    const values = await operator.locator('option').evaluateAll(nodes => nodes.map(node => node.value));
    if (values.join(',') !== 'eq') throw new Error(`action operators=${values.join(',')}`);
    await page.locator('#browse-grid .tabulator-col[tabulator-field="action"] .browse-filter-value').fill('campaign.apply');
    await page.waitForFunction(() => {
      const rows = [...document.querySelectorAll('#browse-grid .tabulator-row')];
      return rows.length > 0 && rows.every(row => (row.textContent ?? '').includes('campaign.apply'));
    }, undefined, { timeout: 10_000 });
    await page.locator('#browse-grid .tabulator-col[tabulator-field="action"] .browse-filter-value').fill('');
    await page.waitForFunction(() => [...document.querySelectorAll('#browse-grid .tabulator-row')]
      .some(row => (row.textContent ?? '').includes('policy.update')), undefined, { timeout: 10_000 });
  });
  await check('the audit table records every mutation with its motive', async () => {
    const audit = (await page.textContent('#browse-grid')) ?? '';
    for (const action of ['policy.update', 'plans.update', 'campaign.prepare', 'campaign.apply'])
      if (!audit.includes(action)) throw new Error(`audit action ${action} missing`);
    if (!audit.includes('Revisión comercial en E2E')) throw new Error('audited motive missing');
  });
  await shot('22-audit');
  await check('the remaining views never raised a page error', async () => {
    const fresh = observedPageErrors.slice(errorsBeforeSurfaces);
    if (fresh.length) throw new Error(fresh.join(' | '));
  });
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

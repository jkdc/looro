// Focused browser E2E for the daily editorial workflow. All writes target the
// disposable Compose database; generation uses the deterministic Docker stub.
import { createRequire } from 'node:module';
import { readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const PANEL = process.env.E2E_PANEL_ORIGIN ?? 'http://127.0.0.1:8090';
const require = createRequire(import.meta.url);
const roots = [process.env.PLAYWRIGHT_CORE_PATH, join(homedir(), '.hermes/hermes-agent/node_modules/playwright-core')].filter(Boolean);
const root = roots.find(existsSync);
if (!root) throw new Error('playwright-core not found; set PLAYWRIGHT_CORE_PATH');
const { chromium } = require(root);
const cache = join(homedir(), '.cache/ms-playwright');
const builds = (await readdir(cache)).filter(name => /^chromium-\d+$/.test(name)).sort().reverse();
if (!builds.length) throw new Error('no cached Chromium');

const browser = await chromium.launch({ executablePath: join(cache, builds[0], 'chrome-linux64/chrome'), headless: true,
  args: ['--disable-gpu', '--disable-dev-shm-usage'] });
const page = await browser.newPage();
const failures = [];
page.on('pageerror', error => failures.push(`page error: ${error.message}`));
try {
  await page.goto(`${PANEL}/login.html`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', 'e2e-operator');
  await page.fill('#password', 'e2e-browser-password');
  await page.click('#login-btn');
  await page.waitForURL(`${PANEL}/`, { timeout: 10_000 });
  await page.goto(`${PANEL}/#daily-practice`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#daily-config-save', { timeout: 10_000 });

  if (!(await page.isChecked('#daily-read'))) await page.check('#daily-read');
  if (!(await page.isChecked('#daily-generation'))) await page.check('#daily-generation');
  await page.fill('#daily-content-budget', '1000000000');
  await page.fill('#daily-config-reason', 'Enable isolated browser rehearsal');
  if (await page.isEnabled('#daily-config-save')) {
    await page.click('#daily-config-save');
    await page.waitForFunction(() => (document.querySelector('#daily-config-status')?.textContent ?? '').includes('guardada y auditada'));
  }

  const packageButton = page.locator('#daily-packages [data-daily-package]').first();
  await packageButton.waitFor({ state: 'visible', timeout: 10_000 });
  const packageId = await packageButton.getAttribute('data-daily-package');
  if (!packageId) throw new Error('seeded package ID was not rendered');

  let generationCreateAttempts = 0;
  const generationRequests = [];
  await page.route('**/v1/admin/daily-practice/generation-runs', async route => {
    const request = route.request();
    if (request.method() !== 'POST') return route.continue();
    generationCreateAttempts++;
    generationRequests.push({ body: request.postData(), key: request.headers()['idempotency-key'] });
    if (generationCreateAttempts === 1) return route.abort('failed');
    return route.continue();
  });

  await page.fill('#daily-generation-reason', 'Queue isolated browser generation');
  await page.click('#daily-generate');
  await page.waitForFunction(() => (document.querySelector('#daily-generation-status')?.textContent ?? '')
    .includes('No se confirmó la solicitud'), null, { timeout: 10_000 });
  const runResponse = page.waitForResponse(response => response.url().endsWith('/v1/admin/daily-practice/generation-runs') &&
    response.request().method() === 'POST' && response.status() === 202);
  await page.click('#daily-generate');
  const run = await (await runResponse).json();
  if (generationRequests.length !== 2 || generationRequests[0].key !== generationRequests[1].key ||
      generationRequests[0].body !== generationRequests[1].body)
    throw new Error(`generation retry changed its idempotency key or request: ${JSON.stringify(generationRequests)}`);
  if (!run.runId || !['queued', 'running'].includes(run.state))
    throw new Error(`real generation route did not queue a durable run: ${JSON.stringify(run)}`);
  await page.waitForFunction(id => (document.querySelector('#daily-generation-status')?.textContent ?? '')
    .includes(`${id}: succeeded`), run.runId, { timeout: 120_000 });
  const watchedRun = await page.evaluate(async id => {
    const response = await fetch(`/v1/admin/daily-practice/generation-runs/${encodeURIComponent(id)}`, { cache: 'no-store' });
    if (!response.ok) throw new Error(`admin run read failed: HTTP ${response.status}`);
    return await response.json();
  }, run.runId);
  if (watchedRun.id !== run.runId || watchedRun.state !== 'succeeded')
    throw new Error(`worker run did not finish successfully: ${JSON.stringify(watchedRun)}`);
  if (!watchedRun?.packageVersionId || watchedRun.packageVersionId === packageId)
    throw new Error(`worker run did not identify a newly generated package: ${JSON.stringify({ seededPackageId: packageId, run: watchedRun })}`);

  await page.click('#daily-load');
  const generatedPackageButton = page.locator(`#daily-packages [data-daily-package="${watchedRun.packageVersionId}"]`);
  await generatedPackageButton.waitFor({ state: 'visible', timeout: 10_000 });

  const previewResponse = page.waitForResponse(response => response.url().endsWith(`/v1/admin/daily-practice/packages/${watchedRun.packageVersionId}`) &&
    response.request().method() === 'GET');
  await generatedPackageButton.click();
  await page.waitForFunction(() => (document.querySelector('#daily-preview')?.textContent ?? '').includes('A greener city'));
  const preview = await (await previewResponse).json();
  if (preview.id !== watchedRun.packageVersionId)
    throw new Error(`preview package differs from watched run: ${JSON.stringify({ preview: preview.id, run: watchedRun })}`);
  await page.fill('#daily-package-reason', 'Approve isolated package for test');
  const publishResponse = page.waitForResponse(response => response.url().endsWith('/v1/admin/daily-practice/publications') &&
    response.request().method() === 'POST');
  const publicationRequestPromise = page.waitForRequest(request => request.url().endsWith('/v1/admin/daily-practice/publications') &&
    request.method() === 'POST');
  await page.click('#daily-publish');
  if (!(await publishResponse).ok()) throw new Error('publication API rejected the package');
  const publicationRequest = await publicationRequestPromise;
  const publication = JSON.parse(publicationRequest.postData() ?? '{}');
  if (publication.packageVersionId !== watchedRun.packageVersionId)
    throw new Error(`publication package differs from watched run: ${JSON.stringify({ publication, run: watchedRun })}`);
  const publicResult = await page.evaluate(async () => {
    const response = await fetch('/v1/daily-practice/current?language=en', { cache: 'no-store' });
    return { status: response.status, body: response.ok ? await response.json() : null };
  });
  if (publicResult.status !== 200 || publicResult.body?.package?.packageVersionId !== watchedRun.packageVersionId)
    throw new Error(`published package is not publicly readable: ${JSON.stringify(publicResult)}`);

  await generatedPackageButton.click();
  await page.waitForFunction(() => (document.querySelector('#daily-preview')?.textContent ?? '').includes('A greener city'));
  await page.fill('#daily-package-reason', 'Withdraw isolated package after check');
  const withdrawResponse = page.waitForResponse(response => response.url().includes(`/v1/admin/daily-practice/packages/${watchedRun.packageVersionId}/withdraw`) &&
    response.request().method() === 'POST');
  await page.click('#daily-withdraw');
  if (!(await withdrawResponse).ok()) throw new Error('withdrawal API rejected the package');
  const afterWithdrawal = await page.evaluate(async () => (await fetch('/v1/daily-practice/current?language=en', { cache: 'no-store' })).status);
  if (afterWithdrawal !== 404) throw new Error(`withdrawn package remains visible: HTTP ${afterWithdrawal}`);
  if (failures.length) throw new Error(failures.join(' | '));
  console.log(`PASS daily admin workflow: retried ${run.runId}, worker generated ${watchedRun.packageVersionId}, watched, previewed, published and withdrew it`);
} catch (error) {
  console.error(`FAIL daily admin workflow: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
} finally {
  await browser.close();
}

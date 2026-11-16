# T11 browser E2E (admin operations)

Real-browser end-to-end verification of the admin operations surface: rendered
effective configuration, save/reload, save-first connection-test semantics,
audited draft moderation (approve/reject with mandatory reason), scheduler
status wording, and publication into the public catalog. No real LLM provider,
no external traffic, no production data.

The suite also checks the overview's grouped indicator hierarchy, the audit
scope stated in the panel footer, and same-origin ES-module loading under the
admin Content Security Policy.

## What runs where

| Piece | Where | Why |
|---|---|---|
| PostgreSQL 17, API build, migrations, stub LLM provider, Caddy panel, bootstrap | Docker (`compose.yaml` in this directory) | The project rule: every project service runs in Docker. Test-only secrets live in the compose file for this disposable stack. |
| Chromium + Playwright driver | Host browser, from the **already-cached** `~/.cache/ms-playwright/chromium-*` and an existing local `playwright-core` install | No Docker browser image is cached and no image/dependency downloads were authorized; the host cache already exists, so this runner needs zero downloads. |

## Reproduce

```sh
cd services/api/tests/browser
E2E_API_PORT=18081 E2E_PANEL_PORT=18090 ADMIN_PUBLIC_ORIGIN=http://127.0.0.1:18090 docker compose -p looro-daily-practice-e2e -f compose.yaml up --build -d database migrate api stub-llm daily-stub-llm caddy bootstrap seed-generate
# wait for bootstrap (operator + student attempt + failed job + daily package) and for the
# generation pipeline (two draft weeks) to finish:
docker compose -p looro-daily-practice-e2e -f compose.yaml logs -f bootstrap seed-generate   # Ctrl-C when both log their seeded output

# from the repository root:
E2E_PANEL_ORIGIN=http://127.0.0.1:18090 E2E_API_ORIGIN=http://127.0.0.1:18081 E2E_EVIDENCE_DIR="$PWD/.superpowers/sdd/2026-11-07-daily-practice/evidence/admin" node services/api/tests/browser/admin-e2e.mjs
E2E_PANEL_ORIGIN=http://127.0.0.1:18090 node services/api/tests/browser/daily-practice-e2e.mjs

docker compose -p looro-daily-practice-e2e -f services/api/tests/browser/compose.yaml down -v   # tear down
```

The host ports are configurable with `E2E_API_PORT` (default `8081`) and
`E2E_PANEL_PORT` (default `8090`) to avoid colliding with another local stack;
set `ADMIN_PUBLIC_ORIGIN` to the matching loopback panel origin when overriding it.
Environment overrides: `E2E_PANEL_ORIGIN` (default `http://127.0.0.1:8090`),
`E2E_API_ORIGIN` (default `http://127.0.0.1:8081`), `E2E_EVIDENCE_DIR`
(default `e2e-evidence/t11-browser-e2e`), `PLAYWRIGHT_CORE_PATH` (a
playwright-core install; the script also tries
`~/.hermes/hermes-agent/node_modules/playwright-core`).

Screenshots of every asserted state land in the evidence directory.

## Seeded state (bootstrap.mjs)

- Local operator `e2e-operator` / `e2e-browser-password` (loopback http origin
  allowed via `ADMIN_ALLOW_LOCAL_LOOPBACK=true`; the panel binds
  `http://127.0.0.1:8090`, the API's public port is `127.0.0.1:8081`).
- One synthetic, validated daily package for the current UTC date. It uses a
  test-only silent audio payload and matching hashes. The browser scenario
  enables read/generation flags and a bounded content budget only in this
  disposable database, aborts the first generation request, then retries the
  unchanged request through the real admin route. The API's durable worker
  calls the deterministic stub provider, passes the real validation/judge
  pipeline, and creates the package linked to the polled run. The test then
  previews and publishes that exact package, reads it publicly, and withdraws
  it.
- Two private draft weeks for the current ISO week: `en/A1` (approved by the
  E2E) and `en/A2` (rejected by the E2E). Pools are hand-written English
  content that passes the same structural validator approval re-runs.
- `LEVELTEST_GENERATE_ENABLED=false`: the panel must show the scheduler as
  configured=No and in-process=No.
- Stub provider at `http://stub-llm:9999/v1` inside the Docker network for
  legacy exam E2E and a loopback-bound daily-worker stub at
  `http://127.0.0.1:9999/v1`; both use the same deterministic fixture-aware
  responder and make no external provider calls.

## What is asserted

1. Local operator login establishes a session.
2. The effective-configuration card renders server values (env defaults before
   the first save), and the connection test is disabled without a persisted key.
3. Save persists the provider/model/base URL/key/verify/variants and the
   re-rendered card shows the stored values plus a masked key hint.
4. A dirty form disables the connection test with the explicit
   "Hay cambios sin guardar" warning; reverting re-enables it.
5. The connection test exercises the SAVED configuration and succeeds against
   the stub provider.
6. Scheduler status distinguishes configured (No) from in-process activity (No).
7. Draft listing shows both drafts; approval with a too-short reason is refused;
   approval with a real reason moves the row to `approved`; rejection moves the
   other row to `rejected` and the rejection is retained.
8. After approval, `GET /v1/level-tests/generated?language=en&level=A1` serves
   the week (25 items, content-addressed identity, thresholds); the rejected
   `en/A2` week keeps returning 404.

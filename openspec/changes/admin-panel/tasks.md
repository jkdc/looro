# Tasks: admin-panel

## Task 1 — Migration, grants and admin CLI

- [ ] 1.1 `migrations/024_admins.sql`: `admins`, `admin_sessions`,
      `admin_actions` (immutable trigger), indexes.
- [ ] 1.2 Grants documentation for `app_runtime` in `docs/admin-panel.md`.
- [ ] 1.3 `src/admins-admin.ts` + `npm run admins-admin` script:
      `add <provider> <subject> [email]`, `list`, `disable <id>` against
      `DATABASE_URL`; no secrets logged.
- [ ] 1.4 Tests: CLI functions on the test database.

## Task 2 — Admin authentication

- [ ] 2.1 `src/admin-auth.ts`: exchange (OIDC → `admins`), session mint
      (8 h, ≤3 live, SHA-256 only), cookie helpers, sign-out, profile.
- [ ] 2.2 `AuthAdmission` operations `admin` / `admin-api`; env config
      (`ADMIN_HMAC_KEY`, `ADMIN_PROXY_TOKEN`, `GOOGLE_ADMIN_CLIENT_ID`,
      `ADMIN_PUBLIC_ORIGIN`) validated at startup.
- [ ] 2.3 Routes in `app.ts`: challenge/exchange/session/sign-out/me +
      origin validation on mutating methods; error codes.
- [ ] 2.4 Tests: auth flow with mocked verifier, cookie flags, `not_admin`,
      disabled admin, session cap/expiry/revocation, isolation both ways,
      admission 429.

## Task 3 — Browse engine, metrics and timeseries

- [ ] 3.1 `src/admin-browse.ts`: table configs, whitelist query builder,
      typed operators, LIMIT/OFFSET, schema metadata, redactions.
- [ ] 3.2 `GET /v1/admin/browse/:table` (+ `schema=1`).
- [ ] 3.3 `src/admin-metrics.ts`: snapshot + four timeseries sets
      (`generate_series` filled, days clamped).
- [ ] 3.4 Routes `GET /v1/admin/metrics`, `GET /v1/admin/metrics/timeseries`.
- [ ] 3.5 Tests: filters/order/pagination per type, injection battery,
      LIMIT cap, unknown identifiers, redaction, timeseries filling/clamp.

## Task 4 — Audited actions

- [ ] 4.1 `HostedVoice.requestAdminClose(sessionID, actor, reason)`.
- [ ] 4.2 Routes: welcome policy get/put, campaigns prepare/apply,
      access-request delete, hosted session force close — all writing
      `admin_actions` in the same transaction and validating `Origin`.
- [ ] 4.3 Tests: policy audit + 409 version conflict, single-user campaign
      prepare/apply, delete waitlist entry, force close settles, audit rows
      present, Origin rejection.

## Task 5 — Static panel UI + edge wiring

- [ ] 5.1 Vendor pinned Tabler/Tabulator/Chart.js files under
      `admin-ui/vendor/`.
- [ ] 5.2 SPA: login (challenge+exchange), overview/growth/voice/economy
      dashboards (KPI cards + Chart.js), browse (Tabulator remote mode,
      schema-driven columns/filters), policy form, campaigns form,
      audit table.
- [ ] 5.3 `Caddyfile` admin site block (proxy + file_server + CSP);
      `compose.yaml` mounts and env passthrough; Dockerfile unchanged.
- [ ] 5.4 Manual smoke: dashboards render, browse filters/paginates,
      actions execute against a local stack.

## Task 6 — Docs and verification

- [ ] 6.1 `docs/admin-panel.md`: setup, DNS, env, CLI bootstrap, security
      notes; README pointer.
- [ ] 6.2 `npm run check` + full `npm test` green; verdict recorded.

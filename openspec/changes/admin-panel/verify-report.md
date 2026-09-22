# Verify report: admin-panel

## Verified by automation

- `npm run check` clean (tsc --noEmit) at every task boundary and at close.
- Full backend suite **385/385 green** (358 baseline + 27 new tests):
  `tests/admins-admin.test.ts` (5), `tests/admin-auth.test.ts` (10),
  `tests/admin-browse.test.ts` (7), `tests/admin-actions.test.ts` (5).
- Coverage: admin CLI add/list/disable with session revocation; OIDC exchange
  against the `admins` table (mocked `identityVerifier`), single-use
  challenges, cookie flags (HttpOnly/Secure/SameSite=Strict/Max-Age 28800),
  `not_admin`/`admin_disabled` refusals, 3-session cap, sign-out revocation,
  bearer→cookie session exchange, strict admin admission (429 + Retry-After);
  token isolation both directions; browse filters/order/pagination per type,
  LIMIT/OFFSET caps, unknown identifiers, type validators, SQL-injection
  battery (values stay literal, tables survive), `token_hash` redaction;
  metrics snapshot and 4 timeseries sets with zero-filled days and 90-day
  clamp; welcome-policy double audit + 409 stale version + mandatory reason;
  single-user campaign prepare/apply with confirmation digest; waitlist
  delete (+404 repeat); operator force close (marks `operator_close`); CSRF
  Origin rejection with zero audit rows.

## Verified manually (live stack, docker compose)

Full stack built and run from the repo: `database` + `migrate` + `api` +
`caddy` (admin site on `https://admin.localhost`, internal cert). Seeded
synthetic accounts, minutes, an active hosted session, waitlist entries and
a purchase order. Exercised over HTTPS through Caddy with the
trusted-proxy headers:

- Health, admin gating (401 without/with bogus cookie, 503 when panel
  disabled), challenge issuance including the public web client ID.
- Identity isolation live: admin cookie refused as app bearer on
  `/v1/minutes`, admin bearer refused on `/v1/admin/me` (cookie-only).
- Browse API: pagination (limit/offset/total), `contains` filter, injection
  payload (`'; DROP TABLE accounts;--`) returns 0 rows and the table
  survives.
- Policy PUT → `minute_policy_audit` + `admin_actions` rows with the admin
  actor; stale version → 409; foreign `Origin` → 403 with zero writes.
- Campaign prepare→apply for one user → wallet +5 min (600k→900k ms);
  forged confirmation → 409.
- Waitlist delete → 200, repeat → 404.
- Operator force close on an active session → `closing`/`operator_close`,
  then the real worker reconciliation settled it (observed the recovery
  worker close an abandoned seeded session on its own, too).
- Fail-closed paths: hosted routes 503 without the voice service.

Browser (Chromium via ZCode in-app browser, viewport 1440×900):

- Login page renders; the Google button is generated with the challenge
  `nonce` and `state=challengeID` correctly; bearer→cookie sign-in works and
  lands on the panel.
- Overview dashboard: 12 KPI cards with live numbers and 5 Chart.js
  timeseries with the seeded spike; Growth/Voice/Economy dashboards render
  their series.
- Table browser: accounts render with header filters, date/boolean
  formatting, "5 filas" total, working email filter (`caro` → 1 row),
  sort by email desc, table switch to `hosted_sessions`, custom pager.
- Per-user actions from row context menu: account detail modal (support
  triage JSON) and "Regalar minutos…" with the mandatory-reason modal →
  server prepares + applies (admin actions 6→8) with a success toast.
- Audit view lists the audited actions; policy and campaign forms render
  with the current values.

## Fixed during manual testing

- Caddy: `try_files` ran before `reverse_proxy` (static fallback swallowed
  `/v1/admin/*`) → exclusive `handle` blocks; `ADMIN_PROXY_TOKEN` missing
  from the caddy container env → passthrough added (fails closed when unset).
- UI: Tabulator's ajax/remote-pagination integration returned no rows in
  6.5.3 → replaced with an explicit `ajaxRequestFunc`-free custom pager
  (own limit/offset/sort/filter state, `dataSorting` + `dataFiltered` with a
  loop guard); modals depended on `window.bootstrap`, which Tabler does not
  expose → manual modal toggling; sidebar overlapped content → in-flow
  sticky layout.

## Not verified by automation / honest boundary

- **Real Google sign-in was not exercised end-to-end**: the local stack uses
  a fake admin client ID. The authorization-code exchange path to Google's
  token endpoint is implemented and unit-shaped but needs a real web OAuth
  client + DNS record (`admin.$API_DOMAIN`) to verify live. The challenge→
  auth-URL construction was verified in the browser.
- **Full-suite flake (pre-existing)**: `accounts.test.ts` "new and existing
  subjects can sign in after ten thousand active accounts" failed in 2 of 4
  full-suite runs. It depends on leftover `auth_rate_limits` 'exchange'
  bucket state within the hour; the admin change does not touch that bucket
  (separate operations). Baseline behavior predates this change.
- Admin-session expiry (8 h) and the live prune interval are covered by
  queries and code path inspection, not by a fast test (would need fake
  clocks).
- Serving real UI assets over the production HTTPS admin domain with a
  trusted certificate, and `docker compose` on the actual server, remain a
  deployment-time check (local compose used Caddy's internal cert).

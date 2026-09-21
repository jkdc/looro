# Proposal: admin-panel

## Intent

Give the operator a first-class administration panel served by the backend:
federated admin sign-in against a dedicated `admins` table (separate from the
app-user `accounts`/`identities` tables), a generic whitelisted table browser
(filters, ordering, pagination), metric dashboards with charts, and audited
operator actions. The panel is for a single technical operator whose goal is
observing how the mobile apps behave and acting on individual users.

## Problem

Every operator capability today is a CLI run inside the server environment
(`minutes-admin`, `access-requests-admin`, `account-closeout-admin`). That is
safe but unusable for day-to-day observation: no browsing of accounts, orders,
sessions or waitlist with filters and pagination, no dashboards, and no way to
act on a specific user without shell access. There is no admin identity
anywhere — nothing to authorize against — and the codebase deliberately has
zero administrative HTTP routes.

## Approach

- **Admin identity, structurally isolated** (migration `024_admins.sql`):
  `admins(id, email, provider, subject, created_at, disabled_at,
  UNIQUE(provider, subject))` plus `admin_sessions` (hashed opaque token,
  8 h TTL, ≤3 concurrent) and `admin_actions` (generic audit of every
  panel-originated mutation: admin, action, target, reason, detail).
  Sign-in reuses the exact OIDC verification of app users
  (`verifyIdentity`, nonce challenges) but resolves the identity against
  `admins`; a user token can never authenticate as an admin and vice versa
  because no table or query is shared. Admin management stays CLI-only
  (`admins-admin add|list|disable`) — the panel can never escalate.
- **Cookie session for the browser panel**: HttpOnly + Secure + SameSite=Strict,
  `Origin` validated on mutating routes, no CORS on admin routes, all admin
  routes behind the durable admission limiter and the trusted-proxy header
  requirement.
- **Generic browse engine** (`src/admin-browse.ts`): one endpoint
  `GET /v1/admin/browse/:table` driven by per-table whitelists (visible,
  filterable-with-operators, orderable columns). Values always bind as `$n`
  parameters; identifiers come only from whitelists, never from client
  strings. LIMIT ≤ 100 + OFFSET pagination with total count. Dedicated
  SQL-injection test suite.
- **Dashboards data** (`/v1/admin/metrics`, `/v1/admin/metrics/timeseries`):
  fixed read-only queries — account/wallet/minute/order/session/waitlist
  snapshots and daily series (signups, sessions, minutes granted vs
  consumed, orders and revenue, helper requests, waitlist) to feed
  Chart.js dashboards.
- **Audited actions**: welcome policy update, minute campaigns (single-user
  included) and waitlist deletion reuse the existing audited functions
  byte-for-byte; one new audited capability — force-closing an active hosted
  session — plus a `POST /v1/admin/auth/session` token→cookie exchange for
  non-browser clients. Every mutation also writes `admin_actions`.
- **Static panel UI** (`services/api/admin-ui/`, served by Caddy
  `file_server` on `admin.$API_DOMAIN`): Tabler theme, Tabulator tables
  (remote pagination/filter/sort against the browse endpoint), Chart.js
  dashboards — all vendored as pinned static files, no build step, no CDN.

## Scope

- **In scope:** migration + grants + CLI, admin auth + cookie sessions +
  admission, browse engine + injection tests, metrics/timeseries, audited
  actions, static UI + Caddy/compose wiring, docs, tests.
- **Out of scope (parked):** managing admins from the panel itself (CLI
  forever), roles/permissions beyond the single admin, keyset pagination,
  cross-table joins in browse, exporting PII over HTTP (stays CLI with
  0600 files), any mobile admin app.

## Risks

- Dynamic WHERE/ORDER BY is the classic SQL-injection surface; mitigated by
  whitelist-only identifiers, parameterized values, and dedicated injection
  tests.
- Browser session model (cookie) is new to this codebase; mitigated with
  SameSite=Strict, Origin validation, no CORS, separate cookie name/domain,
  and the existing admission limiter.
- Vendored third-party JS in the admin origin; mitigated by pinned
  committed files, CSP limited to `self`, and keeping every security
  decision server-side.
- Deployment needs a DNS record for `admin.$API_DOMAIN`; without it the
  panel can run on the same domain under `/admin/` (same-origin still
  holds).

## Delivery strategy

`single-pr` — direct conventional commits on the current local branch, one
commit per task: record → migration/CLI → auth → browse+metrics → audited
actions → UI+Caddy → docs/verify. Backend-only; no Android/iOS changes, so
client parity gates do not apply.

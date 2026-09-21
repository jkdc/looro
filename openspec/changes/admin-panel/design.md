# Design: admin-panel

## Data model (migration 024_admins.sql)

```sql
CREATE TABLE admins (
  id uuid PRIMARY KEY,
  email text,
  provider text NOT NULL CHECK (provider IN ('google','apple')),
  subject text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  disabled_at timestamptz,
  UNIQUE (provider, subject)
);
CREATE TABLE admin_sessions (
  id uuid PRIMARY KEY,
  admin_id uuid NOT NULL REFERENCES admins(id),
  token_hash text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE TABLE admin_actions (
  id uuid PRIMARY KEY,
  admin_id uuid NOT NULL REFERENCES admins(id),
  action text NOT NULL,
  target text NOT NULL,
  reason text NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
```

`admin_actions` uses the `immutable_ledger()` trigger like other audit
tables. Grants: `app_runtime` gets SELECT/INSERT/UPDATE/DELETE on
`admins` and `admin_sessions`, SELECT/INSERT on `admin_actions`
(documented in `docs/admin-panel.md`, following `enable-accounts.md`).

## Authentication

- `POST /v1/admin/auth/challenge` — reuses `createChallenge` verbatim
  (nonces are provider-agnostic proof-of-possession).
- `POST /v1/admin/auth/exchange` — verifies the Google ID token with
  `verifyIdentity` restricted to the `GOOGLE_ADMIN_CLIENT_ID` audience
  (single-audience list; Apple accepted only if configured), then resolves
  `(provider, subject)` against `admins` with `disabled_at IS NULL`.
  Unknown identity → generic `not_admin` 403 (no existence oracle).
  Issues an opaque 32-byte token, stores only its SHA-256, TTL 8 h,
  prunes beyond 3 live sessions (newest kept), sets the cookie.
- `POST /v1/admin/auth/session` — same exchange result path but from an
  already-minted bearer in the body; exists so non-browser clients can
  obtain the cookie. Rate limited like `exchange`.
- `POST /v1/admin/auth/sign-out` — revokes the presented session.
- `GET /v1/admin/me` — id, email, providers, created_at.

Cookie: name `admin_session`; HttpOnly; Secure; SameSite=Strict; Path=/;
Max-Age 28800. `trustProxy` stays false — the cookie is set by the API and
TLS is terminated at Caddy, so `Secure` works in production (HTTPS) and on
loopback during manual testing.

CSRF: SameSite=Strict plus `Origin` header validation on every mutating
admin route — the origin host must equal the configured admin origin
(`ADMIN_PUBLIC_ORIGIN`, required when admin auth is enabled). No CORS
headers are ever emitted on `/v1/admin/*`.

Admission: `AuthAdmission` gains operations `admin` (challenge/exchange/
session — strict per-network + global caps) and `admin-api` (panel API
calls). All `/v1/admin/*` paths are also inside the shared in-memory
limiter and require trusted-proxy headers exactly like account routes.

## Browse engine (`src/admin-browse.ts`)

Per-table config declared in code:

```ts
interface BrowseTable {
  sql: string;                                  // e.g. 'accounts' or a fixed SELECT with joins banned
  columns: Record<string, { type: 'uuid'|'text'|'integer'|'bigint'|'boolean'|'timestamptz'|'jsonb'; }>;
  filterable: Record<string, ('eq'|'neq'|'contains'|'gte'|'lte'|'in'|'is-null')[]>;
  orderable: string[];
  defaultOrder: { column: string; direction: 'asc'|'desc' };
  redactions?: Record<string, 'omit'>;          // columns never returned (e.g. token_hash)
}
```

- Query construction: column/table/direction identifiers are substituted
  only from the config maps (never client strings); values bind as `$n`.
  `contains` → `ILIKE '%' || $n || '%'` with `%`/`\` escaped in the value.
  `in` → `= ANY($n::text[])` typed by column. `is-null` takes no value.
- Params: `q_<column>=<op>:<value>` (repeatable), `sort`, `dir`, `limit`
  (1–100), `offset` (0–1,000,000), `schema=1` to include the column
  metadata the UI renders.
- Response: `{ table, total?, columns, filters, orderable, rows }`.
- Tables v1: accounts, wallets, minute_wallets, minute_entries,
  hosted_sessions, hosted_helper_requests, minute_purchase_orders,
  access_requests, auth_sessions (metadata only, `token_hash` redacted),
  usage_records, checkout_orders, minute_campaigns, minute_policy_audit,
  ai_feedback, admin_actions. Adding a table is adding a config entry.

## Dashboards

- `GET /v1/admin/metrics` — snapshot: total/guest/deleted accounts, live
  user sessions, minute wallets (sum balance/reserved), 30-day active
  (distinct accounts with entries), orders by state, revenue sums by
  environment, hosted sessions by state, helpers requests, waitlist size,
  AI feedback count.
- `GET /v1/admin/metrics/timeseries?days=N&set=<overview|growth|voice|economy>`
  — `generate_series`-filled daily buckets: signups, guest starts, waitlist
  additions; hosted sessions created/closed, helper requests; orders, revenue
  nano, minutes granted vs consumed (minute_entries by kind), refunds.
  Fixed SQL only; `days` clamped 1–90.

## Audited actions

- `GET/PUT /v1/admin/policy/welcome` → `welcomePolicy` / `updateWelcomePolicy`
  (actor = `admin:<email>`, reason required; 409 on version conflict).
- `GET/POST /v1/admin/campaigns` → `prepareMinuteCampaign`; the apply step
  `POST /v1/admin/campaigns/:id/apply` loops `applyMinuteCampaign` with the
  returned confirmation until `pending=0`.
- `DELETE /v1/admin/access-requests` → `deleteAccessRequest`.
- `POST /v1/admin/hosted-sessions/:id/close` → new
  `HostedVoice.requestAdminClose(sessionID, actor, reason)`: locks the row,
  refuses unknown sessions (404), sets `close_requested_at=now()` and
  `close_reason='admin_close'` on non-closed sessions — the existing
  reconciliation settles funds exactly like a user-requested close.
- Every mutating call above also inserts an `admin_actions` row inside the
  same transaction.

## Static UI (`services/api/admin-ui/`)

Vendored pinned files under `admin-ui/vendor/` (Tabler CSS/JS, Tabulator
JS/CSS, Chart.js UMD), committed to the repo. Single-page app with hash
routing: `#overview`, `#growth`, `#voice`, `#economy`, `#browse`, `#policy`,
`#audit`. Login view performs challenge+exchange with Google Identity
Services (loaded from the vendor bundle? No — GIS itself is external; the
login page is the one place that may load accounts.google.com, excluded
from the strict CSP via a dedicated login document). Dashboards render
KPI cards (Tabler) and Chart.js line/bar charts from the timeseries sets;
browse renders Tabulator with remote pagination wired to the browse
endpoint and columns from `schema=1`; actions forms post with a reason
field.

Caddy: second site block `{$ADMIN_DOMAIN}` — `reverse_proxy /v1/admin/*
api:8080`, `file_server` rooted at the mounted `admin-ui`, HSTS + nosniff
+ Referrer-Policy + `Content-Security-Policy` (script/style/connect/img
limited to `'self'`; the login document relaxes script-src for Google
Identity Services). Compose: mount `./admin-ui` read-only into `caddy`,
add `ADMIN_DOMAIN`, `GOOGLE_ADMIN_CLIENT_ID`, `ADMIN_HMAC_KEY`,
`ADMIN_PROXY_TOKEN`, `ADMIN_PUBLIC_ORIGIN` passthrough.

## Error codes

`admin_sign_in_required` 401 · `not_admin` 403 · `admin_disabled` 403 ·
`admin_origin_required` 403 (CSRF) · `table_not_browsable` 404 ·
`admin_configured_unavailable` 503 (feature flag off).

## Testing

- `admins-admin.test.ts` — CLI functions against the test database
  (add/list/disable, duplicate subject rejected).
- `admin-auth.test.ts` — mocked `identityVerifier` (existing pattern):
  happy path cookie flags, unknown identity → `not_admin`, disabled admin,
  single-use challenge, revocation, 3-session cap, 8 h expiry, session
  exchange endpoint.
- `admin-isolation.test.ts` — app bearer rejected on admin routes, admin
  cookie rejected on app routes, cross-table probes.
- `admin-browse.test.ts` — filters/order/pagination per type, LIMIT cap,
  unknown column/table/operator → 400/404, injection battery
  (`'; DROP TABLE`, subqueries, `--`, column-name payloads end up as
  literal values or 400), redaction of `token_hash`.
- `admin-metrics.test.ts` — snapshot and timeseries correctness on
  synthetic data, day filling, days clamp.
- `admin-actions.test.ts` — policy update audit rows + 409 version, single
  user campaign prepare/apply, access request delete, admin force close
  (settles via reconciliation), Origin validation, every mutation writes
  `admin_actions`.
- `admin-admission.test.ts` — 429 + Retry-After on strict admin caps.

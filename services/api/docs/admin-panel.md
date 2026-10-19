# Admin panel

The operator panel is a static single-page app served by Caddy on
`admin.$API_DOMAIN`, talking to `/v1/admin/*` on the API. Administrator
identities live in the dedicated `admins` table — never in the app-user
`accounts`/`identities` tables — and the panel can never manage
administrators; that stays a local CLI (`npm run admins-admin`).

Sign-in is **local only**: there is no federated identity provider. Operator
accounts are created by the CLI with a username and a scrypt password hash,
so the panel's attack surface never depends on an external IdP.

## Capabilities

- **Dashboards** (`#overview`, `#growth`, `#voice`, `#economy`): KPI cards and
  30-day timeseries (accounts, waitlist, hosted sessions, helper cost,
  minutes granted/consumed, orders and volume) from fixed read-only queries.
- **Table browser** (`#tablas`): any table declared in `src/admin-browse.ts`
  with server-side validated filters, ordering and pagination. Filters are
  always applied by the API; the client only paints.
- **Per-user actions**: account detail (support triage report), single-user
  minute campaigns, waitlist deletion, forced close of an active hosted
  session. Every mutation requires a reason and writes both the domain audit
  (e.g. `minute_policy_audit`) and `admin_actions`.
- **Weekly exam operations** (`#level-tests`): effective saved LLM settings,
  save-first connection checks, paginated private draft moderation (approval and
  rejection are audited), and safe latest-job/scheduler status. Rejected rows
  remain retained and block ordinary same-week regeneration; replacement requires
  the existing explicit force operation. Connection checks never display or read
  provider response bodies and are time-bounded.
- **Welcome policy** (`#política`): the same audited policy change the CLI
  performs, with version conflict detection.

## Enable on a deployment

1. Point DNS: `admin.$API_DOMAIN` → the server (A/AAAA/CNAME). Set
   `ADMIN_DOMAIN` in the deployment environment; compose passes it to Caddy.
   Without it the site binds `admin.localhost` with an internal certificate
   and the panel is effectively unreachable.
2. Add the environment entries (see `.env.example`):
   `ADMIN_HMAC_KEY`, `ADMIN_PROXY_TOKEN`, `ADMIN_PUBLIC_ORIGIN=https://admin.$API_DOMAIN`.
   The two secrets are separate random 32-byte lowercase hex values, stored
   in the mode-600 environment file next to the other secrets. Startup fails
   fast on missing, malformed, or identical values.
3. Apply the schema (`npm run migrate` runs `024_admins.sql` and
   `026_admin_local_auth.sql`) and grant the runtime role:

   ```sql
   GRANT SELECT, INSERT, UPDATE, DELETE ON admins, admin_sessions TO app_runtime;
   GRANT SELECT, INSERT ON admin_actions TO app_runtime;
   ```

4. Register yourself as an administrator and set your password (12–1024
   characters, read from stdin so it never lands in shell history):

   ```sh
   npm run admins-admin -- add local <username> you@example.test
   npm run admins-admin -- passwd <admin-id> <<< 'your-long-passphrase'
   npm run admins-admin -- list
   npm run admins-admin -- disable <admin-id>   # also revokes live sessions
   ```

## Authentication model

- Sign-in is local: the panel posts `username` + `password` +
  `challengeID` to `/v1/admin/auth/login`. Passwords are verified against a
  scrypt hash (N=16384, r=8, p=1, per-user salt) stored on the `local` admin
  row; unknown users burn an equivalent verification so response timing does
  not reveal account existence, and every refusal is the same generic error.
- The challenge stays single-use and is burned on success; sign-in
  operations are admission-limited (30/hour per network).
- Sessions are opaque 43-character tokens; only their SHA-256 is stored,
  they live 8 hours, at most 3 live sessions per admin, and sign-out (or a
  CLI disable or `passwd`) revokes immediately. The `admin_session` cookie is
  HttpOnly, Secure, SameSite=Strict and scoped to the admin domain.
- Mutating admin routes reject requests whose `Origin` differs from
  `ADMIN_PUBLIC_ORIGIN`. There is no CORS on `/v1/admin/*`.
- All admin routes require the trusted-proxy headers and durable admission
  limits (30/hour per network for sign-in operations, 600/hour for API use).
- An app-user token can never authenticate as an admin and vice versa: the
  session tables and identity lookups share nothing.

## Security notes

- `admin_actions` is append-only (immutable ledger trigger). Support
  exports with PII stay CLI-only with 0600 files; the panel never exports.
- The browser never filters data by itself: browse requests carry filter
  parameters that the API validates against per-table whitelists, values
  bind as parameters, and identifiers come from configuration only.
- Vendored UI assets (Tabler, Tabulator, Chart.js) are pinned files under
  `admin-ui/vendor/`; refresh them deliberately and re-review. CSP restricts
  the panel to same-origin assets; no external origin is contacted, including
  on the login page.

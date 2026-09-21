# Admin panel

The operator panel is a static single-page app served by Caddy on
`admin.$API_DOMAIN`, talking to `/v1/admin/*` on the API. Administrator
identities live in the dedicated `admins` table — never in the app-user
`accounts`/`identities` tables — and the panel can never manage
administrators; that stays a local CLI (`npm run admins-admin`).

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
- **Welcome policy** (`#política`): the same audited policy change the CLI
  performs, with version conflict detection.

## Enable on a deployment

1. Point DNS: `admin.$API_DOMAIN` → the server (A/AAAA/CNAME). Set
   `ADMIN_DOMAIN` in the deployment environment; compose passes it to Caddy.
   Without it the site binds `admin.localhost` with an internal certificate
   and the panel is effectively unreachable.
2. Create a **Google OAuth web client** (type *Web application*) with the
   authorized redirect URI `https://admin.$API_DOMAIN/oauth2/callback.html`.
   This client is for the panel only; do not reuse the Android clients.
3. Add the environment entries (see `.env.example`):
   `GOOGLE_ADMIN_CLIENT_ID`, `GOOGLE_ADMIN_CLIENT_SECRET`,
   `ADMIN_HMAC_KEY`, `ADMIN_PROXY_TOKEN`, `ADMIN_PUBLIC_ORIGIN=https://admin.$API_DOMAIN`.
   The two secrets are separate random 32-byte lowercase hex values, stored
   in the mode-600 environment file next to the other secrets. Startup fails
   fast on missing, malformed, or identical values.
4. Apply the schema (`npm run migrate` runs migration `024_admins.sql`)
   and grant the runtime role:

   ```sql
   GRANT SELECT, INSERT, UPDATE, DELETE ON admins, admin_sessions TO app_runtime;
   GRANT SELECT, INSERT ON admin_actions TO app_runtime;
   ```

5. Register yourself as an administrator. Discover your Google subject once
   (decode any Google ID token issued to you, or use
   `https://openidconnect.googleapis.com/v1/userinfo`), then:

   ```sh
   npm run admins-admin -- add google <your-subject> you@example.test
   npm run admins-admin -- list
   npm run admins-admin -- disable <admin-id>   # also revokes live sessions
   ```

## Authentication model

- Sign-in is federated. The web flow is the Google **authorization-code**
  flow bound to a single-use challenge: the panel fetches a challenge
  (nonce), sends you to Google with `nonce` and `state=challengeID`, and the
  backend exchanges the code (server secret) and verifies the returned
  `id_token` against the challenge's nonce hash. Native id-tokens with a
  nonce are accepted on the same endpoint.
- Sessions are opaque 43-character tokens; only their SHA-256 is stored,
  they live 8 hours, at most 3 live sessions per admin, and sign-out (or a
  CLI disable) revokes immediately. The `admin_session` cookie is
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
  the panel to same-origin assets; only the login document may contact
  `accounts.google.com`.

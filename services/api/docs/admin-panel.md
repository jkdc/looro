# Admin panel

The operator panel is a static single-page app served by Caddy on
`admin.$API_DOMAIN`, talking to `/v1/admin/*` on the API. Administrator
identities live in the dedicated `admins` table — never in the app-user
`accounts`/`identities` tables — and the panel can never manage
administrators; that stays a local CLI (`npm run admins-admin`).

Sign-in is **local only**: there is no federated identity provider. Operator
accounts are created by the CLI with a username and a scrypt password hash,
so the panel's attack surface never depends on an external IdP.

## Sidebar layout

Every entry belongs to a titled section, and each heading is a separator (not a
link), so the scope of a group is never ambiguous:

| Group | Entries |
|---|---|
| Operación | `#overview`, `#growth`, `#voice`, `#economy`, `#usage` |
| Exámenes | `#exam-weeks`, `#exam-results`, `#ia-config` |
| Comercial | `#plans`, `#campaigns` |
| Sistema | `#browse`, `#policy`, `#audit` |

New entries must declare a `group` in the `NAV` table; the heading is derived
from the data, so a group cannot silently gain a stray entry.

Status badges use Tabler's utilities: `.badge` draws the pill and
`bg-<tone>-lt` paints it (saturated text on a 10 % tint). `badge-<tone>` is
**not** defined in the vendored CSS — it silently renders the neutral grey pill,
so every state looks the same.

## Capabilities

- **Dashboards** (`#overview`, `#growth`, `#voice`, `#economy`): KPI cards and
  30-day timeseries (accounts, waitlist, hosted sessions, helper cost,
  minutes granted/consumed, orders and volume) from fixed read-only queries.
  The overview groups existing indicators into users/activity, service/consumption,
  and commercial/support; labels and captions distinguish current totals from
  bounded periods. Chart rendering is isolated in the tested
  `admin-ui/features/dashboard-charts.js` module; the app entry remains same-origin
  and uses the existing CSP and route hashes.
- **Table browser** (`#browse`): any table declared in `src/admin-browse.ts`
  with server-side validated filters, ordering and pagination. Filters are
  always applied by the API; the client only paints.
- **Per-user actions**: account detail (support triage report), single-user
  minute campaigns, waitlist deletion, forced close of an active hosted
  session. Per-user data mutations require an operator reason and write the
  relevant domain audit (e.g. `minute_policy_audit`) and `admin_actions`.
- **Exam operations** (sidebar group `Exámenes`):
  - `#exam-weeks` — generation launcher, live job monitor, paginated weeks
    listing and audited draft moderation (approval and rejection). Rejected rows
    remain retained and block ordinary same-week regeneration; replacement
    requires the existing explicit force operation. Safe latest-job/scheduler
    status lives here. Launching never overwrites a week: approved keys report
    `skipped_approved` and any other existing row (draft, rejected, retired)
    reports `skipped_existing`, so no job is created and the panel cannot claim
    a green completion for content that did not change. Redoing a draft or a
    rejected week goes through «Regenerar», which is force and refuses approved
    keys.
    The jobs table is a run log: every attempt is its own row, so a retry keeps
    the previous failure and its reasons instead of overwriting them (migration
    `049`). The automatic cap of two attempts binds the scheduler and the CLI; a
    launch from the panel is an explicit operator request and may retry beyond
    it, with the attempt ordinal still growing. The weeks table carries `Origen`
    (`Operador` / `Automático` / `—`): panel launches and regenerations are
    `user_operator`, scheduler and CLI runs are `automatic_worker`, and rows that
    predate migration `048` stay `unknown`. Approval provenance is separate:
    `approved_by` holds `system:auto` when auto-publish approved a week or the
    operator id when a human did.
    An approved week can be retired («Retirar» in the listing or the review
    page), which is how an operator invalidates an approval made by the
    pipeline. Retiring stops serving the week immediately, keeps the content and
    the judge verdicts intact, and stores the operator motive on the row
    (`retired_at`, `retired_by`, `retired_reason`; migration `050`) plus the
    `level_test.retire` audit entry. A retired week is not reissued
    automatically: the scheduler and the launcher skip it, and replacing it is
    an explicit «Regenerar».
    Weeks and jobs are colour-coded by severity (green = usable, red = needs an
    operator, yellow = waiting or unknown). While a run started from the panel
    (or already `running` when the view mounts) is live, the monitor polls
    `/v1/admin/level-tests/status` every 5 seconds using exact run-correlation
    UUIDs. A request token is not shown as a persisted run ID until the exact
    status lookup finds its database row; accepted-but-not-yet-registered runs
    remain pending/unknown rather than matching a prior attempt for the same
    week. Actual run UUID and attempt ordinal identify terminal state. Active
    correlation tokens survive a page reload in tab-scoped session storage and
    are cleared after their run reaches a terminal state. A failed poll is
    retried on the next tick. Leaving the view parks the watch — no timer, no
    requests — and coming back resumes it. A job's error code is a button that
    opens a dialog with the coarse code, the sanitized per-section reasons from
    the last attempt, timestamps, attempts and the next step. Only messages the
    pipeline writes itself are stored (`error_details`, migration `047`);
    provider bodies, stack traces and user content are dropped by the writer.
  - `#exam/<uuid>` — full-page exam review: sections in exam order, accepted
    answers marked, judge verdicts with reasons, passages once per text, variant
    and pool tabs, and approve/reject with a mandatory reason. No raw JSON.
  - `#ia-config` — provider, model, base URL, API key, publish/verify modes and
    variants, plus save-first connection checks. Connection checks never display
    or read provider response bodies and are time-bounded.
  - `#exam-results` — per-student attempts, filters and detail.
  - `#level-tests` redirects to `#exam-weeks` for old bookmarks.
- **Welcome policy** (`#policy`): the same audited policy change the CLI
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

# Delta Spec: admin-panel

## ADDED Requirements

### Requirement: Admin identity is separate from app users

The backend SHALL maintain administrator identities in a dedicated `admins`
table keyed by `(provider, subject)` from federated OIDC verification, with
no join, query or session storage shared with the app-user
`accounts`/`identities`/`auth_sessions` tables. An app-user access token
SHALL NOT authenticate on any `/v1/admin/*` route, and an admin session
SHALL NOT authenticate on any app-user route. Identity matching SHALL never
use email. Creating, disabling and listing administrators SHALL be possible
only through the operator CLI, never through the panel.

#### Scenario: app token cannot reach the admin panel

- **WHEN** a request carries a valid app-user bearer token on
  `/v1/admin/me`
- **THEN** the response is 401 `admin_sign_in_required`.

#### Scenario: admin session cannot reach app routes

- **WHEN** the admin cookie is presented on `/v1/account`
- **THEN** the response is 401 `sign_in_required`.

### Requirement: Admin browser sessions are hardened

Admin sessions SHALL be opaque tokens stored only as SHA-256 hashes with an
8-hour lifetime, at most 3 concurrent live sessions per admin, and full
revocation on sign-out. The cookie SHALL be HttpOnly, Secure and
SameSite=Strict. Mutating admin routes SHALL validate the `Origin` header
against the configured admin origin. All admin routes SHALL require
trusted-proxy identity headers and SHALL be rate limited with durable
per-network and global admission caps.

#### Scenario: cross-site mutation is refused

- **WHEN** a POST from a browser on another origin reaches a mutating admin
  route with a valid admin cookie
- **THEN** the response is 403 `admin_origin_required`.

#### Scenario: flood is rate limited

- **WHEN** one network exceeds the strict admin exchange cap
- **THEN** further attempts receive 429 with `Retry-After`.

### Requirement: Generic whitelisted table browser

The panel SHALL expose `GET /v1/admin/browse/:table` for a fixed set of
operator-declared tables. Filtering, ordering and pagination SHALL be
validated against per-table whitelists: column identifiers, operators and
order direction SHALL come only from configuration, values SHALL bind as
query parameters, and the page size SHALL be capped at 100. Secrets and
credential material (for example `auth_sessions.token_hash`) SHALL never be
returned. Unknown tables SHALL return 404; invalid columns, operators or
values SHALL return 400.

#### Scenario: filters, order and pagination on accounts

- **WHEN** the operator browses `accounts` filtering `email` contains
  `@example.com`, ordering by `created_at` descending, page 2 of size 50
- **THEN** the response returns matching rows with a total count, and the
  executed SQL contains bound parameters, not interpolated values.

#### Scenario: injection attempts are inert

- **WHEN** a filter value contains SQL control syntax, or `sort` names a
  non-whitelisted expression
- **THEN** the value is treated as a literal (or rejected with 400) and no
  identifier outside the whitelists reaches the query.

### Requirement: Dashboards expose operational metrics

The panel SHALL provide a metrics snapshot and daily timeseries sets
(overview, growth, voice, economy) built from fixed read-only queries with
`generate_series` day filling and a clamped range of at most 90 days,
covering accounts, waitlist, hosted sessions, helper usage, minute grants
and consumption, orders and revenue.

#### Scenario: timeseries fills empty days

- **WHEN** a 30-day timeseries is requested for a period with activity on
  only 3 days
- **THEN** all 30 buckets are returned with zeros on inactive days.

### Requirement: Panel mutations are audited

Welcome-policy changes, minute campaigns (including single-user campaigns),
waitlist deletions and forced hosted-session closes SHALL reuse the
existing audited domain functions and SHALL additionally record an
`admin_actions` row (admin, action, target, reason, detail) in the same
transaction. The reason field SHALL be mandatory for every mutation.

#### Scenario: policy change records who and why

- **WHEN** the operator updates the welcome policy with a reason
- **THEN** `minute_policy_audit` and `admin_actions` both gain rows naming
  the admin and the reason, and a stale version returns 409 without any
  write.

#### Scenario: force close settles funds

- **WHEN** the operator force-closes an active hosted session
- **THEN** the session is marked for close with reason `admin_close` and
  the existing reconciliation settles its funding like a user close.

### Requirement: Static panel served at the admin origin

The panel UI SHALL be static files (vendored, pinned theme, table and
chart libraries, no build step) served by Caddy on `admin.$API_DOMAIN`,
proxying `/v1/admin/*` to the API. The admin origin SHALL emit a
content-security-policy restricting scripts, styles, connections and
images to same-origin (the login document may allow the identity
provider's script origin), and the API container topology SHALL be
unchanged apart from mounts and environment passthrough.

#### Scenario: panel loads without build tooling

- **WHEN** the operator opens `https://admin.$API_DOMAIN/` and signs in
  with Google
- **THEN** dashboards and tables render using only same-origin vendored
  assets and the admin API.

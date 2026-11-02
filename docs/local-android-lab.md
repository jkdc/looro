# Local mobile lab: a physical Android phone against the Docker API

Use this lab to run a **debug** APK on a phone that talks to the API running on
your development machine over the same WiFi. No public server, DNS record or
TLS certificate is involved.

Release builds are out of scope by design: they keep
`usesCleartextTraffic="false"` and only accept an `https` origin on port 443.
See [Build and test](build-and-test.md) for the regular flows.

## Why the plain `make up` stack is not enough

- The `api` container publishes no port; only Caddy listens on 80/443, with an
  internal CA the phone does not trust.
- Client routes reject direct requests without trusted-proxy headers
  (`503 trusted_proxy_required`) unless `ACCOUNTS_ALLOW_LOCAL_LOOPBACK=true`.
- `ACCOUNTS_ENABLED` is off in the normal stack, so `/v1/account`, progress
  sync and the token proxy are unavailable.

The `compose.lab.yaml` overlay fixes the three at once: it publishes the API on
`0.0.0.0:8080` in cleartext, enables accounts and trusts private-address
clients for client-keyed admission.

Accounts also refuse to boot without an identity provider, and the lab never
runs the OAuth exchange. The overlay therefore supplies a placeholder Android
client ID; real `GOOGLE_*` values from `.env`, when present, take precedence.

## Prerequisites

- The phone and this machine on the same WiFi network, with no client
  isolation between them (guest networks usually block this).
- USB or wireless debugging authorisation for `adb`.
- `openssl` is not needed: the helpers use Python 3, already required by the
  content scripts.

## Quick start

```sh
make lab-up        # generates the lab secrets, then starts the stack
make lab-install   # builds a debug APK for this machine's LAN IP and installs it
make lab-account   # seeds a lab account and prints the adb command to adopt it
```

`make lab-account` prints a single `adb shell am start …` line carrying
`debug.accountID` and `debug.accountToken`. Run it, then open the app. The
debug seam stores that bearer for 24 hours, so you do not need Google sign-in.

Re-running `make lab-account` adds a new 7-day server session for the same
account (`lab@palique.test`) and prints a new token. Nothing else changes.

Derived targets and overrides:

| Variable | Default | Meaning |
| --- | --- | --- |
| `LAB_PORT` | `8080` | Host port for the API |
| `LAB_HOST_IP` | detected | LAN address compiled into the APK |
| `LAB_API_BIND` | `0.0.0.0` | Bind address for the published port |

```sh
make lab-up LAB_PORT=8090
make lab-apk LAB_HOST_IP=192.168.1.50
```

`make lab-down` stops the stack and keeps the volumes. `make up` returns to the
normal Caddy-only topology.

## What the lab does and does not prove

Works against the local API:

- Account session, profile, wallet and minute balances.
- Learning progress sync and exam-attempt outbox sync.
- Level tests (deterministic pools and any generated content already in the
  database).
- AI feedback reports, when the Anubis/proxy configuration allows them.

Not covered:

- Release hardening: certificate rules, `usesCleartextTraffic="false"` and the
  `https`-only origin validation never run here.
- Google Play billing: the Play flow requires a release build with a real
  application ID and a public `https` origin. Leave
  `app.minutePurchasesEnabled=false` in the lab.
- Google sign-in: the OAuth exchange needs a real Android client ID registered
  against your API origin. The lab uses the debug bearer seam instead.
- Other devices or mobile data: the lab trusts RFC 1918 clients only, and the
  phone must stay on the same network.

## Using the first-party conversation engine (`api` / hosted-proxy)

The `api` engine talks to `/v1/proxy/responses` and needs two more things
that the lab does not set up by default:

1. An LLM provider for the proxy, configured in the admin panel's
   **Configuración de IA** screen (the same configuration exam generation
   uses). Open the panel with `make admin-open` (create the operator first
   with `make admin-add` if you do not have one), pick a provider and model —
   OpenCode's free models work — and save. The proxy resolves provider, model
   and key from that stored config on every call; the old
   `PROXY_PROVIDER_URL`/`PROXY_PROVIDER_KEY` environment variables are gone.
2. An unlocked entitlement and a positive balance for `lab@palique.test`.
   Both are ledger-backed operator actions, not row edits: use
   `tokens-admin grant-unlock` and `grant-balance` with the JSON payload the
   CLI documents (`node dist/src/tokens-admin.js` with no arguments prints the
   usage).

For conversation testing without that plumbing, build with a local engine
instead (`-Papp.conversationEngine=glm`, or `openai` with a key entered in
Settings) and use the local API only for accounts, progress and exams.

## Security notes

- The published port is reachable by every host on your LAN, in cleartext, with
  accounts and lab admission enabled. Treat it as disposable.
- `ACCOUNTS_ALLOW_LOCAL_LOOPBACK=true` keys rate limits by socket address
  instead of signed proxy headers. That is correct for a lab and wrong for
  production; the API refuses to start with duplicate or malformed secrets, but
  it cannot tell a lab from a hostile LAN.
- The lab account credentials are printed by `make lab-account` and are valid
  for seven days. Stop the stack when you are done.

## Troubleshooting

- **`503 trusted_proxy_required`**: the container is running without the lab
  overlay. Run `make lab-up` (not `make up`).
- **Connection refused / timeout from the phone**: check the firewall on this
  machine (`sudo ufw allow 8080/tcp`, or the equivalent), that the phone is on
  the same subnet, and that the access point does not isolate clients.
- **The app loads but account features stay off**: `app.apiOrigin` did not
  reach the build, or the IP is not private. Debug builds only accept `http`
  on `10.0.2.2`, `localhost`, `127.0.0.1` and RFC 1918 addresses. Rebuild with
  `make lab-apk LAB_HOST_IP=<ip>`.
- **`proxy_unavailable` (503) from the `api` engine**: no LLM provider is
  configured in the panel's Configuración de IA screen and no `LEVELTEST_LLM_*`
  environment fallback is set. Configure the provider in the panel (or set the
  env fallback in `services/api/.env` and restart) and retry.
- **`unlock_required` (403) or `balance_exhausted` (402)**: the lab account has
  no entitlement or no minutes. Grant both through `tokens-admin`.
- **Build fails because the origin is empty**: IP detection did not work (some
  VPNs and container networks confuse it). Pass `LAB_HOST_IP` explicitly.
- **`voice_worker_already_running` right after `make lab-up`**: with
  `HOSTED_VOICE_EXPERIMENTAL=true` the API takes a PostgreSQL advisory lock and
  the outgoing container may still hold it. `make lab-up` stops the API first so
  this is rare; if it appears, the container restarts itself once the lock
  clears, or run `make lab-down && make lab-up`.

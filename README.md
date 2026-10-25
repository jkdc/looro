# Palique

**The language app you eventually delete.**

<p align="center">
  <img src="marketing/screenshots/iphone-17-spanish/01-hola.png" width="24%" alt="Palique greeting in Spanish with voice controls" />
  <img src="marketing/screenshots/iphone-17-spanish/02-conversacion.png" width="24%" alt="Spanish café conversation with English meaning subtitles" />
  <img src="marketing/screenshots/iphone-17-spanish/03-temas.png" width="24%" alt="Conversation themes for learning Spanish" />
  <img src="marketing/screenshots/iphone-17-spanish/04-palabras.png" width="24%" alt="Spanish vocabulary with three levels of recall strength" />
</p>

Palique is a native iPhone and Android app for learning through conversation. Speak to a warm, animated orb, follow the meaning when you need it, and practise words again in later conversations. Palique adjusts the challenge from the evidence in your replies.

Built with SwiftUI and Liquid Glass on iPhone, and Jetpack Compose on Android. Learning records stay on your device. The Android client selects its conversation engine at build time (OpenAI, DeepSeek or GLM): your OpenAI key is stored with Android Keystore, while DeepSeek and GLM keys live in the Git-ignored `api-keys.properties` you push as `api_keys.properties` to the app's external files directory. It needs an internet connection, but no Palique account or running Mac.

## Android

A native Android client is available in [`apps/android/`](apps/android/README.md), with voice and written conversation, the same eight language modules, local learning records and iPhone-compatible JSON backups. Its interface is English, and Spanish on a phone set to Spanish. It runs on Android 8.0 or later and uses your own API key for the engine you build in: the OpenAI key is stored with Android Keystore, while DeepSeek and GLM keys live in the Git-ignored `api-keys.properties` pushed as `api_keys.properties` to the app's external files directory (see [conversation engines](apps/android/docs/conversation-engines.md)). The iPhone client remains available below.

See the [Android installation/build guide](docs/run-on-android.md) and the (historical, 12 September) [Android verification record](verification/android-validation.md). Build a personal-install APK with Java 17 and Android SDK 36:

```sh
cd apps/android
./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Local development with Docker (Makefile)

The whole backend runs in Docker with a single command: PostgreSQL, schema migrations, the API, the operator admin panel and Caddy. From the repository root:

```sh
make up          # build and start the stack
make health      # check /healthz and /readyz
```

Once it is up:

- **API**: `https://localhost/healthz` (Caddy issues an internal certificate, so browsers will ask you to trust it once and `curl` needs `-k`).
- **Admin panel**: `https://admin.localhost` — dashboards, table browser and audited operator actions. Login is local-only; create your operator account first with `make admin-add USER=<name> EMAIL=<you@example.test>` and set its password with `make admin-passwd ADMIN_ID=<id>`.
- Data persists in the named Docker volumes `postgres_data` and `caddy_data`; `make down` stops the stack without touching them.

`make help` lists every target. The most useful ones:

| Target | What it does |
| --- | --- |
| `make up` / `make down` | Start / stop the local stack (data is kept) |
| `make logs` / `make status` | API logs and container status |
| `make psql` | psql console against the local database |
| `make migrate` | Apply pending migrations |
| `make test-api` | Full API integration suite in an isolated smoke stack (never touches your local data) |
| `cd services/api && npm test` | Same isolated PostgreSQL-backed API suite; disposable DB volume is removed by default |
| `cd services/api && npm run test:unit` | Fast, partial API tests; database-dependent tests may be skipped |
| `make check` | Cross-platform contracts CI runs (generated content, Swift↔Kotlin) |
| `make android-build` / `android-install` / `android-run` | Build, install and launch the debug APK on a connected device |
| `make ios-build` / `ios-test` / `ios-device` | iOS simulator build, simulator test suite, install on a connected iPhone |

English generated exams require active judge evidence to publish: verification off creates private drafts only. New drafts use their frozen generation blueprint; legacy English drafts without a complete contract must be regenerated. See [English exam guarantees and LLM-reviewed coverage](services/api/docs/english-generated-exams.md).

Before enabling enhanced generated exams for another language, follow the [technical language-onboarding guide](services/api/docs/generated-exam-language-onboarding.md). The shared engine is reusable, but each language needs reviewed pedagogical configuration and explicit activation of generation/publication guarantees; adding a translated JSON file is not enough.

The mobile apps do not run in Docker — they build natively because they need Xcode (macOS) and the Android SDK on the host. Everything else (backend, admin panel, database, tests) goes through the Makefile.

## API endpoints for local testing

Everything the backend serves hangs off the API port. With `make up` the API is
behind Caddy at `https://localhost` (add `-k` to curl for the internal
certificate); with the phone/emulator lab stack below it is plain
`http://<PC-IP>:8080`. Public paths need no auth; paths marked **Bearer**
need `Authorization: Bearer <token>` from a signed-in account.

Health and catalog (public):

| Method & path | What it is |
| --- | --- |
| `GET /healthz` · `GET /readyz` | Liveness and database readiness |
| `GET /v1/pricing` | Price catalog (nanoUSD rates, voice, text) |
| `GET /v1/level-tests/current?language=en&level=A1` | This week's deterministic exam (bundled banks) |
| `GET /v1/level-tests/generated?language=en&level=A1&variant=1` | This week's LLM-generated exam (404 until generated and approved) |
| `GET /v1/level-tests/hybrid?language=en&level=A1` | Generated exam when available, deterministic otherwise (what the apps fetch) |
| `GET /v1/level-tests/exams/<examId>` | A published exam snapshot by content id |
| `GET /v1/minutes/products?provider=stripe` | Purchasable minute packs |
| `GET /v1/auth/providers` | Sign-in providers configured on this deployment |

```sh
curl -s http://127.0.0.1:8080/v1/level-tests/hybrid?language=en&level=A1 | head -c 200
```

Accounts and money (Bearer auth; guest paths noted):

| Method & path | What it is |
| --- | --- |
| `POST /v1/auth/challenge` → `POST /v1/auth/exchange` | Google/Apple sign-in handshake (mints the bearer) |
| `GET /v1/account` · `GET /v1/minutes` · `GET /v1/wallet` | Profile, conversation-minutes balance, payment wallet |
| `POST /v1/minutes/welcome` · `POST /v1/guest/minutes` | Welcome grant (attestation-gated); guest trial minutes |
| `GET /v1/minutes/products` · `POST /v1/minutes/orders` · `GET /v1/minutes/orders/:id` | Pack catalog, checkout, order status |
| `GET /v1/subscriptions/plans` · `GET /v1/subscriptions/mine` · `POST /v1/subscriptions/checkout` | Subscription tiers and state |
| `DELETE /v1/account` · `POST /v1/auth/sign-out` | Delete (with revocation) and sign out |
| `POST /v1/checkout` · `POST /v1/trial/eligibility` | Sandbox card checkout and trial attestation |

Learning sync (Bearer auth — what the exam-recovery feature uses):

| Method & path | What it is |
| --- | --- |
| `GET /v1/progress` · `POST /v1/progress` | Cumulative learning progress ledger (monotonic merge) |
| `POST /v1/usage/events` | Content-free usage telemetry |
| `POST /v1/level-test-attempts/sync` | Create exam attempts with their frozen snapshots (idempotent per attempt) |
| `POST /v1/level-test-attempts/events` | Append attempt progress/completion events (CAS by revision, 409 on divergence) |
| `GET /v1/level-test-attempts?limit=10&cursor=…` | Own attempt history, bounded pages with opaque cursor |
| `GET /v1/level-test-attempts/:attemptId` | One own attempt with its frozen snapshot |

```sh
TOKEN=…   # bearer from a signed-in account
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/v1/level-test-attempts?limit=10
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   -d '{"entries":[…]}' http://127.0.0.1:8080/v1/progress
```

Also served (Bearer unless noted): `POST /v1/feedback/ai` (in-app AI reports),
`POST /v1/access-requests` (public waitlist), `GET /v1/feedback/capabilities`,
`/v1/live/*` (hosted voice sessions, gated), `/v1/proxy/responses` (token-billing
proxy, unlock required), and the sandbox `POST /v1/checkout` +
`POST /v1/stripe-webhook` pair when Stripe test keys are configured.

The operator panel (browser at the admin origin) drives `/v1/admin/*`:
dashboards, table browser, welcome policy, subscription plans, minute
campaigns, LLM provider config with connection test, and the level-test
moderation surface (status, private drafts, audited approve/reject).

### Phone/emulator lab against the Docker API

The Android debug build accepts an `app.apiOrigin` Gradle property and, in
debug builds only, http origins on the local network plus a deterministic
sign-in seam for a seeded account (24-hour session):

```sh
# 1. API + database with accounts enabled (host network, LAN-reachable)
docker run -d --name lab-db -e POSTGRES_DB=app -e POSTGRES_USER=app   -e POSTGRES_PASSWORD=local-only -p 127.0.0.1:5439:5432 postgres:17-alpine
docker run --rm --network host -e DATABASE_URL=postgresql://app:local-only@127.0.0.1:5439/app   app-server-local:dev node dist/src/migrate.js
docker run -d --name lab-api --network host   -e DATABASE_URL=postgresql://app:local-only@127.0.0.1:5439/app -e PORT=8080   -e PUBLIC_ORIGIN=http://localhost:8080 -e ACCOUNTS_ENABLED=true   -e ACCOUNTS_HMAC_KEY=<64-hex> -e ACCOUNTS_PROXY_TOKEN=<64-hex>   -e ACCOUNTS_ALLOW_LOCAL_LOOPBACK=true   -e GOOGLE_CLIENT_ID=1234567890-abc.apps.googleusercontent.com   -e GOOGLE_ANDROID_SERVER_CLIENT_ID=1234567890-def.apps.googleusercontent.com   -e GOOGLE_ANDROID_CLIENT_IDS=1234567890-def.apps.googleusercontent.com   --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges   app-server-local:dev node dist/src/main.js

# 2. Seed a lab account whose bearer the app can adopt (debug builds only)
TOKEN=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64url').slice(0,43))")
HASH=$(node -e "console.log(require('crypto').createHash('sha256').update(process.argv[1]).digest('hex'))" "$TOKEN")
docker exec -i lab-db psql -U app -d app <<SQL
INSERT INTO accounts(id, email) VALUES(gen_random_uuid(), 'lab@example.test');
INSERT INTO auth_sessions(id, account_id, token_hash, expires_at)
  SELECT gen_random_uuid(), id, '$HASH', now() + interval '7 days' FROM accounts WHERE email='lab@example.test';
SQL

# 3. Build the APK pointed at the PC's LAN IP and install it
cd apps/android && ./gradlew -Papp.apiOrigin=http://<PC-LAN-IP>:8080 :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Launch the app adopting that bearer (debug builds only)
adb shell am start -n com.jkdc.palique.android/com.jkdc.app.MainActivity   --es debug.accountID <account-uuid> --es debug.accountToken "$TOKEN"
```

`ACCOUNTS_ALLOW_LOCAL_LOOPBACK=true` also lets direct clients without proxy
headers use the API from the machine itself or the local network — a lab-only
convenience; never enable it in production.

## Get started

You need a Mac with Xcode 26 or later, an iPhone running iOS 26.1 or later, an Apple Account, and an OpenAI API project with billing and access to GPT-Live-1 and GPT-5.6 Luna. A ChatGPT subscription does not provide API credit.

### Install with a local AI agent

If Codex or another coding agent has access to your Mac's files and terminal, paste the prompt below. The agent can clone, build and install Palique. You handle Apple Account sign-in and team selection in Xcode, device trust and Developer Mode prompts, and API-key entry inside the app. The [iPhone installation guide](docs/run-on-iphone.md) covers each step.

```text
Help me build and install Palique on my iPhone from https://github.com/jkdc/palique.

Clone the repository into a new local folder, or use this checkout if it is
already open. Read README.md, docs/run-on-iphone.md and docs/build-and-test.md.
Check that Xcode and its iOS tools are ready, resolve the pinned dependencies,
run the offline core tests, and build the iOS Simulator target.

Guide me through adding my Apple Account and choosing my signing team in
Xcode. For a first installation, help me choose a unique bundle identifier if
needed. Preserve the existing team and identifier when updating Palique, and
do not uninstall it or erase its learning data.

Detect my connected iPhone, build with the configured signing team, install
Palique and launch it. Tell me when I need to unlock the phone, trust this Mac
or the developer profile, enable Developer Mode, or approve a system prompt.

I will choose my learning and subtitle languages, then enter my own OpenAI
API key in Settings > Advanced > Use your own API key. Do not ask me to paste
the key into chat, read it from Keychain, or put it in source files or logs.
Leave managed accounts, hosted trials and purchases disabled.

Finish by reporting which build and installation checks passed, and anything
I still need to do on the phone. I will start the first live conversation.
```

### Install with Xcode

Updating an earlier checkout? The iPhone project now lives in `apps/ios/`. Before opening it, follow the [local-settings migration steps](docs/run-on-iphone.md#update-an-earlier-checkout) to preserve your signing team, account configuration and existing app identity.

1. Clone [jkdc/palique](https://github.com/jkdc/palique), or download its ZIP. Open `apps/ios/App.xcodeproj`.
2. In Xcode, open **Settings → Accounts** and add your Apple Account.
3. Select the **App** target, open **Signing & Capabilities**, enable automatic signing, and choose your team. For your own fork, replace the bundle identifier with a unique value such as `com.yourname.palique`. Keep that value stable for later updates.
4. Connect and unlock your iPhone. Trust the Mac if prompted. Turn on **Settings → Privacy & Security → Developer Mode** on the phone, restart, and confirm the setting.
5. Select **App** as the scheme and your iPhone as the destination, then click **Run**. If iOS asks you to trust the developer, do so in **Settings → General → VPN & Device Management**.
6. Choose your learning and subtitle languages in the welcome screens. In **Settings → Advanced → Use your own API key**, save your own OpenAI project key. Start a conversation and allow microphone access.

You should hear Palique greet you in your chosen language. You can now disconnect your phone from the Mac and use Wi-Fi or cellular.

A free Personal Team can run the app on your own phone; TestFlight and App Store distribution require Apple Developer Program membership. Free provisioning profiles expire after seven days. Refresh by running the same project again, preserving the team and bundle identifier. Export a learning backup before changing either or switching phones. See the [detailed iPhone guide](docs/run-on-iphone.md) for common setup problems. [Apple membership guidance](https://developer.apple.com/support/compare-memberships/)

## What works today

- **A warm welcome:** choose a learning language and a subtitle language in two short screens, with a greeting that changes languages.
- **Conversation practice:** live voice, gentle corrections, optional meaning subtitles, word lookup, mute, and a typed reply when speaking is inconvenient.
- **Tuned to you:** a slower-speech setting for new conversations, and a listening-practice mode: a short story without meaning subtitles (the written story stays on screen, and word lookup remains).
- **Practice between conversations:** a Progress screen with totals, day streaks and your last two weeks, plus a writing drill over due words on the Words tab.
- **Themes:** 24 conversation settings, with cultural details supplied by each language module. You can also request a current topic; on the OpenAI engine web search supplies source links, while the DeepSeek and GLM engines answer without web sources.
- **Adaptive practice:** vocabulary and provisional ability observations come from validated conversation evidence. Each learning language keeps separate progress.
- **Recall bars:** one to three bars summarise repeated retrieval over time. Three bars require spaced evidence in different contexts. These are product heuristics, not calibrated forgetting probabilities or a language certificate.
- **A fresh start:** the Talk screen returns to its greeting 15 seconds after a conversation ends. Tap **New conversation** to reset immediately. Your saved conversations and learning remain.
- **Local records:** export or import a JSON learning backup, delete a conversation, or delete all learning data from Settings.

The modules teach Norwegian Bokmål with an Eastern Norwegian voice target, Spanish from Spain, international English, French from France, German from Germany, Italian from Italy, Brazilian Portuguese and Standard Mandarin with Simplified Chinese. Each language has its own conversation themes, teaching guidance and progress. Valid regional alternatives are accepted.

On iPhone, Mandarin includes optional pinyin in Talk, transcripts and word details. Chinese word lookup uses word boundaries, and the original characters remain available for copying from transcripts. Pinyin uses system dictionary readings; names, ambiguous words and tone changes in connected speech still need listening checks. Voice accent and teaching guidance are model instructions, and fluent-speaker review is still needed before making pronunciation or learning-effectiveness claims.

## Privacy and API costs

Palique stores conversations, vocabulary and preferences on your device. There is no Palique cloud sync, analytics SDK or advertising. The optional iPhone account feature stores signup data on the account service; conversations and vocabulary stay local. Your API key is stored in the device’s Keychain, excluded from learning exports, and sent only to OpenAI.

During practice, audio, selected conversation text, learning context and requested searches go to OpenAI. Palique does not save raw audio. API requests set `store: false` where supported, but that does not disable all provider retention; OpenAI’s abuse-monitoring rules and your project’s settings still apply. [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data)

OpenAI bills your project for voice, text and search. The app’s usage display is an estimate, and its conversation time limit is not a billing cap. Check your OpenAI project’s usage and spending settings.

## Planned public service

Hosted free conversations and minute purchases are **not active**. Optional Google sign-in exists on iPhone and is implemented on Android (same account service, encrypted Keystore session, guest-minute transfer on sign-in), with a real production sign-in still to be verified on an installed Android build. The [API foundation](services/api/README.md) contains identity verification, audited minute allowances and guest transfers, plus the earlier sandbox payment support. [Minute controls](docs/conversation-minutes.md) describe what is implemented and what remains disabled. Its runbook lists the remaining work before commercial activation. No shared provider key belongs in this repository or a distributed app binary.

A public TestFlight link and App Store listing are not yet available. [Release preparation](release/README.md) records the outstanding requirements.

## Build and test

From the repository root:

```sh
swift test --package-path apps/ios
xcodebuild -project apps/ios/App.xcodeproj -scheme App \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
```

For UI tests, create or select an iPhone 17 simulator in Xcode, then run **Product → Test**. The tests use in-memory fixtures and do not require an API key. More commands and preview options are in [the build guide](docs/build-and-test.md).

The iPhone language release recorded on 13 September 2026 passed **70 core tests and 20 native UI tests** — a historical record; the iOS core suite now holds **88 tests**. On 20 September 2026 the backend suite passed **358 tests with an isolated PostgreSQL 17 database and no skips** (Node 22), and the Android branch runs **390 unit tests** across the app and on-device engine modules with no skips. German, Italian, Brazilian Portuguese and Mandarin each passed a live iPhone check using synthetic typed replies and real voice output, meanings and word lookup. These checks do not establish human speech-recognition, pronunciation or correction quality. [Verification record](verification/validation.md)

The Android release branch is being prepared separately. See [release progress](verification/android-release-progress.md) for its checks and remaining gates.

## Code map

| Directory | Contents |
| --- | --- |
| `apps/android/` | Native Kotlin/Compose Android client and tests |
| `apps/ios/App/` | SwiftUI views, SwiftData storage, Keychain, WebRTC transport and API coordination |
| `apps/ios/Core/` | Language modules, teaching policy, transcripts, vocabulary evidence and recall projection |
| `apps/ios/Tests/` | Core learning and translation tests |
| `apps/ios/UITests/` | Native interface tests |
| `shared/` | API contracts and fixtures exercised by both native clients |
| `scripts/` | Project generation, language export and compatibility checks |
| `docs/` | Setup, build and language-module guides |
| `release/` | Submission drafts and public-release checks |
| `services/api/` | Account, billing and hosted-service foundation; see its runbook before deploying |

Read [how the language architecture works](docs/language-architecture.md) and [how to add a language](docs/add-language.md). Contributions should follow [CONTRIBUTING.md](CONTRIBUTING.md); security issues belong in the [private reporting process](SECURITY.md).

## Dependencies and license

The native WebRTC package is pinned to [stasel/WebRTC 152.0.0](https://github.com/stasel/WebRTC/tree/152.0.0). The app bundles [third-party notices](apps/ios/App/ThirdPartyNotices.txt) and the SDK’s privacy manifest. Review upstream notices when changing the dependency.

The iOS learning-database driver is pinned to [groue/GRDB.swift 7.8.0](https://github.com/groue/GRDB.swift/tree/7.8.0); the schema it opens is generated from [shared/db/schema.sql](shared/db/schema.sql). Review upstream notices when changing the dependency.

Palique is released under the [MIT License](LICENSE). Third-party components retain their own licenses. The Palique name and logo identify the original project; the software license does not grant trademark rights.

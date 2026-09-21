# Design: ios-backend-parity

## Wire shapes (mirrored from the Android clients, verified against `services/api`)

- **Create** `POST /v1/live/sessions` — headers `Authorization: Bearer <member
  token>`, `Idempotency-Key: <UUIDv4>`; body
  `{sdp, language, instructions, history, requestedMilliseconds}` (flat —
  NOT the OpenAI envelope the personal-key path uses). Response:
  `{session, transport: {sdp}, deadline, limitMilliseconds|reservedMilliseconds,
  billingBasis, experimental, …}`; the transport only needs
  `session` + `transport.sdp` to finish WebRTC.
- **Status/Close/Current** — `GET /v1/live/sessions/:id`,
  `POST …/:id/close` (empty body), `GET /v1/live/sessions/current`.
- **Capabilities** `GET /v1/live/capabilities` → `{hostedMinutes, experimental}`.
- **Balance** `GET /v1/minutes` → milliseconds balance with
  `availableMilliseconds = balance - reserved`; can-start when
  `availableMilliseconds > 0`.
- **Helpers** `POST /v1/live/sessions/:id/helpers` — body
  `{requestID (UUIDv4), purpose, instructions, input, schema?, search?}`;
  purposes `meaning | assessment | lookup | delegation | typed_reply | topic`;
  response `{requestID, text, sources[], usage{inputTokens, outputTokens,
  searchCalls}}` mapped onto the existing `APIResult`.
- **Errors** — `{error: {code, retryable, retryAfterMilliseconds}}` +
  `X-Palique-Error-Reference`; codes are matched against a safe allowlist
  (`sign_in_required`, `insufficient_minutes`, `rate_limit`,
  `service_unavailable`, …) and otherwise shown as a generic failure.
- **Reports** — `GET /v1/feedback/capabilities` (`aiReports`),
  `POST /v1/feedback/ai` with `{reportID, languageID, reason, excerpt,
  consentVersion: "ai-report-v1"}`, no Authorization, HTTP 202 echoing
  `{accepted, reportID}`.

## iOS structure

- `ManagedAccountClient` gains the hosted + minutes + helpers calls (one
  auth/error path, existing session plumbing) and a parse for the minutes
  balance. Report submission is separate and credential-free
  (`ReportClient.swift`).
- `LiveTransport.connect` refactors its `api.post("live/sessions", …)` into an
  `establish` closure parameter (`offerSDP → (session, answerSDP)`); audio,
  ICE, data channel and metering are untouched.
- `ConversationCoordinator`:
  - Chooses the provider at `start()`: hosted only when the flag is on,
    managed account signed in, capabilities allow it, balance can start, and
    the stored preference says hosted (default personal key — an explicit
    choice, so paid minutes are never spent silently). Provider and hosted
    session id live in memory for the session's lifetime.
  - Ends hosted sessions with `close` + status poll to `closed` (8 s),
    settles an orphaned `current` session before sign-out.
  - Routes all helper calls through one `respond(purpose:…)` that uses the
    hosted helpers endpoint while a hosted session is active and the personal
    key otherwise. Meanings, assessment, delegation, typed replies, lookup
    and topic search keep their existing prompts byte-identical.
- Provider preference: local `UserDefaults` (mirrors Android's local
  provider store — deliberately NOT an Archive field, so no cross-platform
  migration is needed).
- Config: `PALIQUE_APP_HOSTED_MINUTES_ENABLED` in
  `Config/ManagedAccounts.xcconfig` (default NO) surfaced to the app through
  Info.plist next to the existing sign-in flags.

## UI

- Settings: when the hosted flag is on and a managed account is signed in, a
  "Conversation" section shows the provider picker (personal key / included
  minutes) and the available-minutes line fetched on demand.
- Talk transcript: a flag control on assistant passages opens the report
  sheet (reason picker, editable excerpt ≤2000 chars prefilled with the
  passage text, consent toggle, submit with result states).

## Tests

- Parsing of create/status/balance/helpers payloads and the report echo in
  XCTest with canned JSON (macOS gate), mirroring the JVM `HostedAPIClientTest`
  cases; no live network in tests.
- Gates: macOS `swift test` + Xcode build REQUIRED before merge; device
  check of one hosted session + one report against a configured server.

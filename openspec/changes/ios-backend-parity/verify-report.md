# Verify Report: ios-backend-parity

Date: 2026-09-21 · Branch: `feature/rebranding-looro` (local, unpushed)

## What was delivered

- **Hosted client (iOS):** `HostedSessions.swift` — capabilities, create
  (flat body + `Idempotency-Key` UUID, 1 MiB response allowance), status,
  close, current, minutes balance (unit/billingBasis/invariant validated,
  `canStartConversation`), and the helpers channel for the six purposes,
  returning the same `APIResult` the personal-key client produces. Safe
  error mapping (`sign_in_required`, `insufficient_minutes`, `rate_limit`,
  default → unavailable).
- **Transport seam:** `LiveTransport.connect(establish:)` — the coordinator
  supplies the session establishment (personal-key OpenAI envelope, byte
  identical to before, or hosted flat body). Audio keeps connecting
  phone-to-provider directly over WebRTC.
- **Provider selection & lifecycle:** explicit opt-in stored locally
  (default personal key — hosted minutes are never spent silently);
  capabilities + balance checked at start; hosted sessions are closed and
  polled to `closed` (8 s) on end; an orphaned `current` session is settled
  before opening a new hosted one. Settings gains the provider picker and
  balance line; the account screen copy reflects the new capability.
- **Helpers routing:** one `HelperRouter` replaces the six direct
  `APIClient.respond` call sites (meanings, assessment, delegation, typed
  replies, lookup, topic); hosted session → backend helpers endpoint,
  otherwise unchanged behaviour.
- **Reports:** credential-free client (capabilities + 202 echo) and a
  report sheet (four reasons, editable ≤2000-char excerpt, consent) with an
  entry on assistant passages in the conversation transcript.
- Config flag `APP_HOSTED_MINUTES_ENABLED` (default NO) via
  `ManagedAccounts.xcconfig` + Info.plist; Xcode project regenerated.

## Verified on this machine

- Wire shapes checked line-by-line against `services/api/src/app.ts` +
  `live-provider.ts` and the Android clients (`HostedAPIClient.kt`,
  `MinuteCommerceClient.kt`, `ReportClient.kt`), whose parse rules this
  port mirrors (create/status/balance invariants, helper purposes and
  limits, report echo, safe error codes).
- `scripts/generate_project.py` regenerated `App.xcodeproj` with the two
  new files; both referenced.
- No Android, backend or Core changes: Android suites, the backend suite
  and `check_cross_platform.py` are untouched by this change.

## Gates pending (not run here)

- **macOS gate (REQUIRED before merge):** Xcode build + `swift test` for the
  package; iOS app-side code has no on-device test target and is
  reasoning-verified only (repo norm).
- **Device checks against a configured server:** one hosted conversation
  end-to-end (create → conversation → close confirmed), helpers on hosted
  minutes, provider picker, and one report submission. Without these the
  hosted path must stay flag-off (it is off by default).

## Parked with external blockers (documented, not forgotten)

- Purchases on iOS → needs a StoreKit provider on the backend + App Store
  setup.
- Apple Sign-In → needs Apple developer enrollment + an Apple auth provider
  on the backend.
- Guest trial minutes → Play-Integrity-bound by backend design.

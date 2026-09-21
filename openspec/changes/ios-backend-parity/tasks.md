# Tasks: ios-backend-parity

## Task 1 — Hosted + minutes client on iOS

- [ ] 1.1 ManagedAccountClient: capabilities, create (Idempotency-Key),
      status, close, current, minutes balance, hosted helper — Android
      shapes, safe error decode.
- [ ] 1.2 Config flag `PALIQUE_APP_HOSTED_MINUTES_ENABLED` (default NO) via
      Info.plist, next to the sign-in flags.
- [ ] 1.3 XCTest parsing cases for create/status/balance/helper payloads.

## Task 2 — Transport seam + provider selection

- [ ] 2.1 `LiveTransport.connect(establish:)` closure refactor; personal
      path byte-identical when hosted is off.
- [ ] 2.2 Coordinator: provider choice at start (flag + signed-in +
      capabilities + balance + stored preference, default personal key),
      hosted session id tracking, close+confirm on end, orphan settle on
      sign-out.
- [ ] 2.3 Settings: provider picker + available minutes when available.

## Task 3 — Helpers routing

- [ ] 3.1 `respond(purpose:…)` router: hosted helpers while a hosted session
      is active, personal key otherwise; all six call sites switched
      (meanings, assessment, delegation, typed reply, lookup, topic).

## Task 4 — Reports parity

- [ ] 4.1 `ReportClient` (capabilities + submit, credential-free, 202 echo).
- [ ] 4.2 Report sheet (reason set, editable excerpt ≤2000, consent) with an
      entry point on assistant passages.

## Task 5 — Record close

- [ ] 5.1 verify-report.md with honest gates (macOS REQUIRED; hosted device
      check against a configured server; purchases/Apple/guests parked).

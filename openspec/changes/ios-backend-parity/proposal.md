# Proposal: ios-backend-parity

## Intent

Bring the iOS app to the same backend surface Android already uses: hosted
voice conversations on member minutes (with the hosted helpers channel), and
AI report submission. No backend changes are required — every endpoint
already exists and is exercised by the Android clients and the API tests.

## Problem

iOS today only talks to OpenAI directly with the learner's personal key:
`LiveTransport` posts the realtime session to `api.openai.com`, and all
learning helpers (meanings, assessment, delegation, typed replies, lookup,
topic search) go through the same personal-key client. Android additionally
supports the hosted path (`/v1/live/sessions` with member auth,
`Idempotency-Key`, minutes accounting, close confirmation and the helpers
channel) and reports (`/v1/feedback/ai`). The product posture
(`docs/managed-accounts.md`) already promises accounts on iOS; hosted
practice and reports are the missing pieces of that promise.

## Approach

- **Hosted client on iOS**: extend the managed-account client with the
  hosted endpoints (`capabilities`, `create`, `status`, `close`, `current`,
  helpers) and the minutes balance (`GET /v1/minutes`), mirroring the
  Android shapes exactly (flat create body with `Idempotency-Key` UUID,
  status/close parse, SAFE error codes, helper purposes
  meaning/assessment/lookup/delegation/typed_reply/topic with
  `requestID`). Guarded by a new public flag
  (`PALIQUE_APP_HOSTED_MINUTES_ENABLED`, default NO).
- **Transport seam**: `LiveTransport.connect` takes a session-establishment
  closure (offer SDP in → session JSON + answer SDP out). The coordinator
  supplies either the personal-key call (today's behaviour) or the hosted
  create, chosen by an explicit, locally persisted provider preference
  (default: personal key, mirroring Android's default). Hosted sessions are
  closed and confirmed on end, and an orphaned `current` session is settled
  before sign-out.
- **Helpers routing**: a small router replaces the coordinator's direct
  `APIClient.respond` calls; while a hosted session is active, meanings,
  assessment, delegation, typed replies, lookup and topic search go through
  `POST /v1/live/sessions/:id/helpers` (no personal key needed); otherwise
  they behave exactly as today.
- **Reports parity**: a credential-free report client (`capabilities` +
  `POST /v1/feedback/ai`, 202 echo) and a report sheet reachable from
  assistant passages, mirroring the Android reason set
  (offensive/incorrect/wrong_language/other), 2000-char excerpt and consent.

## Scope

- **In scope:** the four pieces above on iOS (client, transport seam,
  coordinator routing, reports UI), configuration flags, unit-testable
  parsing where feasible, and this record.
- **Out of scope (parked with external blockers):** purchases on iOS (needs
  a StoreKit provider on the backend plus App Store setup), Apple Sign-In
  (needs Apple developer enrollment and an Apple auth provider on the
  backend), guest trial minutes (Play-Integrity-bound by backend design),
  and the Android legacy commerce surfaces (`/v1/access-requests`,
  `/v1/trial/eligibility`, `/v1/checkout` — no mobile caller by design).

## Risks

- iOS is reasoning-verified only on this Linux machine: macOS
  `swift test`/`xcodebuild` is a REQUIRED gate before merge (unchanged
  policy). The hosted code path also needs a device check against a real
  server before any commercial use.
- Money-adjacent logic (minute balance gating, close confirmation): kept to
  the Android-proven shapes and defensive parsing; no new accounting rules
  are invented client-side.
- The transport seam touches the critical conversation path; the personal
  default keeps today's behaviour byte-identical when hosted is off.

## Delivery strategy

`single-pr` — direct conventional commits on the current local branch, one
commit per task: record → hosted client → transport/coordinator seam →
helpers routing → reports → verified close. No backend or Android changes.

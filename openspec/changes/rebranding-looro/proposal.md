# Proposal: rebranding-looro

## Intent

Replace the upstream product brand ("Mural", Hackmamba / Chuloo) with the
fork's own brand ("Looro") across all live code, configuration, user-visible
strings and documentation, so the fork can be published under its own name
without shipping upstream trademarks, identifiers or support contacts.

## Problem

The fork carries the upstream brand everywhere: the iOS bundle
`no.william.mural`, the Android namespace `chat.mural` /
applicationId `chat.mural.android`, the generated `Mural.xcodeproj`, ~19
brand-bearing Android strings plus Compose/Swift user-visible text, the
backend package `mural-server` with `mural` databases, roles, advisory-lock
keys, HMAC domains and `x-mural-*` headers, upstream support email
`hi@hackmamba.io` hardcoded in the account-deletion UI (a Google Play
requirement), upstream website/origin `mural.chat`, and release metadata
written for Hackmamba Inc. Store review rejects apps that ship another
entity's trademarks and identity. ~2,850 occurrences across ~430 tracked
files.

## Approach

Mechanical, ordered rename with special cases applied before the generic
substitution, scoped to live files only:

`no.william.mural → com.jkdc.looro`; `chat.mural / chat/mural / chat_mural →
com.jkdc.looro / com/jkdc/looro / com_jkdc_looro`; `mural.chat →
looro.example` (placeholder origin, RFC 2606); `MURAL_ → LOORO_`; then
`Mural → Looro`, `mural → looro`. Support email → centralized
`support@looro.example` constant with an explicit TODO before store
submission. Source directories are moved (`java/chat/mural →
java/com/jkdc/looro`), resource files renamed (`ic_mural → ic_looro`),
`MuralApp.swift / MuralViewModel / MuralIcons` renamed, the Xcode project
regenerated from the updated generator, and upstream repository links
repointed to `jkdc/looro`.

## Scope

- **In scope:** iOS identity (generator, `Package.swift`, Info.plist,
  xcconfig, Swift sources), Android identity (Gradle, namespaces, source
  tree, resources, manifest), user-visible strings on both platforms with
  `check_cross_platform.py` parity, backend (`services/api`), CI workflows,
  support-contact plumbing, live documentation, top-level release metadata
  templates.
- **Out of scope (untouched by design):** dated historical records
  (`verification/`, `release/android/evidence/`, dated release audits,
  closed `openspec/changes/`, prior `informe-*.md`), the MIT `LICENSE`
  copyright notice (legal requirement), vendored third-party trees, binary
  marketing screenshots (re-capture is a post-rename device/simulator task),
  and any new logo design (placeholder icon may follow separately).

## Risks

- JNI glue between `chat.mural` Kotlin and the whisper.cpp layer breaks the
  build if `chat_mural`-prefixed symbols are missed; mitigated by an explicit
  `chat_mural` rule and the engine-local unit suite.
- The cross-platform string parity check pins `TeachingPolicy` paths and
  wording; both sides are renamed in the same pass and the checker must stay
  green.
- The backend suite names databases/roles; tests run against freshly created
  databases so the rename is verified by the full docker suite.
- iOS is reasoning-verified only on this Linux machine; macOS `swift test` +
  xcodebuild is a REQUIRED gate before merge (unchanged policy).

## Delivery strategy

`single-pr` — direct conventional commits on `feature/rebranding-looro`
(never pushed), one commit per phase: record → iOS → Android → backend/CI →
support/docs → verified record. Android JVM suites, `app:lintDebug`,
`check_cross_platform.py` and the backend docker suite must be green before
the close.

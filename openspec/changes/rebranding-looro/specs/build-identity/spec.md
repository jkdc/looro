# Delta Spec: build-identity

## ADDED Requirements

### Requirement: Fork-owned brand identity

The application SHALL be branded "Looro" everywhere a product identity is
built or displayed: the iOS bundle identifier SHALL be `com.jkdc.looro`
with app target `Looro` in a generated `Looro.xcodeproj` and Swift package
`LooroCore`; the Android applicationId SHALL be `com.jkdc.looro.android`
with namespace `com.jkdc.looro` rooted at `java/com/jkdc/looro`. No live
build input SHALL reference the upstream brand (Mural, `no.william.mural`,
`chat.mural`) or its infrastructure domains.

#### Scenario: generated iOS project carries fork identity

- **WHEN** `scripts/generate_project.py` runs
- **THEN** it produces `Looro.xcodeproj` with target `Looro`, product
  `Looro.app`, UI-test target `LooroUITests`, and bundle identifier
  `com.jkdc.looro`.

#### Scenario: Android build carries fork identity

- **WHEN** the Android app assembles
- **THEN** the applicationId is `com.jkdc.looro.android`, the namespace is
  `com.jkdc.looro`, and app resources resolve `@mipmap/ic_looro` and
  `Theme.Looro`.

### Requirement: Fork-owned support contact

The in-app account-deletion and report surfaces SHALL present a support
contact owned by the fork, centralized in a single constant, clearly marked
as a placeholder to replace before store submission; no live surface SHALL
display an upstream entity address.

#### Scenario: account-deletion contact

- **WHEN** the account-deletion support dialog renders its contact
- **THEN** it shows the centralized placeholder email from the single
  constant, and the report sheet uses the same constant.

### Requirement: Historical records stay faithful

Dated historical records (verification runs, release evidence, closed
change records) SHALL keep the upstream-era naming they were produced with;
the rebrand SHALL NOT rewrite evidence or the MIT license notice.

#### Scenario: evidence sweep exemption

- **WHEN** the post-rebrand `mural` sweep runs over the repository
- **THEN** matches remain only in exempted historical paths, `LICENSE`, the
  rebranding record itself, and vendored third-party trees.

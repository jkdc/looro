# Delta Spec: build-identity

## ADDED Requirements

### Requirement: Single-file brand identity

The repository SHALL hold the product brand in one canonical file,
`branding.properties` at the repo root (name, reverse-domain base, iOS
bundle id, Android applicationId, Android namespace, support email and
URL). The Gradle build and the Xcode project generator SHALL derive their
identities from it rather than hardcoding them.

#### Scenario: identity follows the constants file

- **WHEN** `branding.properties` changes and the Xcode project is
  regenerated and the Android app is built
- **THEN** the produced binaries carry the bundle id and applicationId from
  the constants file, with no brand literals left in build scripts.

### Requirement: Brand-free structure

File names, class/type names, package/namespace names, resource names,
build-target names and backend/CI identifiers SHALL be generic and derived
from function (App*, LearningCore, app*), never from the product brand, so
a brand change SHALL NOT require moving or renaming any file, class,
package, target or backend identifier.

#### Scenario: future brand change surface

- **WHEN** a future rename is applied by editing `branding.properties` and
  running `scripts/apply_branding.py`
- **THEN** no file is moved and no class, package, target or backend
  identifier is renamed; only user-visible content and identity values
  change.

### Requirement: Rename engine

The repository SHALL provide `scripts/apply_branding.py` which applies a
brand change to live content from `branding.properties`, with the
historical/evidence/license exclusion list built in, and SHALL print the
follow-up steps (regenerate the Xcode project, run the suites).

#### Scenario: scripted rebrand

- **WHEN** the script runs with a new brand
- **THEN** live user-visible strings, identity placeholders and docs carry
  the new brand and exempted paths are untouched.

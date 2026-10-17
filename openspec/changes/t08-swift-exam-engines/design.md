# Design

The shared placement and level-test JSON remains the source of truth. Each exporter
validates that source using its existing rules, transforms flat items into the
Swift `Exercise` shape, emits a Swift data file, and checks that file for freshness
alongside its existing Kotlin/API outputs.

Swift Core models and pure engines mirror Android behavior: placement uses the
same band ladder and deterministic category-balanced item selection; weekly tests
use SHA-256 ordering over `version|SECTION|id`, ISO week IDs, disjoint reading and
listening passage selection, section thresholds, and the same directional result.
The shared golden JSON is consumed by iOS, Android, and API tests to prevent their
weekly composition expectations drifting independently.

Placement UI preferences are a separate Codable value persisted by a small
UserDefaults-backed Core store, not fields on the shared learning archive. This
mirrors Android's local result/instruction stores without changing the
cross-platform archive contract; T09 connects it to the iOS UI.

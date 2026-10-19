# iOS authenticated exam-recovery UI fixture

## Objective

Add deterministic iOS UI-test coverage for the already specified T12/T13 account recovery behavior without real credentials, network access, or production UI changes. The existing preview path is insufficient: it deliberately does not load an account session and returns early from restoration.

## Requirements and boundaries

- Exercise the real Learn history, restore, account-switch, sync-state, and conflict-resolution UI.
- Simulate an authenticated account with synthetic identity and deterministic server responses; never use a real account or bearer token.
- Prove a restored attempt retains its frozen question content and original result for the same account.
- Prove switching from account A to account B neither displays A-owned history nor sends A's pending events with B's identity.
- Prove divergent state is visible as a conflict and only an explicit learner choice resolves it; the fixture must not implement automatic merging.
- Keep the seam DEBUG/simulator-only and opt-in by explicit UI-test launch arguments. Normal preview, release builds, and production transport behavior remain unchanged.
- Write the UI tests before fixture implementation. Swift execution remains deferred to the final verification batch after T01–T14, as required by the user.

## Approaches considered

1. **Inject a deterministic transport at the account-client boundary (recommended).** Keep production URLSession transport as the default; an explicit simulator-only launch fixture supplies a synthetic session and a fake transport that returns bounded, scenario-specific list/detail/ACK/conflict responses. This exercises the actual account store and UI while avoiding sockets and live credentials. Cost: a narrow transport seam and fixture lifecycle must be isolated carefully.
2. **Use URLProtocol interception.** Register a simulator-only URLProtocol for account requests. This preserves request construction but couples fixture behavior to URLSession configuration/global protocol registration and can make test isolation/order harder.
3. **Seed only local DAO state.** This is simplest but cannot prove paginated restore, authenticated ownership, or that pending events are not transmitted under another account; it is insufficient for T12/T13 acceptance.

## Recommended design

Use a private injectable request transport owned by `ManagedAccountClient`; production construction continues to select the existing bounded URLSession implementation. In DEBUG simulator builds only, an explicit UI-test launch argument selects a fixture implementation and a named scenario. `ManagedAccountStore` receives a synthetic, scope-valid session for the selected fixture account and does not consult Keychain in that mode. The fixture rejects unknown routes, unexpected account IDs, and unexpected request ordering rather than returning permissive defaults. Its state is per app launch and reset between UI tests.

The UI tests cover three observable journeys: (1) clean local store restores a completed account-owned attempt and reopening history shows the exact frozen content/result; (2) an account-A pending row followed by switching to account B remains isolated and produces no A-authenticated request; (3) a local edit that receives a server revision conflict displays both branches and resolves only after tapping the existing explicit fork/takeover action. Assertions inspect visible history/status/result and fixture-observed request identity, not implementation-only flags.

No new public API, server endpoint, user-facing control, or persisted format is introduced. Existing account and exam contracts remain authoritative. The fixture is not enabled by `--preview` alone and must fail closed outside DEBUG simulator builds.

## Verification plan

- Author one UI regression per journey before implementing the fixture seam.
- Do not run Swift tests, parsers, typechecks, builds, or Xcode during T01–T14 implementation. Run the whole applicable iOS Core/App/UI verification batch only in final verification on supported macOS/Xcode; report unavailable gates honestly on Linux.
- Continue Docker-only API and non-Swift checks where applicable; a skipped PostgreSQL case is not passing evidence.
- Perform a source-level scope/readback check now; final functional proof is deferred, not waived.

## Risks and mitigations

- **Fixture leaks into production behavior:** compile the seam under `DEBUG && targetEnvironment(simulator)`, require an explicit dedicated argument, and keep release initialization on the existing transport path.
- **Fake transport fails to reflect actual request identity:** capture authorization identity and request route in fixture-owned in-memory observations and assert those from scenario behavior; never log actual token values.
- **Overly permissive fake hides missing calls:** define exact accepted routes and response sequence per scenario; unexpected calls fail the UI flow.
- **Unable to execute UI tests on Linux:** keep tests authored, mark them pending, and do not close T12/T13 until the final supported environment runs them.

## Self-review checklist

- [x] No real credentials, account, network, or provider calls.
- [x] Scope is test infrastructure only; no public API or product UI changes.
- [x] T12/T13 acceptance remains behavioral and uses visible outcomes.
- [x] Preview-only behavior remains unchanged.
- [x] Swift execution is deferred, not represented as passed.
- [x] User authorized autonomous continuation after this written design on 2026-10-17; the plan and implementation may proceed without routine approval.

## Approval and implementation plan

The user authorized autonomous continuation of the remaining phases on 2026-10-17. The implementation plan is `docs/superpowers/plans/2026-10-17-ios-account-recovery-ui-fixture.md`; execution is inline. No commits or Swift execution are authorized during implementation.

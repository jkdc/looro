# Design: Admin Panel Hardening

Follow `docs/superpowers/specs/2026-10-24-admin-panel-hardening-design.md` for goals, considered approaches, constraints, and phase ordering.

## Runtime boundaries

- Server owns persisted truth, authorization, input validation, audit, query allowlists, and generation job state.
- Each mounted UI view owns its read requests, timers, listeners, and third-party widget instances; disposal invalidates future UI writes and releases each resource once.
- Mutations are not treated like cancellable reads. A client abort or navigation does not prove a mutation was not accepted.
- Static ES modules and existing local vendor assets remain under the current CSP and server routes.

## Compatibility

- Existing hashes and browser entrypoints remain valid.
- API additions for generation identity are additive. `POST .../generate` continues to return the existing accepted response; it must not claim a persisted run ID before the run row exists.
- Any status recovery must distinguish unknown/not-yet-registered from terminal failure and must not infer failure from a missing exam.
- No database migration is planned unless code inspection disproves that the existing UUID/attempt identity is sufficient.

## Failure handling

- Show loading, stale, error, retry, and confirmed-mutation states distinctly.
- Never convert failed reads into empty data, or failed/uncertain writes into a success or automatic duplicate action.
- Campaign apply must preserve the exact prepared campaign identity after uncertain transport outcomes.
- Polling failures remain retryable and visible; polling is not an alert that exam generation is absent.

## Accessibility and layout

- Keep the existing four navigation groups and add a usable mobile header/menu/sign-out.
- Dialogs must contain focus, expose accessible names/description, support Escape cancellation, and return focus to their trigger.
- Inputs have programmatic labels and errors; actions are discoverable by keyboard and touch.
- Status and chart meaning cannot rely on color or graphics alone.
- Validate narrow and desktop layouts in the existing Chromium harness; manual assistive-tech validation remains explicitly reported.

## Verification

Use the repository's documented isolated browser runner, relevant API/PostgreSQL integration suites, TypeScript check when server code changes, JavaScript syntax/module checks, and full `make test-api` when API behavior/contracts change. Capture exact commands and outcomes in `verify-report.md`.

## Development workflow

Use Superpowers SDD/TDD, never Gentle AI. Keep commits on the current feature branch; do not use worktrees or ask the user to choose PR-chain strategy. Push, PR creation, and merge require a separate explicit request.

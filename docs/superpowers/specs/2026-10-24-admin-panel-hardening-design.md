# Admin Panel Hardening — Design

## Intent

Implement the findings and phased recommendations in `informe-panel-administracion-2026-10-23.md` on a new feature branch. The panel is an operational tool; correctness of operator intent, reliable feedback, and safe asynchronous behavior take precedence over visual novelty.

## Goals

- Fix the confirmed campaign, browse, request lifecycle, resource disposal, validation, and feedback defects.
- Make the shell and high-value workflows usable with keyboard, touch, assistive technology semantics, and narrow viewports.
- Correlate generation status to the persisted execution without changing the meaning of `202`, introducing a new queue, or inferring failures from missing exams.
- Extract only responsibilities whose ownership and tests are established.
- Preserve authorization, audit reasons, server validation, transaction semantics, static asset serving, and current navigation contracts.

## Non-goals

- Replacing the static admin UI with a framework or bundler.
- Adding RBAC, global search, new contextual analytics, new alerting, or additional generation automation.
- Changing business rules, data retention, or financial behavior beyond preventing stale/duplicate campaign application.
- Claiming complete WCAG/browser support without the corresponding manual verification.

## Approaches

1. **Rewrite the admin UI as a framework application.** Rejected: adds a build/runtime migration and broad regression surface unrelated to the reported defects.
2. **Keep one static script and patch each symptom in place.** Rejected: leaves request, view, and resource ownership implicit and makes the proven stale-render defects likely to recur.
3. **Correct behavior first, define a small view lifecycle, then extract stabilized responsibilities into local ES modules.** Selected: aligns with the audit, preserves static delivery/CSP and vendors, permits independently tested units, and makes organization a consequence of real boundaries rather than an objective by itself.

## Design decisions

- The API remains authoritative for query allowlists, validation, authorization, auditing, persistence, and terminal generation state.
- Prepared campaign data is an immutable snapshot. Any meaningful form edit invalidates it; uncertain apply outcomes retain the original identity and are never retried by silently preparing a second campaign.
- View contexts own reads, timers, event listeners, Tabulator/Chart instances, and disposal. Aborting a client request never implies that a server mutation was undone.
- Generation identity uses the existing persisted UUID and attempt ordinal. The existing detached `202` contract remains truthful; status lookup is additive and does not rely solely on the bounded recent-jobs list.
- KPI scope is made explicit as global; no API expansion for contextual metrics is assumed.
- Existing navigation groups and static vendor assets remain. Mobile navigation, accessible dialogs, labels, action controls, persistent feedback, and text alternatives are added incrementally.
- No status/alert is synthesized from absence of exam output. The generation worker remains optional.
- Feature extraction follows tests and lifecycle ownership. No empty module tree, third-party dependency, migration, or broad route refactor is introduced without evidence.

## Phases

1. Baseline and maintained reproductions.
2. Campaign snapshot integrity, browse/API compatibility, client validation, failure handling, and explicit global KPI scope.
3. View context, stale response prevention, resource cleanup, and reliable loading/error/retry states.
4. Responsive shell and functional accessibility across dialogs, controls, rows, feedback, and charts.
5. Durable generation-run identity and recovery, preserving server concurrency and response semantics.
6. Incremental module extraction, operational hierarchy/readability, and documentation/test alignment.
7. Full targeted verification, independent high-risk verification, and a final evidence report.

## Verification constraints

- Use the existing browser E2E harness and isolated PostgreSQL stack as documented; do not use live accounts, production data, credentials, or real providers.
- Apply RED → GREEN → REFACTOR for deterministic behavior changes.
- Test API contract changes in integration tests and keep them additive.
- Treat campaign and generation correlation as high-risk: exercise duplicate/uncertain operations and concurrency against isolated state, and obtain an independent final review.
- Preserve current untracked user files and do not use worktrees, push, or merge.
- Superpowers is the required engineering workflow; no Gentle AI workflow or PR-chain clarification is used. Work-unit commits stay on the active feature branch.

## Source of truth

Detailed finding evidence and acceptance examples: `informe-panel-administracion-2026-10-23.md`. This design deliberately excludes the report's conditional product proposals in section 9 unless later explicitly authorized.

# Design: T11 admin operations

## Intent
Operators must see what generation actually uses and produces. Secrets never leave the API; connection tests use persisted settings; drafts remain private until audited approval; published catalog and student attempts stay separate. Scheduler configuration is distinct from runtime activity.

## Architecture
Extend services/api/admin-ui/index.html, app.js, and app.css without a framework. Reuse GET/PUT /v1/admin/llm-config and existing adminOnly, same-origin cookie, SameSite=Strict, and Origin protections. Add bounded admin-only read models for draft listing and generation status, plus audited rejection only if no equivalent operation exists. Draft views return generation content, judge verdict, safe metadata, and moderation state, never student attempts. Approval and rejection call the existing publication/audit service.

Show provider, model, base URL, masked key hint, and publish/verify/variants from the effective server response. Save then reload that response; do not present environment defaults as persisted values. The connection test is labeled for saved settings and is disabled or displays a save-first warning when the form is dirty. Its API operation has explicit timeout and response-byte limits and returns only safe error codes.

Generation status combines safe durable job fields with this process scheduler state and configured schedule. Label configured/enabled separately from active in this process; inactivity here does not imply other replicas are inactive. Never expose secrets, prompts, raw provider bodies, or student history.

## Security and UI state
Use existing cookies and Origin enforcement; no invented CSRF header. Make loading, empty, stale, saving, testing, moderation, and error states explicit. Prevent duplicate mutations while pending and refresh server state after success. Enforce authorization, Origin, pagination, and response limits on every route. Audit configuration mutations and moderation. Connection-test audit metadata is limited to actor, provider/model identifiers, time, and safe outcome.

## Browser verification
Use Playwright for local browser E2E because no browser runner exists and this static application has meaningful interactive state. Add only a development test dependency and run test/browser in Docker. Use isolated API/database and stub provider traffic; no external LLM calls. This adds a browser harness but verifies actual rendered save/reload, unsaved-form warning, moderation, authorization, and status wording.

## Verification
API tests cover auth/Origin, saved-config-only tests, timeout/body bounds, secret redaction, draft privacy/pagination, audited moderation, and safe scheduler status. Docker-run Playwright covers rendered UI against isolated services. Do not enable publication, call real providers, test Swift here, or alter private student history.

# Admin Panel Hardening

## Why

The administration panel has confirmed issues in campaign intent, browse/API compatibility, asynchronous rendering and resource ownership, accessibility, validation feedback, and generation-run tracking. These defects make operator actions harder to trust even though the backend remains the authority for validation and authorization.

## What Changes

- Correct campaign snapshot invalidation, query controls, form validation, and error states.
- Introduce explicit view ownership/disposal and prevent stale responses from painting over a newer route.
- Improve responsive navigation, accessible dialogs and controls, and persistent status/error feedback.
- Add additive lookup/correlation for persisted generation runs while preserving the current detached-launch response.
- Extract stabilized static-UI responsibilities into tested ES modules and improve operational hierarchy without a framework rewrite.
- Synchronize browser tests and operator documentation with observable behavior.

## Non-goals

- No framework, bundler, CDN, or vendor replacement.
- No new RBAC, global search, contextual analytics, or product features from the report's conditional suggestions.
- No generation-missing alert, and no assumption that an absent exam indicates a failed run.
- No change to server authorization, audit, publication, financial, or generation policies.

## Impact

- Admin static UI and browser E2E tests.
- Admin generation status API/types and existing generation persistence only where the confirmed identity lookup requires it.
- Admin operator documentation and OpenSpec verification evidence.

## Reference

Full findings, risks, and acceptance criteria: `informe-panel-administracion-2026-10-23.md`.

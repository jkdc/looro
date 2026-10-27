# Proposal: Complete the mobile audit implementation follow-up

## Why

The implementation review `informe-revision-implementacion-app-movil-2026-10-27.md` identified remaining defects and unverified risks in account isolation, progress transport, Android voice and iOS flows. The user requested implementation of the report's changes.

## Scope

Address R01–R19 using the existing `mobile-audit-remediation` architecture and tests. Preserve account ownership and local-first synchronization: each guest or member has its own local data; no guest-to-member merge; learner event times originate on device; server-to-device bootstrap is limited to a member on a device without that member's existing data. Do not guess provenance for legacy data.

This change does not authorize deployment, provider calls, destructive data reset, pushing, merging, or treating unavailable platform/device tests as passed. Existing uncommitted T10 work and user artifacts must be preserved.

# Spec delta: admin-exam-management

## ADDED Requirements

### Requirement: Retiring an approved week

The system SHALL allow an administrator to retire an approved generated week
with a motive between 3 and 500 characters. Retirement SHALL set
`status = 'retired'`, store `retired_at`, `retired_by` and `retired_reason` on
the week, and record one `level_test.retire` audit entry with the same motive.
The week's content, judge verdicts and approval provenance SHALL remain
unchanged, and the public generated-test endpoint SHALL stop serving it.

#### Scenario: A manual retirement stores its motive
- **WHEN** an administrator retires an approved week with a motive
- **THEN** the week is `retired`, the motive is readable from the week detail, and one audit entry records the same motive.

#### Scenario: Replay does not duplicate the audit
- **WHEN** an administrator retires an already retired week
- **THEN** the response reports `alreadyRetired` and no second audit entry is written.

#### Scenario: Only approved weeks can be retired
- **WHEN** the week is a draft, rejected, or unknown
- **THEN** the request fails with `level_test_not_retirable` (409) or `level_test_not_found` (404), and no audit entry is written.

#### Scenario: A short motive is rejected
- **WHEN** the motive is shorter than three characters
- **THEN** the request fails with `admin_reason_required` and nothing changes.

### Requirement: Explicit replacement after retirement

A retired week SHALL NOT be regenerated automatically by the scheduler or the
missing-key launcher. Replacement SHALL require an explicit operator action.

#### Scenario: The scheduler skips a retired week
- **WHEN** the scheduler or the launcher reaches a key whose current week is retired
- **THEN** it does not generate a replacement.

#### Scenario: Regeneration replaces a retired week
- **WHEN** an operator regenerates a retired week of the current week
- **THEN** the existing force path retires the old row (already retired) and persists a fresh draft.

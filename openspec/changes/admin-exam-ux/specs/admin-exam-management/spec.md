# Admin exam management — UX split and review page (delta)

## ADDED Requirements

### Requirement: Separate configuration from generation

The panel SHALL expose the LLM provider configuration on its own view
(`#ia-config`) and the exam generation surface (scheduler, launcher, jobs and
weeks listing) on a different view (`#exam-weeks`), both reachable from a
sidebar group named `Exámenes` that also contains `#exam-results`. The legacy
route `#level-tests` SHALL redirect to `#exam-weeks`.

#### Scenario: Provider form is not on the generation view
- **WHEN** an operator opens `#exam-weeks`
- **THEN** no provider, model or API-key field is present, and the launch form, jobs monitor and weeks table are.

#### Scenario: Configuration view is self-contained
- **WHEN** an operator opens `#ia-config`
- **THEN** the provider catalog, model, base URL, API key, publish/verify modes, variants and the connection test are available there, and the dirty-state notice links to the pending exams in `#exam-weeks`.

#### Scenario: Legacy bookmarks still work
- **WHEN** an operator opens `#level-tests`
- **THEN** the panel shows `#exam-weeks` without an extra history entry.

### Requirement: Readable exam review page

The panel SHALL render a generated exam at `#exam/<uuid>` as a full page, not as
raw JSON: a header with language, level, week, status, model, judge mode, tokens
and dates; a banner counting items the judge flagged; tabs for each variant plus
the full pool; sections in the order the student takes them; and one card per
item with its prompt, options (the accepted one marked), accepted answers,
explanation, source unit and judge verdict with its reason. Reading and listening
passages SHALL be shown once per passage, followed by their questions. For
`draft` exams the page SHALL offer approve and reject with the mandatory reason.
An unknown id SHALL render a not-found state.

#### Scenario: Operator reviews an exam without reading JSON
- **WHEN** the operator opens a generated week
- **THEN** items appear grouped by section, the accepted option is visually marked, and flagged items show the judge's reason.

#### Scenario: Variants switch without refetching
- **WHEN** the operator selects another variant tab
- **THEN** that variant's items and passages render immediately, in the same section order.

#### Scenario: Approve from the review page
- **WHEN** the operator approves a draft from the review page with a valid reason
- **THEN** the exam is published, the page reflects the new status and the weeks listing shows the badge.

#### Scenario: Missing content degrades safely
- **WHEN** an item has no options or answers, or references a passage absent from the variant
- **THEN** the page renders the item without errors and without an empty passage block.

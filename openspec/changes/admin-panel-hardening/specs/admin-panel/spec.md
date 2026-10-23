# Admin Panel Hardening Specification

## Requirements

### Requirement: An applied campaign matches the current prepared intent

The panel MUST invalidate a prepared campaign when a field affecting audience, account, grant amount, cap, or reason changes. Applying MUST use the immutable prepared campaign identity and confirmation, show the full relevant impact before application, and prevent accidental duplicate grants after an uncertain response.

#### Scenario: Edit after prepare
- GIVEN a campaign has been prepared
- WHEN an intent-affecting field changes
- THEN the existing preparation cannot be applied and the operator must prepare again.

#### Scenario: Apply response is uncertain
- GIVEN an apply request may have reached the server but its response is lost
- THEN the UI MUST retain the same campaign identity and MUST NOT silently create or apply a second campaign.

### Requirement: Browse controls agree with the server query contract

Visible filter operators and sort controls MUST derive from the server catalog's allowed operators, orderable columns, and default order. The UI MUST NOT present a control that produces a rejected query or locally re-sort only one remote page with different semantics.

#### Scenario: Non-orderable column
- GIVEN a catalog column is not in the server orderable allowlist
- THEN the UI MUST NOT expose sorting for it.

#### Scenario: Restricted operator
- GIVEN a column only permits `eq` or `in`
- THEN the UI MUST NOT send `contains` for that column.

### Requirement: A view owns and disposes asynchronous resources

Every mounted view MUST own its asynchronous reads, listeners, timers, and widget instances. A response from a disposed or superseded view MUST NOT update the DOM, announce a stale result, or replace the current route. Each resource MUST be disposed at most once.

#### Scenario: Navigate during a pending read
- WHEN an earlier route's request resolves after a newer route is mounted
- THEN the newer route's hash, title, content, and event handlers remain unchanged.

#### Scenario: Leave a table view
- WHEN a browse view is disposed
- THEN each Tabulator instance and listener created by it is disposed exactly once.

### Requirement: Failures and uncertain outcomes are explicit and recoverable

Loads MUST expose loading/error/retry states and distinguish stale data from empty results. A confirmed mutation followed by a failed refresh MUST remain reported as confirmed, with a separate refresh failure. Failed reads MUST not become an empty or healthy state.

#### Scenario: Bearer login network failure
- WHEN the request fails at the network layer
- THEN the login page shows an accessible persistent error, does not expose the token, and re-enables the control.

#### Scenario: Refresh after successful mutation fails
- THEN the UI communicates that the mutation succeeded but the refreshed view is stale, and offers a read retry without resubmitting the mutation.

### Requirement: Forms prevent invalid client submissions

The panel MUST execute browser-side validation before sending configured forms, associate errors with their inputs, and preserve entered data after a recoverable failure. Server validation remains authoritative.

#### Scenario: Numeric value is outside the input constraints
- THEN no request is sent and the invalid field is identified to the operator.

### Requirement: Scope and status are communicated accurately

Global KPI cards MUST state that their scope is global when displayed beside a filtered result list. Status, error, loading, and chart information MUST have readable textual equivalents and accessible announcement behavior. The UI MUST NOT infer generation failure or health from absent exam output.

### Requirement: The operator shell supports responsive and accessible operation

The panel MUST provide navigation and sign-out on narrow viewports. Dialogs MUST contain keyboard focus, have an accessible name and modal semantics, support Escape cancellation, and return focus to the invoking control. Inputs and icon-only controls MUST have accessible names; primary row actions MUST be discoverable without a context-menu gesture.

### Requirement: Generation status identifies the persisted run

The panel MUST correlate a generation attempt to its persisted UUID/ordinal, not solely to a language/level/week tuple or bounded recent-job list. Lookup/recovery MUST distinguish not-yet-registered/unknown from terminal states. The launch endpoint's existing `202` semantics MUST remain intact, and the UI MUST NOT claim an execution ID before persistence creates it.

#### Scenario: Older failed attempt exists
- WHEN a new attempt starts for the same key
- THEN the old terminal failure cannot complete the new watch.

#### Scenario: Recent list is displaced
- WHEN more than the current recent-list bound of unrelated jobs appears
- THEN the active run remains queryable by its exact persisted identity.

### Requirement: Organization changes preserve behavior

The UI MAY be split into ES modules only after lifecycle ownership and test coverage exist. Extraction MUST preserve existing routes, hashes, API behavior, CSP, and local vendors. The work MUST NOT introduce a framework or refactor stable backend routes without a demonstrated need.

### Requirement: Documentation matches the implemented interface

Browser test comments, operator help, and admin documentation MUST match actual enablement and mutation behavior. Verification reports MUST state unrun checks and manual accessibility limits accurately.

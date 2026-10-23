# API organization and behavior-preserving refactor Specification

## Requirements

### Requirement: Preserve externally observable API contracts

Route organization MUST preserve existing methods, path templates, statuses, JSON shapes, headers, cache behavior, auth/admission/CSRF policy, route/body limits, and feature-disabled responses. No route or public schema changes are authorized by this refactor.

#### Scenario: Existing admin attempt routes remain equivalent

- **WHEN** an authenticated administrator lists, filters, summarizes, exports, or opens an attempt detail
- **THEN** the response, status, headers, ownership filtering, and limits remain unchanged
- **AND** unauthenticated and wrong-origin requests remain rejected as before

### Requirement: Preserve shared HTTP policy and webhook handling

Shared admission, rate-limit, cache/security headers, request logging/tracing, parser behavior, and error mapping MUST retain their current root scope and ordering unless a separately tested task justifies a change. Signed webhook bodies MUST remain byte-for-byte raw at signature verification.

#### Scenario: Admin route modules are registered on the existing Fastify instance

- **WHEN** a route group is extracted
- **THEN** it inherits the same root hooks and error handler
- **AND** path policy continues to use the same route templates

#### Scenario: Provider webhook signature is verified

- **WHEN** a webhook request is parsed
- **THEN** signature verification receives the original raw bytes

### Requirement: Preserve business transactions and effects

Extracted operations MUST retain transaction ownership, the same PostgreSQL client through a transaction, locks, audit writes, security counters, idempotency, result/error mapping, and asynchronous job semantics. A denied request may persist admission counters; tests MUST distinguish security/accounting writes from forbidden business effects.

#### Scenario: A rejected request preserves existing security accounting

- **WHEN** admission or authorization rejects a request
- **THEN** no unauthorized provider or business operation occurs
- **AND** existing auth-rate-limit or audit records are preserved where applicable

#### Scenario: Generation is launched by the admin endpoint

- **WHEN** a valid generation request is accepted
- **THEN** the API retains its existing 202 response and detached job launch behavior

### Requirement: Verify the compiled server over HTTP

The isolated API test stack MUST make real HTTP requests to the compiled, hardened API process, not only `app.inject()`. The smoke MUST wait within a finite bound for readiness, verify a public route and verify that an unauthenticated request to an enabled admin route is rejected. It MUST use only disposable PostgreSQL and synthetic test-only admission values.

#### Scenario: API process and database become ready

- **WHEN** the smoke stack starts its compiled API and PostgreSQL
- **THEN** the test waits for readiness with a bounded deadline and verifies a representative public response

#### Scenario: Admin route is reached without an admin session

- **WHEN** a request supplies the smoke-only trusted-proxy headers but no admin session
- **THEN** the server rejects it with the existing unauthenticated response

### Requirement: Keep environment dependencies explicit at the composition boundary

Handlers MUST NOT read ambient process environment directly. Static provider/scheduler settings MUST be passed from the process entry/composition root; dynamic database-backed configuration MUST remain dynamically read and retain existing precedence/default behavior. No monolithic configuration container is introduced.

#### Scenario: Two app instances use different injected process settings

- **WHEN** two API factories receive independent environment objects
- **THEN** each operation uses its own values without mutating global `process.env`

### Requirement: Preserve runtime lifecycle ordering and resource closure

The process entry MUST be import-safe for tests. Startup validation, database/telemetry initialization, listening, background worker start, signal shutdown, and partial-startup cleanup MUST preserve their current order and existing semantics.

#### Scenario: Startup succeeds

- **WHEN** configured dependencies initialize successfully
- **THEN** the API listens before the existing post-listen workers are started

#### Scenario: Startup partially fails

- **WHEN** initialization fails after a resource has opened
- **THEN** every opened resource is closed and the process reports startup failure

#### Scenario: Shutdown is requested more than once

- **WHEN** a signal or close request arrives while shutdown is in progress
- **THEN** cleanup is idempotent and does not leak intervals, jobs, server, providers, telemetry, or database resources

### Requirement: Preserve build and operational entry points

The change MUST retain `dist/src/main.js`, CLI invocation names, migration/operation/fixture resolution, and existing migration history. No applied migration is edited.

#### Scenario: Compiled process starts independently of caller cwd

- **WHEN** the compiled API or migration/CLI runs from its documented container working directory
- **THEN** it resolves the same packaged runtime resources as before

### Requirement: Align API documentation to observed contracts

The API README MUST accurately distinguish standard error-handler JSON correlation from direct provider-test error responses, document the supported build/test path, and link to canonical operational documents. Historical reports remain historical and MUST NOT be rewritten as current behavior.

#### Scenario: Reader follows the API entry documentation

- **WHEN** a developer follows documented API build/test instructions
- **THEN** the commands and links are valid and describe the current executable verification path

### Requirement: Keep test cleanup isolated and explainable

Any cleanup changes MUST be scoped exclusively to the named disposable smoke Compose project and preserve the test command's exit status. The workflow MUST make clear when failed state is retained for diagnosis versus automatically removed. User/development stacks and volumes are outside scope.

#### Scenario: Smoke suite fails

- **WHEN** the isolated smoke test fails
- **THEN** the chosen retain/cleanup behavior is explicit and cannot target other Compose projects or volumes

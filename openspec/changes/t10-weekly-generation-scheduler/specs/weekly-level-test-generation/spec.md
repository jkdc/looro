# Weekly level-test generation scheduling

## Requirements

### Requirement: Scheduler defaults off and validates its schedule
The API MUST keep automated generation disabled unless LEVELTEST_GENERATE_ENABLED is exactly true. It MUST validate UTC hour, ISO weekday, supported languages and levels, and reject duplicate or out-of-bound lists. Defaults MUST be disabled, 04:00 UTC Monday, languages en,es, levels A1,A2,B1.

#### Scenario: Disabled scheduler
- **WHEN** the API starts without an explicit enabling value
- **THEN** no scheduler timer or generation job is started.

#### Scenario: Invalid schedule
- **WHEN** hour, ISO weekday, language/level, duplicate, or list bound is invalid
- **THEN** startup validation fails before scheduling any provider call.

### Requirement: One-shot UTC schedule and current-week recovery
The scheduler MUST calculate the next configured ISO weekday/hour in UTC, arm one one-shot timer, and recalculate after the run using the current time. On startup after the scheduled instant it MUST attempt recovery for only the current ISO week and MUST NOT backfill historical weeks indiscriminately.

#### Scenario: Timer recalculation
- **WHEN** a scheduled run finishes
- **THEN** the next target is computed from the current UTC clock, not the original interval.

#### Scenario: Missed current-week run
- **WHEN** the scheduler starts after this week's configured instant
- **THEN** it consults durable state and attempts only this week's missing generation jobs.

### Requirement: Generation jobs are excluded before provider calls
CLI and scheduler jobs MUST share a PostgreSQL lock keyed by language, level, and week and durable job state. The lock and claim MUST be acquired before LLM calls. A persisted approved week or draft MUST be preserved by default. Per-combination failures MUST not stop other combinations.

#### Scenario: Competing processes
- **WHEN** scheduler replicas or CLI compete for one language/level/week
- **THEN** at most one may call the provider, while other combinations remain independently runnable.

#### Scenario: Unknown provider outcome
- **WHEN** a transport failure may have occurred after the provider received a request
- **THEN** the job is marked unknown and is not retried automatically after restart or another tick.

### Requirement: Scheduler shutdown cancels and drains work
The scheduler MUST clear its timer, avoid starting new jobs, abort active provider requests, and await active work before the database pool closes.

#### Scenario: Shutdown during generation
- **WHEN** shutdown is requested during a job
- **THEN** its request is aborted, no later combination begins, and stop resolves before database close.

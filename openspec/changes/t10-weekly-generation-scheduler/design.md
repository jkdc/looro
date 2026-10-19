# Design: T10 weekly generation scheduler

## Architecture
LevelTestScheduler is created inside API startup and starts only when LEVELTEST_GENERATE_ENABLED=true. It calculates the next configured ISO weekday/hour in UTC and uses a one-shot timeout, recalculating from the current clock after each run. On startup after this week's scheduled instant it attempts only the current ISO week; the durable job ledger decides whether work is still eligible. Shutdown clears timers, aborts the active generation signal, awaits the active run, then allows the API to close PostgreSQL.

The CLI and scheduler both call one shared runLevelTestGenerationJob orchestration function. It obtains a PostgreSQL session advisory lock keyed by language, level, and ISO week before any LLM call, then claims a durable job row. The same process cannot overlap its own jobs; PostgreSQL lock and unique job key coordinate replicas and CLI. running found after lock acquisition means its prior owner disappeared and is conservatively converted to unknown, never reissued automatically.

## Durable job lifecycle
A row is uniquely keyed by language_id, level, week_index and stores bounded status, attempt count, safe error code, and timestamps. Successful approved/draft/existing results become terminal for that week; known failures may be retried only within a small fixed attempt bound. A transport outcome that may have reached the provider becomes unknown and is not retried without explicit CLI --force. Existing approved or draft week rows are never replaced unless --force is explicit. No prompt, response body, credential, or raw provider error is stored.

## Configuration and timing
Strictly parse booleans, UTC hour 0..23, ISO weekday 1..7, unique bounded languages and levels. Defaults are disabled, 04:00 UTC Monday, en,es, and A1,A2,B1. Invalid settings fail startup rather than silently broadening generation scope. Compose passes the complete LEVELTEST_* configuration, with secret values interpolated only from environment.

## Failure and cancellation
Each language/level job is isolated: a failure records a safe code and does not prevent later combinations. Each provider request receives an abort signal and a bounded total job timeout. Any transport failure is classified as an unknown external outcome and halts the whole generation attempt. Scheduler shutdown aborts active work and waits for it to settle before db.end().

## Verification
Use fake-clock unit tests for UTC calculation, early/late timers, startup recovery, input validation, failure isolation, and shutdown. Use PostgreSQL integration tests with independent connections for lock contention, durable claims, and unknown-outcome recovery. Run all project commands through Docker; no real provider calls. The effective delivery switch remains disabled in Compose.

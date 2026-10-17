# Tasks

- [x] Implement strict scheduler configuration and UTC one-shot timer with fake-clock tests.
- [x] Add durable generation-job migration and shared advisory-lock/claim orchestration for CLI and scheduler.
- [x] Prevent automatic retries after unknown provider outcomes; thread timeout/shutdown abort signals through all provider calls.
- [x] Wire scheduler lifecycle into API startup/shutdown and complete disabled-by-default Compose configuration.
- [x] Verify fake-clock, database concurrency/recovery, API build and focused suites in Docker; record limitations.

- Independent bounded verification of unknown body/judge outcomes: PASS on correction commit b84be55.
- Commits: 1aa322f (scheduler), b84be55 (unknown outcome correction).

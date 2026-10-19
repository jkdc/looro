# T08: Swift Placement and Weekly Exam Engines

## Why

The iOS Core has no placement or weekly level-test implementation, so it cannot
compose, grade, or retain the deterministic exam contract already used by Android
and the API. T09 needs these platform-neutral primitives before adding iOS recovery.

## Scope

- Generate Swift placement and level-test banks from the existing shared JSON sources.
- Add deterministic Swift placement and weekly composition/grading engines.
- Pin cross-platform composition to a shared B1/English/week-202641 golden fixture.
- Add local per-language placement-result and instruction-language preferences.

## Out of scope

- iOS UI, durable attempt storage, cloud sync, or account recovery (T09).
- Changes to canonical question content, Android grading, API composition, or production publication.
- Whisper-dependent lint/APK checks, deferred until all development phases are complete.

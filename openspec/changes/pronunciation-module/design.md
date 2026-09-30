# Design: pronunciation-module

## Feature flag

`Preferences.pronunciationEnabled: Boolean? = null` — `null` and `false` mean
OFF. Only `true` activates. This triple-state allows:
- `null` → never been set, use default (OFF)
- `false` → user explicitly disabled
- `true` → user explicitly enabled

The flag gates three things:
1. Whether the pron drill renders in LessonDetail
2. Whether pron skills appear in MasteryStats (via index filtering)
3. Whether the pronunciation settings row is visible in Settings

No compile-time flag, no server config — a simple local preference. The
module is self-contained; disabling it removes zero evidence (scores stay
in the ledger but mastery excludes them when the flag is off).

## Speech recognition

Both platforms already have on-device STT (Talk uses it for conversation).
The pronunciation scorer reuses the same engine:

- Android: `android.speech.SpeechRecognizer` with `RecognizerIntent`
- iOS: `SFSpeechRecognizer` with `SFSpeechURLRecognitionRequest`

Flow:
1. User taps "Check pronunciation"
2. App starts listening (same permissions as Talk)
3. User speaks the target phrase
4. App stops listening, gets a transcript
5. `PronunciationEngine.score(target, transcript)` compares word sets

## Scoring engine (WER)

```text
score = 100 × (1 − WER)
WER   = editDistance(targetWords, transcriptWords) / targetWords.count
```

- Case-insensitive, punctuation-stripped (same normalizer as ExerciseEngine)
- Threshold: score ≥ 50 → passed (evidence recorded)
- Score < 50 → "Try again" with the model replayed
- Deterministic: same target + same transcript = same score

## Ledger

New kind `pronunciation` added to:
- `ProgressEngine.kt` / `.swift` kinds list
- Server migration 034 (CHECK constraint rebuild)
- Server `PROGRESS_KINDS` in `progress.ts`

Evidence shape: `CompletionMark(kind=pronunciation, itemID=<lessonID>,
bestScore=score, languageID)`. MasteryEngine maps it to `CHECKPOINT_WEIGHT
× 0.8 = 0.8` for pron-tagged skills only.

## MasteryEngine integration

The index already resolves `activityID → skillIDs` from lesson skills.
Pron skills (`pron:th`, `pron:past-ed`, etc.) appear in lesson skills when
tagged. The pronunciation completion mark attributes to the lesson's `pron:`
skills. MasteryEngine already checks `ProgressEngine.PRONUNCIATION` marks
against `activityIDs` — same pattern as listening and reading.

When the flag is OFF, the SkillCatalogIndex filters out pron skills from
`taughtSkills`, so they contribute zero to categories and overall.

## Toggle in Settings

Settings → Preferences gains a row: "Pronunciation practice (beta)" with
a Switch. Toggling writes `preferences.pronunciationEnabled` and persists.
The change takes effect immediately (no restart needed) because the drill
visibility is checked per composition.

## File changes per platform

| Layer | Android | iOS |
|---|---|---|
| Flag | Models.kt Preferences | Models.swift Preferences |
| Engine | PronunciationEngine.kt | PronunciationEngine.swift |
| Scoring | WER inline in engine | Same |
| Ledger kind | ProgressEngine.kt | ProgressEngine.swift |
| Migration | 034 SQL | N/A |
| Server | progress.ts PROGRESS_KINDS | N/A |
| Mastery | MasteryEngine.kt | MasteryEngine.swift |
| Lesson drill | LessonDetail pron section | LessonDetail pron section |
| Settings | SettingsScreen.kt row | SettingsView row |

# Design: gentle-ai

## Cross-cutting

- **Parity contract.** Every new `TeachingPolicy` prompt must exist on both
  platforms with identical normalized wording (`scripts/check_cross_platform.py`
  compares single-literal String functions). New `Preferences`/`SessionRecord`
  fields must be **optional** in Swift (`Type?`) — synthesized `Decodable`
  would otherwise reject every pre-existing archive — and defaulted in Kotlin,
  absent from the Kotlin `fields(...)` required-key checks.
- **New settings apply to the next session.** Live sessions are not mutated
  mid-flight (OpenAI WebRTC keeps server-side state; the local pipeline rebuilds
  its prompt per turn). Both features read the preference at session start.
- **iOS is reasoning-verified only** (no Swift toolchain on the Linux machine);
  the macOS gate from `post-analysis-remediation` applies before merge.

## K10 — Tutor speech pace

- `Preferences.slowSpeech: Bool?` (`nil` = natural pace). Both `Models`.
- `TeachingPolicy.voice(...)` gains `slowSpeech: Bool` and a new prompt line
  `Speech pace: {…}` that interpolates `paceSlow()` or `paceNormal()` — two
  new single-literal prompt functions the cross-platform checker enforces:
  - `paceSlow`: "Speak noticeably more slowly than natural conversation, with short sentences and clear pauses."
  - `paceNormal`: "Speak at a natural conversational pace."
- Local Android pipeline additionally sets the system-TTS rate
  (`TextToSpeech.setSpeechRate`, slow ≈ 0.8) so the effect is guaranteed where
  speech is synthesized locally; the prompt carries the instruction for the
  realtime engines (best-effort there, documented).
- Settings: Toggle (iOS, "Slower speech") / Switch row (Android) next to the
  meaning-subtitles control. Typed sessions are unaffected.

## K9 — Listening comprehension mode

- `Preferences.listeningPractice: Bool?`; `SessionRecord.mode: String?` set to
  `"listening"` at session creation so gating and history survive preference
  flips mid-session.
- `TeachingPolicy.listeningStory(language:)` replaces the `greeting` command on
  `session.started` when the session mode is listening (both platforms,
  checker-enforced wording):
  "Run a listening comprehension exercise now, without waiting for the learner to speak. Tell one very short, simple story in {language.name}, about 90 words, suited to the learner's level. Narrate it naturally without pausing for replies and without translating. Then ask one short question about the story and wait. Ask further questions one at a time. If the learner answers in another language, gently invite a {language.name} answer. Keep all your speech in {language.name}."
- Meanings: `scheduleTranslation` adds a listening-session guard
  (`session.mode == "listening"`), so auto-subtitles never appear during the
  exercise. **Word lookup stays available** — a stuck learner can look a word
  up deliberately, which preserves learner agency without reinstating the
  subtitle crutch. The meanings toggle itself is untouched.
- Assessments and the final assessment run unchanged (the evidence pipeline is
  mode-agnostic); the voice prompt for the session already carries the pace
  preference. Sessions are marked in history by `mode`, surfaced only in stats.

## K3 — Progress and streaks

- `LearningStats` projection, `LearningEngine.stats(archive:…)` on both
  platforms (pure function over the archive; `now` and the day-boundary rule
  injected for tests):
  - totals: sessions, voice minutes (`voiceSeconds`), words by bar level 0–3;
  - streaks: consecutive local days with ≥ 1 session, counting today or
    yesterday as the anchor; longest streak over all history;
  - `last14Days`: voice minutes per local day (oldest → today) for the bars;
  - capabilities: count and most recent descriptor.
- Identical day-boundary algorithm on both platforms (local calendar day;
  tests pin UTC) — this is review-parity, not checker-enforced.
- UI: a Progress screen reached from Settings (iOS `NavigationLink` section
  row; Android screen from Settings). Read-only, recomputed on open; no
  background recomputation, no archive growth.

## K12 — Directed writing practice

- `WordPractice` core (both platforms, unit-tested): normalization = trim →
  NFC → casefold → collapse whitespace → strip surrounding punctuation;
  accepted when the normalized answer equals the normalized `form` **or**
  `lemma`. Deliberately strict on purpose (spelling practice); diacritics are
  significant (they are orthography in all eight languages).
- Drill UI on the Words tab: takes up to 10 due words (not hidden, sorted by
  `dueAt`), shows the English sense and the example quote with the target form
  masked, checks the typed answer, reveals the correct spelling on a miss, and
  summarizes at the end. Keyboard input only (voice dictation is a follow-up).
- Attempts are **not persisted** in this change: recall evidence stays
  conversation-derived (the project's stated model), and persistence would
  require archive schema v3 — parked follow-up.

## D3 — Partials, barge-in, adaptive VAD (Android local path)

- **`AdaptiveVad`** (pure Kotlin, in `core.voice`): exponential noise-floor
  tracking while a frame is classified silent; speech start requires
  `rms ≥ max(BASE, floor × 3)`, speech continues until trailing silence under
  `max(BASE × 0.5, floor × 1.8)`. Floor is floored at `BASE` so silence never
  makes the VAD hair-trigger. Replaces the raw `rms >= VAD_SPEECH_RMS` test in
  `LocalVoiceTransport`'s auto mode; manual mode keeps its button semantics.
- **Partial transcripts.** While a turn is open (auto or manual), after every
  2 s of accumulated speech (minimum 1 s buffered), the transport snapshots the
  buffer (capped at the last 8 s), runs a bounded whisper pass and emits
  `session.input_transcript.partial` with the provisional text. Rules: newer
  partial supersedes (and cancels) an older in-flight one; the mutex means at
  most one decode runs at a time, and the turn-final pass can wait at most one
  bounded decode; empty or unchanged text is not emitted. MuralViewModel maps
  the event to provisional UI state only — **partials never create fragments**
  — and clears it on the final input delta, session end, or a new partial.
  Talk UI renders it as an interim line (secondary style).
- **Barge-in.** Only when `AcousticEchoCanceler.isAvailable()` (device
  capability; `AecAvailability` tolerates the JVM's missing audiofx runtime).
  With barge-in armed, `speakSuppressed` does **not** pause the mic — the
  listen loop keeps receiving frames, discards them for turn buffering, and
  stops playback when energy ≥ `BARGE_RMS` (0.06) sustains ≥ 250 ms
  (`tts.stop()`, whose interrupted-utterance completion was fixed in
  `post-analysis-remediation` Task 3.4), so `speakSuppressed` unwinds through
  its normal depth-accounting path. Because playback-period frames never enter
  a turn buffer, a mis-trigger costs one interrupted utterance — never a
  phantom turn. Without AEC (or `bargeIn = false`) the pre-existing pause/
  drain/resume behavior is unchanged. Note: `MicRecorder` uses the
  `VOICE_RECOGNITION` source, so hardware AEC may not be active even where
  `isAvailable()` reports true; live-device verification of the barge
  thresholds stays a parked gate (as with the rest of the voice pipeline).
- All D3 constants live in `LocalVoiceTransport`/`AdaptiveVad` companions;
  none enter the cross-platform CONSTANTS table (no iOS counterpart).

## Strings

- Android: every user-facing string added to `values/` and `values-es/`
  (222-key parity is enforced by existing checks). iOS: English-only, matching
  the current iOS convention.

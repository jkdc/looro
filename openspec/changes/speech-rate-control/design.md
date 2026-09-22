# Design: speech-rate-control

## Preference

```kotlin
var speechRate: Double? = null   // Kotlin (nullable, optional, outside fields())
public var speechRate: Double?   // Swift
```

- Allowed values: 0.6 / 0.8 / 1.0 / 1.2 (validated at the settings layer; the
  archive only bounds the field as a finite positive double ≤ 2.0).
- Effective rate derivation (both cores):
  `effective = speechRate ?? (slowSpeech == true ? 0.8 : 1.0)` — legacy
  archives behave exactly as before.
- Prompt pace flag: `slow = effective < 1.0` (replaces the direct
  `slowSpeech == true` reads in AppViewModel and ConversationCoordinator).

## Application points

| Surface | Android | iOS |
|---|---|---|
| Session TTS (on-device voice) | `LocalVoiceTransport.speechRate: Float` → `tts.setSpeechRate(effective)` | n/a (provider voice) |
| Lesson card reader | `lessonReader.setSpeechRate(effective)` before `speak` | `utterance.rate = AVSpeechUtteranceDefaultSpeechRate × effective` |
| Tutor pace prompt | `TeachingPolicy.voice(…, slowSpeech = effective < 1.0)` | same via ConversationCoordinator |

## Settings UI

- Android: the "Slower speech" `SettingsSwitchRow` becomes a
  `SettingsChoiceRow` with four localized options (ids "0.6"/"0.8"/"1.0"/"1.2",
  displayed value = localized label).
- iOS: the `Toggle("Slower speech")` becomes a `Picker` with the same four
  options writing `speechRate`.

## Tests

- JVM `SpeechRateTest`: derivation matrix (null/false → 1.0, null/true → 0.8,
  0.6, 1.2), archive round-trip preserving `speechRate`, legacy fixture still
  decodes. iOS mirror under the macOS gate.
- `check_cross_platform.py` (Preferences struct pin) + shared fixture gains
  `speechRate: 0.8`.
- Gates: Android suites, checker, emulator spot-check of the lesson reader.

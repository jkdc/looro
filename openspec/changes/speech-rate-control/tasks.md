# Tasks: speech-rate-control

## Task 1 — Preference (both cores)

- [ ] 1.1 `Preferences.speechRate: Double?` (Kotlin/Swift) + effective-rate
      derivation helper; fixture and checker pin.

## Task 2 — Application (both cores)

- [ ] 2.1 Android: `LocalVoiceTransport.speechRate`, lesson card reader rate,
      prompt flag derivation.
- [ ] 2.2 iOS: `LessonReader` rate, prompt flag derivation.

## Task 3 — Settings UI + strings

- [ ] 3.1 Android: choice row replacing the "Slower speech" toggle.
- [ ] 3.2 iOS: picker replacing the toggle.
- [ ] 3.3 EN/ES strings parity.

## Task 4 — Gates + record

- [ ] 4.1 JVM tests + Android suites + `check_cross_platform.py` green;
      emulator spot-check.
- [ ] 4.2 verify-report.md with honest gates (macOS pending).

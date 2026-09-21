# Looro for Android

Native Kotlin and Jetpack Compose client for Android 8.0 or later. It offers voice and written conversation, eight learning languages, 24 themes, meanings, vocabulary and local history, using an owner-provided API key for the selected conversation engine (OpenAI, DeepSeek or GLM — see [conversation engines](docs/conversation-engines.md)). Learner-facing extras on both platforms: a slower-speech setting, a listening-practice mode, a Progress screen with streaks and a writing drill over due words. On the DeepSeek and GLM engines the voice pipeline runs whisper STT and system TTS on device, with live partial transcription, an adaptive noise-floor turn detector and voice barge-in on echo-cancelling devices. Mandarin captions link each word and show optional pinyin on Android 10 or later; Android 8 and 9 keep word links without pinyin.

Word taps in all eight languages open a contextual meaning sheet, matching the iOS flow. Mandarin uses a bundled offline phrase dictionary with an ICU fallback; unresolved common ambiguous readings retain their source characters.

Interface copy lives in `res/values` (English) and `res/values-es` (Spanish); Android picks the translation from the phone's language.

See [install and build](../../docs/run-on-android.md), [design](../../docs/android/design.md) and [verification](../../verification/android-validation.md).

```sh
./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Requirements: Java 17, Android SDK 36 and Build Tools 35.0.0. The OpenAI key is entered inside the app and encrypted with Android Keystore; DeepSeek and GLM keys are read from an untracked `api_keys.properties` file pushed to the app's external files directory. Keys must never be configured in Gradle. `local.properties`, private signing keys and build outputs are excluded from Git.

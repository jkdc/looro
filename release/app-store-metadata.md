# App Store and TestFlight metadata draft

Prepared for the current BYOK build on 12 September 2026. Replace every `[REQUIRED: …]` before copying into App Store Connect. Update the feature list against the final binary. Store metadata language is English; that is separate from the languages users can practise.

## Listing fields

| Field | Draft |
| --- | --- |
| Name | Palique |
| Subtitle | Learn through conversation |
| Primary category | Education |
| Secondary category | None proposed |
| Copyright | [REQUIRED: publisher copyright, e.g. `2026 <publisher>`] |
| Marketing URL | `https://palique.example/` — live |
| Support URL | [REQUIRED: live support URL on the publisher's domain; placeholder `https://palique.example/support/`] |
| Privacy Policy URL | `https://palique.example/privacy/` — live |
| Repository | https://github.com/jkdc/palique |
| Operator | [REQUIRED: publisher legal entity] |
| Seller | `[REQUIRED: exact enrolled Apple Developer entity]` |
| App Review contact | `[REQUIRED: contact name] · support@palique.example (placeholder) · [REQUIRED: telephone, entered privately]` |
| Age rating | `[REQUIRED: complete the current questionnaire for the shipped AI/search behavior]` |
| SKU | `[REQUIRED: owner-approved internal identifier]` |

**Promotional text**

> Start with a hello. Practise a real conversation, check the meaning when you need it, and revisit words as they become familiar.

**Keywords**

```text
language,speaking,conversation,norwegian,spanish,french,english,vocabulary,practice,immersion
```

Keywords are limited to 100 bytes; this draft uses 93 ASCII bytes. Verify every named language in the release build before submission. [Apple field reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)

## Description

Palique helps you practise a language by talking. Start with a hello or choose an everyday setting, then follow a conversation that responds to how you are getting on.

Palique speaks in the language you are learning. Turn on meaning subtitles when you need help, ask for a simpler explanation, or use a typed reply. Corrections happen within the conversation so you can try again while the context is still fresh.

Choose from 24 themes, from ordering coffee to discussing a film. For something current, ask Palique to find a topic and see the sources behind it.

Your vocabulary grows from words you use in conversation. Three recall bars reflect repeated practice over time. Each language keeps its own conversations and progress; the bars and ability observations are guidance, not a language qualification.

Practise Norwegian Bokmål, Spanish from Spain, international English, French from France, German, Italian, Brazilian Portuguese or Mandarin with Simplified Chinese and optional pinyin. Your conversations and learning records stay in the app on your device. You can export a backup and import it on another installation.

This version requires your own OpenAI API key, API billing and access to the supported models. API usage is billed by OpenAI. Palique has no account requirement or credit purchases in this version. Audio and selected text are sent to OpenAI while you practise; provider retention policies apply. Palique does not save raw audio.

Palique’s source is available under the MIT License at github.com/jkdc/palique.

## Screenshot set

Four 6.9-inch upload PNGs are prepared in [screenshots/en-US](screenshots/en-US/README.md). They use the English interface and Spanish sample learning content. The originals are retained separately; upload the opaque 1320 × 2868 files beside the screenshot README.

## TestFlight beta description

Try Palique, a voice conversation app for language practice. The beta includes Norwegian, Spanish, English, French, German, Italian, Brazilian Portuguese and Mandarin, with meaning subtitles, everyday themes, vocabulary recall bars and local learning backups. Mandarin uses Simplified Chinese with optional pinyin. This build uses your own OpenAI project key and bills API usage to that project. An internet connection is required.

## What to test

Try a short conversation and tell us where it stops feeling natural: pacing, corrections, pronunciation or how Palique responds when you are stuck. Check meanings while Palique speaks and after ending. Try a theme, mute, interruption, the 15-second reset, and a learning export/import. Report the learning language, iOS version and audio route. Please leave private conversation content and API keys out of feedback screenshots.

## App Review notes

Palique is a language-practice app. There is no communication with other app users and no public conversation feed. The orb is an AI conversation partner. It is instructed to speak in the selected learning language; optional meaning subtitles use the separately selected meaning language.

This build has no Palique user accounts or purchases. It connects directly to OpenAI with an owner-entered project credential saved in the device’s Keychain. Review access must be provisioned before submission:

**[REQUIRED: document the tested review credential delivery or fully functional managed review-access route here. Do not commit an actual credential. Do not require reviewers to purchase API access.]**

Review flow:

1. Open Settings and select Spanish from Spain, with English meanings.
2. Open **Advanced → Use your own API key** and configure the review access provided privately, then return to Talk and start a conversation.
3. Allow microphone access. Palique greets you in Spanish. Reply aloud, or use the typed reply action.
4. Toggle Meaning to show or hide subtitles. End the conversation; Meaning remains available until the screen resets after 15 seconds. **New conversation** resets immediately.
5. Open Themes to try a setting. Open Words to inspect vocabulary and past conversations. Settings contains JSON export/import and local deletion.

Conversation and vocabulary records stay in local SwiftData storage; selected context goes to OpenAI for teaching. OpenAI is the provider for live voice, translation and optional current-topic search. The request disables provider application storage where supported; abuse-monitoring retention can still apply.

No hidden debug preview is needed to access normal app features. Simulator screenshots used for marketing contain sample learning records; functional review must use the actual speech service.

# Design: themed-conversation-steering

## Prompt surfaces

### 1. Session instructions — `TeachingPolicy.voice`

Current themed line (passive, one line among many):

```
Context: ${theme?.situation ?? "Free conversation. Follow the learner’s day and interests."}
```

New themed branch (active anchor):

```
Theme: <title>. <situation> <themeSteering>
```

with `themeSteering(language)`:

> This theme anchors the conversation: keep it as the thread, explore its
> different concrete situations, and gently steer back when the chat drifts
> too far. Use that variety to deepen the learner’s command of
> <language name>.

No-theme branch unchanged in meaning: `freeConversationContext()` returns the
existing sentence verbatim.

Why "anchors … drifts too far" and not a hard constraint: the surrounding
policy ("Follow their meaning, allow interruption", "Let advanced speakers
reveal their ability quickly") is intentional pedagogy; the steering must
re-direct, never imprison, the dialogue. "Explore its different concrete
situations" encodes the product goal of breadth-within-the-theme (ordering →
chatting → paying at the café) instead of looping one scenario.

### 2. Opening instruction — `TeachingPolicy.greeting`

Gains `theme: ConversationTheme? = null`. With a theme, after "ask one short,
natural question" the instruction appends `themeOpening()`:

> that steps into the theme situation.

so turn 1 is no longer a generic question. The fixed greeting utterance
(`language.greeting`) is unchanged — only its follow-up question is themed.
This reaches both engines: OpenAI Realtime and the on-device pipeline both
receive the base instructions plus this greeting command at session start.

### 3. Mid-session switch — `TeachingPolicy.theme`

"Move naturally into this situation: …" gains an anchoring clause:

> Anchor the conversation here: explore its different concrete situations and
> gently steer back when the chat drifts too far.

## Parity mechanics

`scripts/check_cross_platform.py` compares every single-literal String
function of `TeachingPolicy` across cores with interpolations collapsed to
`{}` — text inside an interpolation (like the old `?: "Free conversation…"`
fallback) is NOT verified. Because the point of this change is the new
wording, the branch texts are hoisted into ordinary single-literal functions
(`themeSteering`, `freeConversationContext`, `themeOpening`) so the checker
pins them. The conditionals themselves stay inside interpolations
(`theme?.let { … } ?: freeConversationContext()` /
`theme.map { … } ?? freeConversationContext()`), which keeps `voice()` /
`greeting()` / `theme()` single-literal expression bodies on both platforms
and therefore still skeleton-compared.

## Alternatives considered

- Theme-aware `language.greeting` strings (a themed opening utterance per
  theme per language): rejected — 24 themes × N languages of hand-written
  copy is a content project, and proficient-speaker review is required for
  language data; the steering approach gets the themed opening from the model
  with zero new language assets.
- Injecting the theme as a first user message instead of instructions:
  rejected — it would pollute the transcript/assessment evidence (typed user
  fragments) and the archive semantics.

## Tests

- JVM `TeachingPolicyTest`: themed instructions contain the anchor block and
  steering (and not the free-conversation line); no-theme keeps the default;
  greeting carries `themeOpening()` only with a theme; mid-session `theme()`
  anchors and only mentions the situation when a theme is given.
- iOS `Tests/TeachingPolicyTests.swift` mirrors the JVM cases (macOS gate).
- `check_cross_platform.py` green (now also covers the new wording).

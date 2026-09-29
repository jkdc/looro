# Proposal: voice-turn-settings-themes

## Intent

Two voice-UX gaps surfaced in real-device testing (Pixel 9, ENGINE=glm):

1. **Beginners get cut off mid-sentence**: the fixed 1,2 s silence window of the
   VAD closes the turn while the learner is still thinking. Advanced users want
   a longer window; beginners want explicit control.
2. **Themes feel dead in local voice**: choosing a theme selects it (the Talk
   screen shows the title), but the on-device voice session never opens the
   conversation — the OpenAI Realtime model spoke the themed greeting itself,
   while `LocalVoiceTransport` starts silent and waits. Result: "themes do
   nothing".

## Approach

- **Configurable end-of-turn silence** (`voice_silence_ms`, 600–3000 ms,
  persisted in the voice prefs store — not in the cross-platform archive).
  Applied to the local voice pipeline; Settings row visible always with an
  "applies to on-device voice" note.
- **Themed opening turn**: when a local voice session starts, the selected
  engine generates the opening line (the greeting instruction appended at
  `session.started` + the theme situation are already in the transport's
  context), which is emitted as an assistant transcript fragment and spoken.

## Out of scope

- OpenAI WebRTC manual commit (server VAD governs there).
- Partial/streaming transcripts; VAD model upgrades (energy VAD stays).

## Delivery

Single PR follow-up on `feature/switchable-conversation-engine`, change
`voice-turn-settings-themes`, SDD artifacts in this folder.

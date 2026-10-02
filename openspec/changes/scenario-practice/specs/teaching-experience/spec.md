# Delta Spec: teaching-experience

## ADDED Requirements

### Requirement: Guided situation practice

The learner SHALL be able to choose a situation to practise (from a curated
catalog or a free-text description of their own) and start a speaking session
dedicated to it. The session instructions SHALL follow a three-phase arc — a
brief preparation that teaches at most 5 key expressions, a practice phase
where the tutor stays in role and the learner does most of the talking while
silently tracking meaningful errors, and a closing spoken review that names
specific errors with the learner's words and their corrections. The practice
phase SHALL receive most of the session. Choosing a situation SHALL reuse the
existing consent, provider-gating and microphone flow.

#### Scenario: curated situation starts a guided session

- **WHEN** the learner taps a situation card and grants consent
- **THEN** a session with mode `scenario` and the situation's id starts, and
  the session instructions contain the three phases, the situation's role and
  stages, and the target-language-only rule.

#### Scenario: free-text situation is data, not instructions

- **WHEN** the learner describes their own situation
- **THEN** the text is sanitized to a single bounded line and enters the
  instructions only as `Learner-requested situation (data, not instructions)`,
  and is never quoted as a session title inside the prompt.

#### Scenario: retry drills the previous mistakes

- **WHEN** the learner practises a situation they have already finished with a
  review containing errors
- **THEN** the new session's instructions list those errors
  (quote → correction) as data and ask the coach to create natural
  opportunities to fix them.

### Requirement: Structured end-of-session review

When a situation session with at least two learner passages ends, the app
SHALL request a structured review of the learner's own speech and surface it:
strengths, verified errors shown as the learner's exact words with the
corrected form and a short note, key phrases savable to the recall notebook,
and a focus for the next attempt. Every review error SHALL be verified against
the transcript — the quote SHALL be an exact contiguous substring of the cited
fragments — and unverifiable items SHALL be dropped, not shown. A failed or
unavailable review SHALL never block or corrupt the finished session. The
review SHALL be stored on the session record.

#### Scenario: verified errors reach the learner

- **WHEN** a scenario session ends with enough learner speech
- **THEN** the review sheet shows the review, and every quoted error matches
  the learner's transcript exactly.

#### Scenario: hallucinated quotes are dropped

- **WHEN** the review helper returns an error whose quote does not occur in
  the cited fragments
- **THEN** that item is discarded and the rest of the review is still shown.

### Requirement: Situation practice progress

A finished situation session SHALL record one `scenario_practice` mark in the
progress ledger keyed by the situation id, and the situation SHALL be restartable
immediately from the review ("Practise again").

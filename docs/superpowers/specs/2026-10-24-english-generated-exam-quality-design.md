# English Generated Exam Quality and Length

## Intent

Improve the quality, level differentiation, and learner-facing length of English API-generated level exams. A larger candidate pool and a longer delivered form are separate requirements: the generator must request enough valid material for both the configured form and meaningful variant diversity.

## Goals

- Make the English blueprint the authoritative input to generation, deterministic validation, judging, composition, and publication checks.
- Increase the English learner-facing closed-item form by CEFR level using the report's pilot counts as initial targets, without claiming those counts are psychometrically validated.
- Improve item and passage diversity, evidence-based judging, and bounded repair without re-emitting uncertain provider calls.
- Preserve known usage accounting, generation provenance, and published snapshots/history.
- Keep the change English-only; do not generate or revise authored exam banks or modify Chinese validation.

## Non-goals and anti-goals

- Do not create an external-exam certificate or claim that a score proves CEFR readiness.
- Do not add open writing/speaking evaluation, audio response collection, new mobile flows, or a second diagnostic product in this change; those need separate privacy, assessment, and client-contract design.
- Do not change bundled banks or generated Kotlin/Swift data by hand.
- Do not apply new English requirements to other generated languages or change their validation behavior.
- Do not silently lower requirements, duplicate prompts, re-run a request with unknown provider outcome, or rewrite historical snapshots/grades.

## User constraints

- User: “centremosla en inglés sólo, pero mi mayor interés no es generar los bancos de exámenes, es mejorar la calidad de estos y la extensión, revisa el documento antes de nada y aplica todo lo que necesites usando superpowers”
- User: “centrate en lo que pone en el informe, adelante con todo”
- The English pilot counts in the report are hypotheses, not a validated standard. The implementation must label and measure them as a product pilot, not as proof of readiness.

## Approaches

1. **Only enlarge the candidate pool.** Improves variant choice but leaves each learner with the current 25-item exam. Rejected because it does not satisfy the requested increase in exam length.
2. **Enlarge the current English generated form and separately size its candidate pool.** Preserves the existing generated-exam flow, makes the form longer by level, and allows candidate diversity to be measured independently. **Selected** because it directly meets the user's request while avoiding a second product and open-response privacy/grading work.
3. **Build a separate readiness diagnostic with open writing and speaking.** Pedagogically richer, but the report identifies unresolved rubric, consent, data-retention, asynchronous grading, and API/client versioning requirements. Deferred rather than silently introducing those decisions.

## Chosen initial form targets

Use the report's proposed closed-task totals as pilot targets for English only: A1 34, A2 39, B1 44, B2 54, C1 58. Retain the report's distribution across recognition, reading stimuli/questions, listening stimuli/questions, and controlled production. These figures do not represent validated MCER cut scores or guaranteed preparation. Candidate-pool targets must be independently sized to support form composition and diversity.

## Constraints and implementation calls

- Preserve the API's existing exam schema and closed item types; confirm existing clients and snapshot limits remain compatible with the larger forms.
- Version prompts/judge/blueprint independently from grading and snapshot schemas.
- For English, require active judge evidence for answer correctness, passage support, explanation, level/task fit, plausible distractors, and format. `flagged` content must not be auto-approved as clean evidence; existing human review remains available.
- Repair at most once per affected section, with sanitized deterministic error codes and explicit failure if minima/diversity cannot be met.
- Unknown provider outcomes are terminal for that call and are never retried automatically.
- The API does not load `.agents/skills/looro-content/SKILL.md`; runtime requirements belong in typed blueprint and inline API prompts. Any skill edit must clarify editorial scope only and not pretend it controls runtime generation.
- Before finalizing, run focused API tests, API type-check, exporter check if canonical model JSON changes, and the isolated API integration suite if its environment is available. Report unavailable checks honestly.

## Acceptance

- Captured English A1/C1 messages and validation rules differ according to their blueprint, including level-specific length and task requirements.
- A configured English form target controls served form size; candidate pool target controls generated candidate volume, with no hidden fixed-count override.
- Structural defects produce deterministic validation errors instead of crashes; English language checking does not approve clearly non-English text based on an empty-marker comparison.
- A repair test proves feedback is sent before the only corrective call, only the affected section is regenerated, and uncertain outcomes are not reissued.
- Judge reject/flagged/unknown-ID/missing-ID cases are genuinely exercised; unresolved flagged material is not silently considered clean.
- Three variants are evaluated by distinct item and stimulus sets; diversity promises are not inferred from hashes alone.
- Token usage aggregates only reported usage across generation/judge/repair; missing usage remains unknown rather than fabricated zero.
- Existing non-English behavior, published snapshots, historical grading, bundled pools, and API response schema remain unchanged.
- Longer forms remain within all API, sync, client body-size, and snapshot limits.

## Future evidence gate

Teacher review and a learner pilot are required before representing these forms as readiness evidence. Open responses, speaking, exam-specific alignment, changed scoring cutoffs, and readiness claims require a separately approved design and appropriate privacy/validation evidence.

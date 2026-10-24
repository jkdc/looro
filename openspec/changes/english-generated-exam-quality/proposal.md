# Proposal: English Generated Exam Quality and Length

## Intent

Improve quality, level differentiation, and learner-facing length of English API-generated level exams without creating authored exam banks or modifying other languages.

## Problem

The generation pipeline has a fixed 25-item form, requests only the fixed form counts despite larger candidate-pool targets, and does not consistently connect the English level blueprint to prompts, validators, and the active judge. The report also reproduced broken validation and corrective-retry behavior, misleading rejection coverage, and per-run token usage that remains zero.

## Approach

Use a single typed/resolved English blueprint across prompts, section validation, judge, composition, and publication. Expand the learner-facing closed form using the report's English pilot counts (34/39/44/54/58 from A1 to C1), while treating pool target and served form shape separately. Improve bounded repairs, evidence-based judging, diversity checks, and usage/provenance measurement. Keep existing schema, history, and non-English behavior intact.

## Out of scope

- Authored/bundled exam-bank generation or editing.
- Chinese or other non-English prompt/validator changes.
- Open writing/speaking evaluation, a separate readiness product, external-exam certification, or psychometric claims.
- Historical snapshot/grade rewriting.

## Evidence and limits

The targets are the report's initial pilot hypotheses, not validated cut scores or proof of readiness. A teacher-reviewed learner pilot remains a launch gate for claims about preparation quality.

# Proposal: Admin exam UX — split configuration from generation

## Intent

Separate the LLM provider configuration from the day-to-day exam generation
surface, and replace the raw-JSON exam modal with a full-page review screen an
operator can read and act on.

## Problem

`#level-tests` mixes three different jobs in one screen: provider credentials
and connection test (rare, sensitive), the generation launcher with its job
monitor (daily), and the weeks listing with moderation (daily). The exam detail
is a `<pre>` with `JSON.stringify(…, 2)`, so reviewing 25 items means decoding
JSON by eye: no sections, no marked correct answer, no judge verdict per item.

## Approach

- Sidebar group **Exámenes** with three entries: **Generar y revisar**
  (`#exam-weeks`), **Resultados** (`#exam-results`), **Configuración de IA**
  (`#ia-config`). `#level-tests` redirects to `#exam-weeks` for old bookmarks.
- The provider card moves to its own view; its "pending approval" link points to
  the generation view and counts drafts with `?status=draft&limit=1`.
- New route `#exam/<uuid>` renders a full-page review: header with model, judge
  mode, tokens and dates; a `N marcados por el juez` banner; variant tabs;
  sections in exam order; item cards with the accepted option marked, the
  explanation, the source unit and the judge verdict with its reason; reading
  and listening passages rendered once per `passageId`; sticky approve/reject.
- `levelTestWeekDetail` gains tokens, dates and prompt/judge versions so the
  header needs one request.

## Out of scope

Editing exam content, per-item regeneration, new grading logic, Android/iOS
changes, migrations, new public endpoints, provider changes.

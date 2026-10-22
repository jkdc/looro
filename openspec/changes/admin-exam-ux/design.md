# Design: Admin exam UX

## Navigation

Flat nav stays for the operational dashboards; the three exam surfaces get a
sidebar group heading. Grouping is data, not markup: each `NAV` entry may carry
`group`, and the boot renderer emits a heading whenever the group changes.

Why a group instead of tabs: configuration is rare and sensitive (a credential
form that can break every generation), generation is daily. Frequency and risk
differ, so they get different places; a group keeps them adjacent without
pretending they are one task. Deep links (`#ia-config`) are quotable in
incidents and reports.

## Views

| Route | Content | Frequency |
|---|---|---|
| `#exam-weeks` | scheduler + launcher + live status + jobs + weeks table + moderation | daily |
| `#exam-results` | student attempts, filters, summary, CSV | weekly |
| `#ia-config` | provider, model, base URL, key, publish/verify, variants, connection test | rarely |
| `#exam/<uuid>` | exam review page | daily |

## Review page

- Data: one `GET /v1/admin/level-tests/weeks/:id` returning metadata plus
  `pool`, `variants`, `review`.
- `variants[i]` already is the exam as the app serves it: `items` (flat, with
  `section` and optional `passageId`), `passages`, `thresholds`, `contentHash`.
  Rendering it needs no transformation.
- Sections are rendered in first-appearance order from `items`, so the operator
  reads the exam in the order the student takes it. Labels: recognition →
  "Reconocimiento", writing → "Escritura", reading → "Lectura", listening →
  "Escucha".
- Accepted answers mark options case-insensitively; written items show the
  accepted answers as chips.
- `review[]` maps `id → {verdict, reason}`; `flagged` gets a warning badge and
  the banner counts them.
- Approve/reject reuse the reason dialog and the existing endpoints; the page
  re-renders with the new status.

## Rendering safety

Content comes from an LLM: every string passes through `escapeHtml()`. Missing
`options`/`answers`/`passages`/`review` degrade to empty states, and an item
whose `passageId` has no passage renders standalone.

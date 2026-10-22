# Design: Admin exam polish

## Severity colours

One mapping (`BADGE_COLORS`) feeds every status badge, so weeks and jobs cannot
drift apart:

| Value | Tone | Meaning |
|---|---|---|
| `approved`, `finished` | success | usable, nothing to do |
| `rejected`, `failed` | danger | the operator has to look |
| `draft`, `unknown` | warning | waiting, or an outcome we could not confirm |
| `running` | info | in flight |
| `retired` | secondary | archived on purpose |

The existing `badge-outline` is reserved for identifiers (error codes, models),
so a red fill always means "state", never "label".

### The trap: `badge-success` does not exist

The panel's badges were grey for a reason that the tone map alone could not fix:
this Tabler build defines `.badge` (neutral surface) plus the `bg-*-lt`
utilities, and **no** `.badge-success` / `.badge-danger` / `.badge-warning`
classes. Those class names were silently inert — verified in the browser: both
`badge-danger` and `badge-success` computed `rgb(107,114,128)` on
`rgb(249,250,251)`, the same neutral pill.

Tone classes are therefore `bg-<tone>-lt` (saturated text on a 10 % tint), never
`bg-<tone>`: the solid utility only sets the background and would leave
`.badge`'s grey text on top of it. The E2E asserts the computed colour channels,
not the class name, so a future misspelled utility fails the test instead of
passing quietly.

## Why a column and not a wider `error_code`

`error_code` is a stable, bounded contract (badges, logs, alert rules) and is
validated by `^[a-z0-9_]{1,80}$`. Widening it would break that contract and let
free text into a column other systems read. The list of reasons is a different
fact with a different lifetime, so it gets its own column.

## Sanitizing (the security-relevant part)

`error_details` is written by `sanitizeGenerationErrorDetails()`:

- each entry is normalized (control characters → space, collapsed whitespace)
  and truncated to 160 characters;
- the entry must match `^[a-z0-9_.,()%:\- ]+$` — lowercase, digits, counts and
  basic punctuation. Uppercase, quotes, braces, slashes and newlines never
  survive, which is what removes provider JSON, stack traces and LLM content;
- duplicates are dropped and the list is capped at 10;
- an empty list falls back to the coarse `error_code`, so a red row always has
  something to show.

The migration repeats the shape constraint (`jsonb` array, ≤ 10) so the
invariant holds even if a future writer forgets the sanitizer.

## Status endpoint

`GET /v1/admin/level-tests/status` adds `errors: string[]` to `jobs[]` and
`latestJob`. `errorCode` is unchanged. The array is filtered to strings on read
(defence in depth, no `unknown` leaking into the panel).

## Error dialog

Reuses `#detail-modal`. `detail()` still writes `textContent` (JSON viewer,
unchanged); the new `detailHtml()` writes escaped markup for structured
payloads. `#detail-body` changed from `<pre>` to a `div` with
`white-space: pre-wrap` so both modes look right.

The dialog explains the failure with a pattern table over the code plus every
reason (`judge_failed_or_incomplete`, "only N passages", network/timeout,
`llm_outcome_unknown`, `interrupted_generation`, `reconciliation_required`,
`attempt_limit_reached`, `finished_without_week`, missing configuration or
model), falling back to a generic "check the provider configuration". It also
shows the run context (language/level/week, attempts, timestamps) and the
sanitized reasons verbatim in `<code>`.

## Menu

Grouping stays data: each `NAV` entry declares `group`, and the renderer emits
`<li class="nav-group"><span class="nav-link">` whenever the group changes.
Headings get an uppercase style, reduced opacity and a top border — and
`pointer-events: none`, so a heading can never be clicked or focused as a link.
The active state applies to `#nav a.nav-link` only.

Order: Operación (dashboards) → Exámenes → Comercial → Sistema.

## Test strategy

- Unit: the sanitizer keeps our messages, drops provider bodies/stack
  traces/user text, falls back to the code, caps at 10.
- Integration: the status endpoint returns `errorCode` plus a sanitized,
  non-empty `errors` array for a failed job.
- Browser E2E: a deterministic failed job fixture (en/B2, previous week) proves
  the red badge, the green `finished` badge and the dialog contents; the nav
  check asserts DOM membership (each group's links sit between its heading and
  the next one), not just the presence of a heading.

## Runs are history, not a mutable row

`level_test_generation_jobs` had PK `(language_id, level, week_index)`: every
attempt overwrote the previous one, so a retry erased the failure it was
retrying. Migration `049` gives each attempt its own `id` and keeps `attempts`
as the ordinal for its key; the runner inserts a row as `running` and closes
that same row on its terminal state. `exists` writes nothing — there was no
generation. Guards read the newest run by `attempts DESC`.

Retry policy: the automatic cap (`MAX_AUTOMATIC_ATTEMPTS = 2`) protects the
scheduler and CLI from re-running a broken generation forever. An operator
launch passes `manual: true` and opens the next run beyond the cap; attempts
keep counting so the history shows the real number of tries. `unknown`
(reconciliation required) is never bypassed by a manual retry.

## Provenance on the week

`origin` is written by the single `INSERT` (`persistWeek`) from a required
`GenerateOptions.origin`: the panel (launch and regeneration) sends
`user_operator`; the scheduler and the CLI send `automatic_worker`. Rows that
predate migration `048` stay `unknown` instead of inventing history. The listing
exposes it and the panel paints `Operador` / `Automático` / `—`.

## Watch with a run log

The status endpoint returns runs newest-first. The panel's `byKey` map must keep
the first row for a watched key (the live attempt), not the last: with a
mutable row the distinction did not exist.

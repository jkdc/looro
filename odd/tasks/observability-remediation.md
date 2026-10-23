# Observability remediation
Objective: Resolve the confirmed observability findings while preserving privacy, compatibility, opt-in generation and existing telemetry architecture.
Branch: `feature/observability`; delivery strategy `feature-branch-chain` (user directed work-unit commits on the current branch; no push/PR authorized), forecast approximately 900 authored changed lines; runner: TypeScript `node:test` via `tsx`, Prometheus `promtool` 2.54.1, Makefile Docker test targets.

## Specs

### S1 — Correct all confirmed review findings

The user authorized: “lanza sdd usando superpowers y resuelve todos los problemas que has encontrado y definido en el documento que has generado”.

Scope: implement H1–H9 and C1 from `informe-observabilidad-2026-10-23.md` as specified in `docs/superpowers/specs/2026-10-23-observability-remediation-design.md`. Do not promote the unconfirmed API/CLI metric identity hypothesis into a code change unless an isolated regression test reproduces incorrect metrics.

Acceptance: concrete PII markers cannot reach logs or spans; all retained Prometheus rules have functional assertions; generation and LLM failures/durations are correctly measured; HTTP spans and direct 5xx correlation are safe and compatible; all applicable tests pass. Keep historically green behavior and errors intact.

### S2 — Sunday scheduling without missing-generation alerts

The user clarified: “la generación de examenes debería ocurrir los domingos por tanto siempre podemos saber si se han generado o no cada lunes o cada domingo unos horas despues de que corra el worker que los genera, te vale esta respuesta?” They approved: “ok”.

Scope and accepted decisions: unspecified scheduler day defaults to ISO Sunday (`7`); retain the current UTC hour (`4`); keep generation opt-in. Do not alert merely because a scheduled generation is missing or stale. Keep an alert for an actual failed execution, driven by failure metrics; no migration or startup timestamp marker.

Acceptance: explicit schedule overrides remain effective; actual failed attempts are observable and alertable, including the first failure before a scrape and after API restart when durable records exist. Absence of a scheduled run alone never triggers a generation alert.

### S3 — Preserve opt-in and public contracts

The user's exact setting is: “mantenemos el interruptor optativo”.

Scope: leave `LEVELTEST_GENERATE_ENABLED=false` when unset. Do not enable scheduled LLM calls, inspect remote deployment state, contact real model providers, or alter existing 502 JSON bodies. Add correlation to direct 5xx responses only through a compatible, documented surface.

Acceptance: default-disabled tests observe zero generated jobs; explicit `true` still starts the scheduler; client-visible status/body/code fields remain stable; user services and remote state are untouched.

### S4 — Approved design and verification boundaries

The design was approved with “dale” and is maintained at `docs/superpowers/specs/2026-10-23-observability-remediation-design.md`; the later alert requirement supersedes the former four-hour missing-run alert.

Constraints: no database migration, no change of stack/backend, no undocumented PII or high-cardinality labels, no proof claim from syntax-only checks. Work stays on `feature/observability`; native RDD status currently fails because `gentle-ai` is unavailable, so never claim native approval. The later alert requirement supersedes the four-hour missing-generation alert in the original approved design.

Acceptance: tests assert observable log/span/metric/rule behavior; config validation and functional rule tests both run; full API checks are reported honestly; independent verification covers the high-risk privacy change.

## Tasks

- [x] T1 — S1/S3: secure request logging, server spans and compatible direct 5xx correlation; route inline; complete; commits `2bc4101` (SDD plan) and `756dc7e` (implementation); focused tests 53 pass, 2 DB-only skipped; typecheck passes.
- [x] T2 — S1/S2: Sunday UTC scheduler default and documentation; route inline; complete; commit `d356082`; focused scheduler tests 12 passed and API TypeScript check passed.
- [x] T3 — S1/S3: job/LLM failure-path duration and outcome metrics plus true no-op/bounded telemetry startup; route inline; complete; commit `a0b6b84`; focused tests 32 passed, 11 skipped (DB-dependent) and API TypeScript check passed.
- [ ] T4 — S1/S2: repair alert math, remove missing/stale-generation alert, retain failed-execution alert, add behavioral promtool tests and wire them into CI; route inline; status planned; commit pending.
- [ ] T5 — S1–S4: isolated API/CLI metric probe, reconcile original plan/OpenSpec/runbooks/report, run full applicable suites; route inline; status planned; commit pending.

Detailed red/green steps and exact commands are in `docs/superpowers/plans/2026-10-23-observability-remediation.md`.

## Log

- L1 — “lanza sdd usando superpowers y resuelve todos los problemas que has encontrado y definido en el documento que has generado”.
- L2 — “la generación de examenes debería ocurrir los domingos por tanto siempre podemos saber si se han generado o no cada lunes o cada domingo unos horas despues de que corra el worker que los genera, te vale esta respuesta?”
- L3 — “mantenemos el interruptor optativo”.
- L4 — The user approved the original four-hour grace and design (“ok”; “dale”); L6 later supersedes the missing-run alert requirement.
- L5 — Design baseline: Sunday ISO day 7, 04:00 UTC default, auto-generation remains opt-in, no migration. The previous four-hour grace proposal is superseded by L6.
- L6 — Requirement change (2026-10-23): “de momento creo que lo mejor es no tener alertas para la generación de examenes, en su defecto lo que sí quiero es que si falla la generación generar métricas y estas generen alertas. los ves acorde y coherente?” The user confirmed and authorized the direction: “me parece perfecto, lo ideal sería que actualices la documentación con esta nueva requisito y lo implementes”. This supersedes missing/stale-week alerting and removes the four-hour alert grace; actual failed runs remain alertable from metrics.
- L7 — Baseline `a5b103680feac26b2ac4242d081528e57a7e8a3d`, branch `feature/observability`; pre-existing untracked workspace files are user data and must not be staged accidentally.
- L8 — `gentle-ai review mode status` returned exit 127 (`command not found`); preserve RDD as unknown. Do not fabricate native review approval.
- L9 — Task 1 implementation: server spans no longer export `url.path`; logs use matched template/`unmatched`, preserve reserved envelope fields and reject sensitive field names, request context uses AsyncLocalStorage, spans are SERVER/ERROR, and all 5xx add `X-Request-Id` while preserving existing response bodies. Regression tests include the actual `/v1/admin/llm-config/test` timeout. RED observed for logger envelope overwrite (`event=forged`) and missing active request context. Focused test command: `tsx --test tests/observability.test.ts tests/http.test.ts tests/level-tests-policy.test.ts` → 53 passed, 2 skipped for PostgreSQL; `tsc --noEmit` passed. Native RDD CLI remains unavailable.
- L10 — “ve haciendo commits sobre la rama en la que estamos trabajando”. Work-unit commits stay on `feature/observability`; no push or PR is authorized.
- L11 — Task 1 committed on `feature/observability`: `756dc7e fix(observability): protect request telemetry and classify server spans` (192 insertions, 18 deletions); the execution plan/tracker/design are committed at `2bc4101 docs(observability): track remediation plan` (352 additions). Focused API tests and type-check passed before the implementation commit; no user services or remote systems were touched.
- L12 — A prior read-only mapping handoff could not start; no Task 2 source changes were made before the current user-authorized scope revision.
- L13 — T2 RED: scheduler config test observed actual default `day=1`, expected `day=7`; GREEN after changing only the unspecified default and Compose fallback to Sunday. Existing Monday-specific helper tests remain explicit; scheduler lifecycle fixtures were moved to Sunday to continue testing default-schedule behavior. `tsx --test tests/level-tests-scheduler.test.ts` passed 12/12; `tsc --noEmit` passed. Commit: `d356082`.
- L14 — T3 RED/GREEN: focused observability tests proved missing durations on LLM/job errors, missing failed-execution metrics for thrown connection/query/finalization errors, invented zero token counters when provider usage is absent, and a startup seed query despite telemetry being disabled. Added a durable last-failure timestamp gauge, bounded (2s) best-effort seeding only when OTEL is enabled, and failure-path metrics in `finally`; preserved original exceptions, no-op statuses, zero-retry behavior, and public token values. Focused observability/scheduler/job tests and `tsc --noEmit` passed; DB-only cases remained skipped without `TEST_DATABASE_URL`.

# Proposal: multilanguage-parity

## Intent

Completar los 6 idiomas (es/fr/it/de/pt) hasta paridad jugable con el
temario inglés, siguiendo `docs/plan_completar_idiomas_2026-10-01.md`.

## Problem

Inglés tiene la escalera completa (27 unidades/82 lecciones, 44 listenings,
22 readings, 5 checkpoints, 5 tracks); los otros 5 idiomas están en 12-17
lecciones con escalera hasta B1. No hay un medidor objetivo de la brecha.

## Approach

Fases F0-F10 del plan: medidor de paridad, escalera B2/C1 × 5 idiomas,
engorde A1 (3 olas temáticas), A2, B1, primer track por idioma, módulo de
pronunciación (F2-F5 de su SDD propio) y cierre. Cada fase = un commit
convencional + verify-report, autorado con la skill `looro-content`.

## Definition of done

`content_health.py --parity` reporta cero huecos para los 5 idiomas:
82 lecciones, 34 listenings, 19 readings, 5 checkpoints, 1 track, y la
cobertura pron en 8/8 (detrás del feature flag en la app).

## Scope

Solo `shared/*.json` (más el medidor y pins de tests). Sin cambios de
motor: MasteryEngine/DailyPlanEngine ya consumen el catálogo universal.

## Out of scope

Tracks 6-8 de inglés (doc §12), snapshot de mastery en servidor,
gate macOS.

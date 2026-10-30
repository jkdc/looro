# Free Drip Read Fast Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans; ejecución directa ya elegida. No nuevos requisitos respecto a las secciones4–5 del diagnóstico aprobado al continuar.

**Goal:** Evitar el recorrido transaccional de drip cuando no puede aportar nuevos minutos.
**Architecture:** Guard de configuración fresca y lectura positiva por cuenta/periodo; fallback transaccional sin cambios.
**Tech Stack:** TypeScript, pg/PostgreSQL17, node:test; sin dependencias nuevas.
**Spec:** design.md y specs/free-drip-read/spec.md de este change.

## Global Constraints
Rama activa, sin worktree/push/PR/Gentle AI; código English, informes español; preservar firma de runDripForAccount y no editar migraciones.

## Review Focus
Concesión pagada no equivale a gratuita; dos misses concurrentes no conceden dos veces; cambio UTC/configuración no queda cacheado; eliminación del marcador se observa; errores SQL no inventan crédito.

## Task 1: Pruebas y guard equivalente (S1–S3)
Files: modificar services/api/src/subscriptions.ts; crear services/api/tests/free-drip-fast-path.test.ts. Interfaz existente runDripForAccount(db,account,now?):Promise<number> permanece.
- [x] Escribir regresiones con PostgreSQL para disabled/zero/existing: resultado y persistencia iguales,0 adquisiciones transaccionales explícitas; verificar RED contra360ab43.
- [x] Añadir preservación para carrera inicial, periodo UTC nuevo, paid-marker, guest/activo, presupuesto y errores reales SQL; distinguir casos ya verdes de RED del cambio.
- [x] Type-check inicial; implementar guard mínimo con query compatible con índice subscription_grants_period_idx. No borrar el guard ni ON CONFLICT del fallback.
- [x] Ejecutar focused PG y suite `make test-api`; registrar resultados y commit convencional junto con pruebas/docs.

## Task 2: Verificación y coste antes/después (S4)
- [x] Construir imagen baseline antes de modificar fuente y candidate después; comparar tres repeticiones intercaladas400/800 ofrecidas/s, misma mezcla/datos, sin suite concurrente.
- [x] Medir llamadas SQL/transacciones y respuesta útil, errores, latencias, dropped y contabilidad; no promediar percentiles como si fueran percentil global.
- [x] Verificador independiente sobre este change completo y base360ab43, probes scratch/PG sintético; una corrección acotada si hace falta.
- [x] Actualizar tasks/verify-report e informe de diagnóstico: realizado vs pendiente, límites del experimento y Risk financiero/concurrencia.

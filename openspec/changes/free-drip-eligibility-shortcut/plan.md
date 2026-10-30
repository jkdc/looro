# Drip Eligibility Shortcut Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Ejecución directa ya elegida; sin escrituras delegadas.

**Goal:** Eliminar el sobrecoste medido de invitados/suscriptores que no reciben drip.
**Architecture:** CASE de evidencias positivas; fallback transaccional inalterado.
**Tech Stack:** Node22+,TypeScript,pg,PostgreSQL17,node:test.
**Spec:** design.md y specs/drip-eligibility/spec.md de este change.

## Global Constraints
Rama activa, sin worktrees/Gentle AI/push/PR. No caché, DDL o cambio de reglas financieras. Código/tests English, informes español. Base `aa51066`.

## Review Focus
Los guards adelantados solo deniegan; un miss no concede. Trialing/active excluyen y past_due/canceled no. Configuración no queda cacheada. Dos candidatos concurrentes que llegan a la transacción siguen únicos. CASE de grant resuelto no ejecuta subplanes caros.

## Task 1: Implementación y regresiones S1–S3
Files: services/api/src/subscriptions.ts, services/api/tests/free-drip-fast-path.test.ts.
- [x] Type-check inicial. Sustituir la barrera de prueba basada en textoSQL por adquisición transaccional real.
- [x] Añadir guest/trialing/active no-Tx y persistencia; observar3RED sobre la base. Añadir preservación past_due; casos anteriores siguen verdes.
- [x] Consolidar query CASE/EXISTS, usar boolean tipado skip_grant y conservar fallback completo.
- [x] FocusedPG, suite `make test-api`,type-check y EXPLAIN ANALYZE; registrar y commit convencional.

## Task 2: Comparación y verificación S4
- [x] Imagen before congelada antes de modificar fuente; candidate después. Tres repeticiones intercaladas400/800mezcla libre y100/sguest/suscrito; mismo fixture/recursos, sin suite concurrente.
- [x] Verificar contabilidad y SQLporperfil; documentar misses de candidatos nuevos y límites del generador, no promediar percentiles.
- [x] Verificador independiente sobre este alcance/base y scratch fuera del workspace; una corrección acotada si bloqueantes.
- [x] Informe final y enlaces a cambios previos con SHA, checks observados/pendientes y Risk financiero/concurrencia.

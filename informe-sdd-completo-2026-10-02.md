# Informe: sesión SDD multi-fase — 2026-10-02

Ejecución subagent-driven-development de todas las fases pendientes del
proyecto, en secuencia, con revisión por tarea y revisión final por rama.
Rama `feature/learning-platform`; 5 fases + preparación; **40 commits**.

## Fase 0 — Base verificada (4 commits: 9256871, bf552e5, 8d7858e, a12d839)

Commiteo del trabajo scenario-practice verificado que estaba sin commitear
(Android + API + migraciones 035-037 + evidencia e2e + informes históricos +
skill looro-content). Verificado antes de commitear: suite Android verde,
backend 386/391 (5 fallos ambientales Node 18 conocidos), árboles agrupados en
4 commits por concern.

## Fase 1 — scenario-practice 6.x: paridad iOS (a12d839 → d542741)

- T1 Core: tipos de review + validador espejo de `ScenarioReviewing.kt`
  (83246c3). T2 wiring: sesión scenario en `ConversationCoordinator` +
  petición de review + eventos de uso; fix ronda 1: esperar confirmación del
  close hospedado antes del helper (b83db79). T3 UI: `ScenarioReviewSheet`,
  picker "Practise a situation" en la pantalla de temas, montaje en RootView,
  cierre del cambio openspec con gate macOS-pending (d542741).
- Review final de rama: MERGE-READY; 11 minors aceptados y triados.
- Gates: `check_cross_platform.py` verde; Swift sin compilar localmente
  (gate macOS pendiente, registrado honestamente).

## Fase 2 — multilanguage-parity: cierre F5 (e44c7f9)

F5 estaba cubierto por las olas de relleno (A1 = 14u/40l en los 6 idiomas,
listenings A1 ≥15, temas transporte/tiendas/salud/hobbies presentes;
PARITY_GAP = 0). Tarea de verificación y cierre: checkbox F5 marcado con
sección "Verify F5 + closure", F6/F7/F9 cerrados con evidencia, verify-report
e informe nuevos. Nota de honestidad: cierre a posteriori por verificación,
sin autoría nueva.

## Fase 3 — mirrors iOS + reparaciones Android (e44c7f9 → 4a95853)

- T1: UI iOS de recall/notebook (RecallViews.swift; tarjeta en Learn,
  sesión de recall, notebook bajo Words) + cierre de spaced-recall-notebook
  (a733903; fix de redacción en 1903b69). Descubrió 2 regresiones Android.
- T2: gate macOS declarado en progress-exercises-listening (1cf0d8f).
- T3: reparaciones Android: reset de answer/verdict en RecallScreen
  (regresión d388c86) + aterrizaje de la sección notebook en Words con
  etiquetas de origen y CTAs (regresión 033252c) + fix de reasignación de
  estado para recomposición (e3afbd9; fix wave final 482b623/4a95853).
- 518/518 JVM re-verificado en HEAD. Seguimiento aparcado: el remove del
  notebook es no-op para filas de otro idioma (decisión cross-platform
  pendiente de diseño).

## Fase 4 — token-billing completo (4a95853 → e28bed6, 14 commits)

- T1 packs: unlock $6.99 + pack $4.99 (2,16 USD de crédito nanoUSD, rate
  2026-09-minimax-m) por los paths existentes de Stripe/Play con
  reconciliación completa; fix ronda 1: unlock concedido solo en entorno
  live (9a8f69e, 567a264).
- T2 proxy v1: no-streaming documentado (400 streaming_unsupported_v1),
  fase de load; fix ronda 1: cap de concurrencia por cuenta
  (PROXY_MAX_CONCURRENT, 429 account_busy) exigido por design (5fdaa59,
  396ac17).
- T3 engine `hosted-proxy` en ambas fábricas; fix ronda 1: etiquetas
  `usage:` en Swift (2 sitios), nota de operador (modelo MiniMax-M2),
  contrato de bearer eager, guardia de base URL (15a9bd3, b1b3659).
- T4 Android: fila "Palique (tokens)", paywall, hoja de compra, copy de
  crédito prepagado, ruta de errores 402/403, aviso sandbox (80f5b96).
- T5 iOS mirror; fixes rondas 1-2: 3 Critical de parsing/URL/visibilidad +
  restauración del mapeo 401 (479a39a, 2a1f403, 1cfc27d).
- T6 admin browse (entitlements + metering) + cierre honesto del cambio
  (78eb504, 4c84eaa); fix wave final: re-concesión de unlock tras refund +
  evidencia de refund en el estado (0357f7a) + resets iOS y filtro de hoja
  de minutos (e28bed6).
- Gates finales: backend 419 tests (414 pass + 5 ambientales), Android
  548/0, cross-platform verde. Pendientes de operador registrados: Play
  Console, precios finales, gate macOS, server desplegado.

## Fase 5 — temario-volume-expansion (e28bed6 → 775f05b, 6 commits)

- F-V1/F-V2 cerradas como superadas con evidencia (olas de relleno).
- Autoría nueva: B1 "Getting things done" y "Dealing with problems",
  B2 "Innovation & change" (9 lecciones 5/5/5/4), 3 listenings y 3 readings
  vinculados, 5 skills nuevas al catálogo (111 total, cobertura 100%,
  sin inversiones de prerequisitos). Inglés 27u/82l → 30u/91l
  (B1 14→20, B2 5→8); 283 listenings / 125 readings en catálogo.
- 2 rondas de fix de contenido (distractor ambiguo, nota de registro,
  apóstrofe, tags decorativos; atribución de hablante, phrasal objetivo,
  colocación). tasks.md + verify-report + informe del cambio creados;
  PARITY_GAP = 0. Gradle 548/0 en bb00c98 (shared/ sin cambios desde).

## Verificación global de la sesión

- Android: `:app:testDebugUnitTest` 548/548 + `assembleDebug` verde.
- Backend: 419 tests, 414 pass, 5 fallos ambientales conocidos (Node 18
  webcrypto + ventana de fechas), reproducidos en árbol limpio.
- Contenido: validadores, 4 exporters --check, content_health --parity 0.
- `scripts/check_cross_platform.py` verde en cada tarea.
- Cada tarea: implementador → revisor independiente → fix loop → re-review
  con alcance; revisión final de rama por fase (MERGE-READY ×5).

## Pendientes (registrados, no bloqueantes)

- Gate macOS: compilar/testear todo el Swift nuevo (apps + LearningCore).
- Decisión de diseño cross-platform: notebook multi-idioma (remove no-op).
- Operador: productos Play Console, precios finales, server desplegado,
  e2e en dispositivo de los flujos de compra y del engine swap.
- Espejo B1/B2 por idioma y tracks 6-8 (olas futuras de contenido).
